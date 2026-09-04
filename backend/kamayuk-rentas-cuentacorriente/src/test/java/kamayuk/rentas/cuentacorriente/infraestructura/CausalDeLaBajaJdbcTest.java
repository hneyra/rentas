package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El libro dice <b>por que</b> se dio de baja una deuda (#684, V77, RF-044 y RF-045).
 *
 * <p>Se conecta como {@code sgtm_app}, nunca como {@code sgtm_owner}: con {@code FORCE ROW LEVEL
 * SECURITY} el dueno de la tabla <b>tambien</b> queda sujeto a la politica, asi que una rotura de
 * aislamiento escrita con el dueno pasaria en verde sin demostrar nada (#537, #545, #601). Quien la
 * omite es el superusuario del cluster, y con el se mide.
 *
 * <h2>Que defiende</h2>
 *
 * <ul>
 *   <li><b>AC 1 — vocabulario cerrado, y el reparto de #542.</b> El dominio nombra el valor
 *       recibido y los admitidos; la base para lo que entra por SQL directo. Son <b>tres</b> {@code
 *       CHECK}: el vocabulario, «solo una baja tiene causal» y «toda baja nueva declara la suya».
 *   <li><b>AC 2 — el filtro de RF-045 acota de verdad.</b> Dos bajas con causales distintas y el
 *       filtro devuelve <b>una</b>, comparando las filas devueltas contra lo sembrado; que conteste
 *       200 no dice nada (la rotura con la que #425 midio las nueve operaciones desajustadas).
 *   <li><b>AC 3 — las bajas anteriores a V77.</b> No se pueden reparar y por eso su {@code CHECK}
 *       va {@code NOT VALID}: la prueba reproduce la instalacion con historia —una baja sin causal
 *       ya escrita— y comprueba que la migracion entra igual, que validarla habria fallado, y que
 *       esa fila sigue saliendo en la relacion sin filtro y desaparece al filtrar por una causal.
 *   <li><b>AC 4 — el contraste.</b> La observacion sigue siendo obligatoria (regla 10) y sigue
 *       siendo <b>del usuario</b>: la causal no se escribe dentro de su texto ni lo sustituye.
 * </ul>
 */
@DisplayName("#684 — La causal de la baja es una columna, no un trozo de la observacion")
class CausalDeLaBajaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate DIA_DEL_ACTO = LocalDate.of(2026, 4, 10);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T14:00:00Z"), ZoneId.of("America/Lima"));
    private static final Paginacion PAGINA = Paginacion.de(0, 50, "fecha_valor");

    /** El texto que teclea quien atiende. No lleva la causal dentro, y eso es el AC 4. */
    private static final String RELATO = "Se deshace el cargo del expediente 118-2026";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static AsientoRepositoryJdbc asientos;
    private static RegistrarAsiento registrar;

    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("280101", "Municipalidad de causales A");
        municipalidadB = crearMunicipalidad("280102", "Municipalidad de causales B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        // El proxy obedece a la anotacion —AnnotationTransactionAttributeSource—, como el
        // contenedor: un TransactionTemplate incondicional dejaria la prueba pasando con el
        // @Transactional quitado, que es el modo de fallo que existe para impedir (#486, #535).
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  El centinela: si esta prueba deja de correr como sgtm_app, no mide nada
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la prueba se conecta como sgtm_app, no como el dueno ni como superusuario")
    void seConectaComoSgtmApp() {
        String usuario =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(usuario)
                .as(
                        "con FORCE ROW LEVEL SECURITY el dueno tambien queda sujeto a la politica,"
                                + " asi que una prueba escrita con sgtm_owner pasaria en verde sin"
                                + " medir el aislamiento (#537, #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------
    //  AC 1 — vocabulario cerrado: el dominio nombra, la base para
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — el vocabulario es cerrado por los dos lados")
    class ElVocabulario {

        @Test
        @DisplayName("el dominio rechaza lo que no es una de las seis, y nombra lo admitido")
        void elDominioNombraLoAdmitido() {
            assertThatThrownBy(() -> CausalDeBaja.de("PRESCRIPCION"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PRESCRIPCION")
                    .hasMessageContaining("PRESCRIPCION_DECLARADA")
                    .hasMessageContaining("CONDONACION_POR_ORDENANZA");
        }

        @Test
        @DisplayName("el rotulo del manual —con su tilde y su espacio— tampoco entra")
        void elRotuloDelManualNoSeTraduceAqui() {
            // La traduccion le toca a la pantalla, con una tabla (#542). Una lectura
            // tolerante que quitara tildes y cambiara espacios por guiones bajos haria
            // entrar cualquier texto parecido, y lo que se clasifica aqui es el sustento
            // juridico de un acto que extingue deuda del municipio.
            assertThatThrownBy(() -> CausalDeBaja.de("PRESCRIPCIÓN DECLARADA"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CausalDeBaja.de("COMPENSACIÓN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("por SQL directo, una causal fuera del vocabulario la para el CHECK")
        void porSqlDirectoLaParaElCheck() throws SQLException {
            long titular = nuevoTitular(nuevoCodigo());
            assertThatThrownBy(() -> insertarBaja(titular, "PREDIAL", "PRESCRIPCION"))
                    .as("la guarda de Java no llega a la base; el reparto de #542")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("asiento_causal_ck");
        }

        @Test
        @DisplayName("un asiento que no es una baja no puede llevar causal")
        void soloUnaBajaTieneCausal() throws SQLException {
            long titular = nuevoTitular(nuevoCodigo());
            assertThatThrownBy(
                            () ->
                                    insertarAsiento(
                                            titular,
                                            "PREDIAL",
                                            "CARGO",
                                            "ALTA_DEUDA",
                                            "PRESCRIPCION_DECLARADA"))
                    .as("el desplegable «Causal» es el de la baja; el alta se sustenta en su papel")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("asiento_causal_del_acto_ck");
            assertThatThrownBy(
                            () ->
                                    insertarAsiento(
                                            titular,
                                            "PREDIAL",
                                            "ABONO",
                                            null,
                                            "PRESCRIPCION_DECLARADA"))
                    .as("un cobro tampoco: no nace de un alta ni de una baja")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("asiento_causal_del_acto_ck");
        }

        @Test
        @DisplayName("y una baja NUEVA sin causal tampoco entra, aunque el CHECK sea NOT VALID")
        void unaBajaNuevaSinCausalNoEntra() throws SQLException {
            long titular = nuevoTitular(nuevoCodigo());
            assertThatThrownBy(() -> insertarBaja(titular, "PREDIAL", null))
                    .as(
                            "NOT VALID no comprueba lo que ya hay y SIGUE comprobando cada INSERT:"
                                    + " es lo que deja pasar la historia sin dejar pasar el defecto")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("asiento_baja_con_causal_ck");
        }

        @Test
        @DisplayName("y el dominio no deja ni construir la peticion de una baja sin causal")
        void elDominioTampocoDejaConstruirla() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeDeuda(
                                            SentidoDelMovimiento.BAJA,
                                            new ClaveDeSaldo(
                                                    1L, "PREDIAL", EJERCICIO, 1, null, null),
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Fase.ORDINARIA,
                                            DIA_DEL_ACTO,
                                            "RES-0001",
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declara su causal");
        }
    }

    // ------------------------------------------------------------------
    //  AC 2 — el filtro de RF-045 acota de verdad
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 2 — RF-045 filtra por causal, y acota")
    class ElFiltro {

        @Test
        @DisplayName("dos bajas con causales distintas: el filtro devuelve una, y es la suya")
        void elFiltroDevuelveUna() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "PREDIAL", "300.00");
            darDeBaja(titular, "PREDIAL", "100.00", CausalDeBaja.PRESCRIPCION_DECLARADA, 1);
            darDeBaja(titular, "PREDIAL", "50.00", CausalDeBaja.ERROR_MATERIAL, 2);

            assertThat(relacion(codigo, CausalDeBaja.PRESCRIPCION_DECLARADA))
                    .as(
                            "no basta con que conteste 200: se comprueban las filas devueltas"
                                    + " contra lo sembrado (#425)")
                    .singleElement()
                    .satisfies(
                            asiento -> {
                                assertThat(asiento.causal())
                                        .isEqualTo(CausalDeBaja.PRESCRIPCION_DECLARADA);
                                assertThat(asiento.monto()).isEqualTo(Dinero.de("100.00"));
                            });

            assertThat(relacion(codigo, CausalDeBaja.ERROR_MATERIAL))
                    .singleElement()
                    .satisfies(
                            asiento -> assertThat(asiento.monto()).isEqualTo(Dinero.de("50.00")));
        }

        @Test
        @DisplayName("sin filtro salen las dos bajas y el alta: la relacion no cambia")
        void sinFiltroSalenTodas() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "ARBITRIO", "300.00");
            darDeBaja(titular, "ARBITRIO", "100.00", CausalDeBaja.PRESCRIPCION_DECLARADA, 1);
            darDeBaja(titular, "ARBITRIO", "50.00", CausalDeBaja.COMPENSACION, 2);

            assertThat(relacion(codigo, null))
                    .extracting(Asiento::causal)
                    .containsExactlyInAnyOrder(
                            null, CausalDeBaja.PRESCRIPCION_DECLARADA, CausalDeBaja.COMPENSACION);
        }

        @Test
        @DisplayName("el filtro deja fuera las altas por si mismo: un alta no tiene causal")
        void elFiltroDejaFueraLasAltas() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "VEHICULAR", "300.00");
            darDeBaja(titular, "VEHICULAR", "40.00", CausalDeBaja.DEUDA_DE_COBRANZA_DUDOSA, 1);

            assertThat(relacion(codigo, CausalDeBaja.DEUDA_DE_COBRANZA_DUDOSA))
                    .extracting(Asiento::documentoOrigen)
                    .containsExactly("RES-BAJA-VEHICULAR");
        }

        @Test
        @DisplayName("la reversion de una baja conserva su causal: el acto deshecho sigue hallable")
        void laReversionConservaLaCausal() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "ANUNCIOS", "150.00");
            darDeBaja(titular, "ANUNCIOS", "150.00", CausalDeBaja.CONDONACION_POR_ORDENANZA, 1);

            Asiento laBaja =
                    relacion(codigo, CausalDeBaja.CONDONACION_POR_ORDENANZA).stream()
                            .findFirst()
                            .orElseThrow();
            transaccion.execute(
                    estado ->
                            registrar.reversar(
                                    Objects.requireNonNull(laBaja.id()),
                                    LocalDate.of(2026, 6, 1),
                                    "RES-REVERSION",
                                    Observacion.de("La ordenanza no cubria este tributo")));

            assertThat(relacion(codigo, CausalDeBaja.CONDONACION_POR_ORDENANZA))
                    .as(
                            "la fila que DESHACE el acto tiene que salir con el: es el rastro de"
                                    + " que aquella condonacion se deshizo (regla 4)")
                    .extracting(Asiento::documentoOrigen)
                    .containsExactlyInAnyOrder("RES-BAJA-ANUNCIOS", "RES-REVERSION");
        }
    }

    // ------------------------------------------------------------------
    //  AC 3 — las bajas anteriores a V77
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 3 — las bajas anteriores a V77 no se pueden reparar, y se dice")
    class LasAnterioresAV77 {

        /** La clausula del CHECK que esta prueba retiro, para poder reponer LA MISMA. */
        private @Nullable String definicionRetirada;

        @Test
        @DisplayName("una instalacion con historia migra igual, y validar el CHECK habria fallado")
        void laMigracionEntraSobreUnaBajaSinCausal() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            // La forma exacta de una fila anterior a V77: BAJA_DEUDA con la causal en nulo,
            // porque hasta #684 viajaba dentro del texto de la observacion. Hoy no se puede
            // producir —ni desde Java ni por SQL directo—, asi que para tenerla hay que
            // retirar el CHECK, escribirla y volver a ponerlo: que es literalmente lo que
            // pasa en una instalacion en marcha el dia que se aplica la migracion.
            retirarElCheckDeLaBaja();
            insertarBaja(titular, "JUEGOS", null);
            darDeBaja(titular, "JUEGOS", "20.00", CausalDeBaja.ERROR_MATERIAL, 2);

            // Y aqui esta el motivo de que V77 lo declare NOT VALID: asi entra.
            volverAPonerElCheckDeLaBaja(false);

            assertThat(relacion(codigo, null))
                    .as("sin filtro, la baja vieja sigue en la relacion: no desaparece del control")
                    .hasSize(2);
            assertThat(relacion(codigo, CausalDeBaja.ERROR_MATERIAL))
                    .as(
                            "al filtrar por una causal concreta la vieja no aparece: NULL no es"
                                    + " ninguna de las seis, y no se puede reparar (V7, regla 4)")
                    .singleElement()
                    .satisfies(
                            asiento -> assertThat(asiento.monto()).isEqualTo(Dinero.de("20.00")));

            // Y la otra mitad: validado habria dejado la instalacion SIN MIGRAR, que es lo
            // que V64 midio para el tipo de transferencia.
            assertThatThrownBy(this::validarElCheckDeLaBaja)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is violated by some row");
        }

        @Test
        @DisplayName("y una baja sin causal todavia se puede REVERSAR: es la unica correccion")
        void laBajaViejaSeSigueReversando() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            retirarElCheckDeLaBaja();
            insertarBaja(titular, "VEHICULAR", null);
            volverAPonerElCheckDeLaBaja(false);

            Asiento laVieja = relacion(codigo, null).stream().findFirst().orElseThrow();

            // `Asiento#reversionDe` COPIA la causal, asi que la reversion de una baja
            // anterior a V77 nace con la causal en nulo. Sin la rama del
            // `asiento_reversado_id` en el CHECK, la unica forma de corregir un asiento
            // (V2, regla 4) quedaria cerrada justo sobre las filas que no se pueden tocar
            // de ninguna otra manera — el mismo motivo por el que V75 excluye la reversion
            // del indice del alta.
            transaccion.execute(
                    estado ->
                            registrar.reversar(
                                    Objects.requireNonNull(laVieja.id()),
                                    LocalDate.of(2026, 6, 1),
                                    "RES-REVERSION-VIEJA",
                                    Observacion.de("La baja de 2025 no correspondia")));

            assertThat(relacion(codigo, null))
                    .as("la baja vieja y su reversion: dos filas, ninguna modificada")
                    .extracting(Asiento::documentoOrigen)
                    .containsExactlyInAnyOrder("RES-VIEJA", "RES-REVERSION-VIEJA");
        }

        /**
         * Estas dos pruebas RETIRAN el CHECK para poder sembrar la baja sin causal que una
         * instalacion en marcha ya tiene, y lo vuelven a poner. Si una de ellas revienta por el
         * camino, el CHECK se queda fuera y las siguientes miden otra cosa sin decirlo —lo que
         * ocurrio de verdad al mezclar V74, y el sintoma fue «constraint does not exist» en una
         * prueba que no lo habia tocado—. Asi que se repone pase lo que pase.
         */
        @AfterEach
        void reponerElCheck() throws SQLException {
            if (definicionDelCheck() == null) {
                volverAPonerElCheckDeLaBaja(false);
            }
        }

        /**
         * La definicion del CHECK **leida del catalogo**, no escrita aqui.
         *
         * <p>Y no es un adorno: la primera version de esta clase reponia el CHECK con su texto
         * copiado a mano, y entonces mutar `V77` —quitarle la rama del `asiento_reversado_id`— daba
         * CERO pruebas en rojo, porque la que reversa una baja vieja acababa midiendo el CHECK que
         * la propia prueba escribia. Leyendolo de `pg_constraint` la prueba replica lo que la
         * migracion declara, y esa mutacion vuelve a morder.
         *
         * @return la clausula {@code CHECK (...)} tal cual la declara la migracion aplicada, o
         *     {@code null} si el CHECK no esta puesto.
         */
        private @Nullable String definicionDelCheck() throws SQLException {
            try (Connection duenno = base.conexion(BaseDeDatosDePrueba.OWNER);
                    PreparedStatement sentencia =
                            duenno.prepareStatement(
                                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                            + " WHERE conname = 'asiento_baja_con_causal_ck'")) {
                try (ResultSet resultado = sentencia.executeQuery()) {
                    return resultado.next() ? resultado.getString(1) : null;
                }
            }
        }

        private void retirarElCheckDeLaBaja() throws SQLException {
            definicionRetirada = definicionDelCheck();
            assertThat(definicionRetirada)
                    .as("el CHECK de V77 tiene que estar puesto antes de retirarlo")
                    .isNotNull();
            ejecutarComoDueno(
                    "ALTER TABLE cuenta_corriente_asiento"
                            + " DROP CONSTRAINT asiento_baja_con_causal_ck");
        }

        private void volverAPonerElCheckDeLaBaja(boolean validado) throws SQLException {
            String definicion = Objects.requireNonNull(definicionRetirada);
            // `pg_get_constraintdef` devuelve «CHECK (...) NOT VALID»; el NOT VALID se
            // recorta cuando la prueba quiere volver a ponerlo VALIDADO, que es lo que mide
            // que la migracion no podia haberlo declarado asi.
            String clausula =
                    validado && definicion.endsWith(" NOT VALID")
                            ? definicion.substring(0, definicion.length() - " NOT VALID".length())
                            : definicion;
            ejecutarComoDueno(
                    "ALTER TABLE cuenta_corriente_asiento"
                            + " ADD CONSTRAINT asiento_baja_con_causal_ck "
                            + clausula);
        }

        private void validarElCheckDeLaBaja() throws SQLException {
            ejecutarComoDueno(
                    "ALTER TABLE cuenta_corriente_asiento"
                            + " VALIDATE CONSTRAINT asiento_baja_con_causal_ck");
        }
    }

    // ------------------------------------------------------------------
    //  AC 4 — el contraste: la observacion no se convierte en la causal
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 4 — son dos cosas: el sustento y el relato")
    class ElContraste {

        @Test
        @DisplayName("el motivo sigue siendo el texto del usuario, sin la causal dentro")
        void elMotivoSigueSiendoDelUsuario() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "ALCABALA", "300.00");
            darDeBaja(titular, "ALCABALA", "80.00", CausalDeBaja.PRESCRIPCION_DECLARADA, 1);

            assertThat(relacion(codigo, CausalDeBaja.PRESCRIPCION_DECLARADA))
                    .singleElement()
                    .satisfies(
                            asiento -> {
                                assertThat(asiento.motivo())
                                        .as(
                                                "hasta #684 aqui ponia «PRESCRIPCIÓN DECLARADA. "
                                                        + RELATO
                                                        + "»: la causal viajaba dentro del texto"
                                                        + " que escribe quien atiende (regla 10)")
                                        .isEqualTo(RELATO);
                                assertThat(asiento.motivo())
                                        .doesNotContain("PRESCRIPCION")
                                        .doesNotContain("PRESCRIPCIÓN");
                                assertThat(asiento.causal())
                                        .isEqualTo(CausalDeBaja.PRESCRIPCION_DECLARADA);
                            });
        }
    }

    // ------------------------------------------------------------------
    //  El aislamiento lo pone la politica, no la consulta
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El aislamiento")
    class NoSeMezclaConB {

        @Test
        @DisplayName("la baja de B no sale al filtrar en A por la misma causal")
        void laBajaDeBNoSaleEnA() throws SQLException {
            String codigo = nuevoCodigo();
            long deA = crearContribuyente(municipalidadA, codigo, "9191" + codigo.substring(3));
            long deB = crearContribuyente(municipalidadB, codigo, "9292" + codigo.substring(3));

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            darDeAlta(deA, "ESPECTACULOS", "300.00");
            darDeBaja(deA, "ESPECTACULOS", "10.00", CausalDeBaja.COMPENSACION, 1);
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            darDeAlta(deB, "ESPECTACULOS", "300.00");
            darDeBaja(deB, "ESPECTACULOS", "20.00", CausalDeBaja.COMPENSACION, 1);

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            assertThat(relacion(codigo, CausalDeBaja.COMPENSACION))
                    .as("mismo codigo, misma causal en las dos: A ve la suya")
                    .singleElement()
                    .satisfies(
                            asiento -> assertThat(asiento.monto()).isEqualTo(Dinero.de("10.00")));

            // La misma demostracion que exige AislamientoMultiTenantTest: con el mismo
            // contexto fijado, el superusuario ve las dos municipalidades y sgtm_app una.
            try (Connection admin = base.conexionAdmin();
                    PreparedStatement sentencia =
                            admin.prepareStatement(
                                    "SELECT count(DISTINCT municipalidad_id)"
                                            + " FROM cuenta_corriente_asiento"
                                            + " WHERE causal = 'COMPENSACION'")) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    assertThat(fila.getLong(1))
                            .as("las dos municipalidades tienen su baja por compensacion")
                            .isEqualTo(2);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Los actos, por el camino de verdad
    // ------------------------------------------------------------------

    private static List<Asiento> relacion(
            String codigoContribuyente, @Nullable CausalDeBaja causal) {
        Pagina<Asiento> pagina =
                transaccion.execute(
                        estado ->
                                asientos.altasYBajas(
                                        new CriterioDeAltasBajas(
                                                codigoContribuyente, EJERCICIO, null, null, causal),
                                        PAGINA));
        return Objects.requireNonNull(pagina).contenido();
    }

    private static void darDeAlta(long titular, String tributo, String importe) {
        asentar(
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.ALTA,
                        new ClaveDeSaldo(titular, tributo, EJERCICIO, 1, null, null),
                        Dinero.de(importe),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        DIA_DEL_ACTO,
                        "RES-ALTA-" + tributo,
                        null));
    }

    private static void darDeBaja(
            long titular, String tributo, String importe, CausalDeBaja causal, int cuota) {
        asentar(
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.BAJA,
                        new ClaveDeSaldo(titular, tributo, EJERCICIO, cuota, null, null),
                        Dinero.de(importe),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        DIA_DEL_ACTO,
                        "RES-BAJA-" + tributo,
                        null,
                        causal));
    }

    private static void asentar(MovimientoDeDeuda movimiento) {
        for (Asiento asiento : movimiento.enAsientos()) {
            registrar.asentar(asiento, Observacion.de(RELATO));
        }
    }

    /** Una baja escrita por SQL directo, para medir lo que para la BASE y no Java. */
    private static void insertarBaja(long titular, String tributo, @Nullable String causal)
            throws SQLException {
        insertarAsiento(titular, tributo, "ABONO", "BAJA_DEUDA", causal);
    }

    private static void insertarAsiento(
            long titular,
            String tributo,
            String tipo,
            @Nullable String acto,
            @Nullable String causal)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                                    + " contribuyente_id, tributo, concepto, tipo, fase, periodo,"
                                    + " monto, fecha_valor, documento_origen, usuario_id, motivo,"
                                    + " acto, causal)"
                                    + " VALUES (?, 2026, ?, ?, 'INSOLUTO', ?, 'ORDINARIA', 1,"
                                    + " 55.00, DATE '2025-11-03', 'RES-VIEJA', 'prueba',"
                                    + " 'Baja anterior a V77', ?, ?)")) {
                sentencia.setLong(1, municipalidadA);
                sentencia.setLong(2, titular);
                sentencia.setString(3, tributo);
                sentencia.setString(4, tipo);
                sentencia.setString(5, acto);
                sentencia.setString(6, causal);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static void ejecutarComoDueno(String ddl) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                Statement sentencia = owner.createStatement()) {
            sentencia.execute(ddl);
            owner.commit();
        }
    }

    // ------------------------------------------------------------------

    private static String nuevoCodigo() {
        siguienteCodigo++;
        return String.format("CB-%04d", siguienteCodigo);
    }

    private static long nuevoTitular(String codigo) throws SQLException {
        return crearContribuyente(municipalidadA, codigo, "7070" + codigo.substring(3));
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(long municipalidadId, String codigo, String dni)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, CAUSALES',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
