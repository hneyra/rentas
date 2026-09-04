package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.Colindante;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.Orientacion;
import kamayuk.rentas.catastro.dominio.ParticipacionComun;
import kamayuk.rentas.catastro.dominio.Riego;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
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
 * Los otros tres tipos de ficha (RF-002, RF-003, RF-004), contra PostgreSQL real.
 *
 * <p>La afirmacion que estas pruebas defienden es que <b>el mecanismo no cambio</b>: siguen siendo
 * versiones con vigencia, la version anterior sigue entera, y la copia arrastra tambien lo que es
 * propio de cada tipo. Esa ultima parte es la que se olvida —ya paso con las construcciones— y por
 * eso hay una prueba por tipo que la comprueba a la vuelta de la base, no en memoria.
 *
 * <p>Ninguna cifra normativa aparece aqui. Las hectareas y los porcentajes son superficies y
 * reparto, no importes; cuanto vale una hectarea de cultivo bajo riego es D-02a.
 */
@DisplayName("RF-002/003/004 — Fichas economica, de bienes comunes y rural")
class FichasDeTodoTipoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);
    private static final LocalDate CAMBIO = LocalDate.of(2026, 7, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;

    private static TransactionTemplate transaccion;
    private static FichaCatastralRepositoryJdbc repositorio;
    private static ActualizarFichaCatastral fichas;
    private static InscribirFicha inscribir;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250101", "Municipalidad de los cuatro tipos");
        contribuyente = crearContribuyente("CT-0001", "PEREZ GARCIA, JUAN");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new FichaCatastralRepositoryJdbc(jdbc);
        fichas =
                envolver(
                        new ActualizarFichaCatastral(
                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);

        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);
        RegistrarPredio predios =
                envolver(
                        new RegistrarPredio(catastro, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        inscribir =
                envolver(
                        new InscribirFicha(
                                catastro, new SinVias(), new PadronDePrueba(), predios, fichas),
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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Ficha economica (RF-002)")
    class Economica {

        @Test
        @DisplayName("la licencia entra por NUMERO, y catastro no depende de licencias")
        void laLicenciaEntraPorNumero() throws SQLException {
            long predio = crearPredio("25010100100100101010001", "AV. ECONOMICA 100");

            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.ECONOMICA, "150.00", "COMERCIO")
                            .conDetalle(
                                    DetalleEconomico.de(
                                            ActividadEconomica.de("Juan Perez", "4711")
                                                    .conLicencia("LIC-2026-0001", ALTA))),
                    Observacion.de("Alta de la ficha economica tras la inspeccion"));

            Optional<FichaCatastral> leida = fichas.vigenteA(predio, TipoFicha.ECONOMICA, CAMBIO);

            assertThat(leida).isPresent();
            DetalleEconomico detalle = (DetalleEconomico) leida.get().detalle();
            assertThat(detalle).isNotNull();
            assertThat(detalle.actividades()).hasSize(1);
            assertThat(detalle.actividades().get(0).licenciaNumero()).isEqualTo("LIC-2026-0001");

            Long conClaveAjena =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM information_schema"
                                                            + ".referential_constraints r"
                                                            + " JOIN information_schema"
                                                            + ".table_constraints t"
                                                            + "   ON t.constraint_name ="
                                                            + " r.constraint_name"
                                                            + " WHERE t.table_name ="
                                                            + " 'actividad_economica'"
                                                            + "   AND r.unique_constraint_name LIKE"
                                                            + " 'licencia%'")
                                            .query(Long.class)
                                            .single());

            assertThat(conClaveAjena)
                    .as(
                            "una clave ajena a licencia_funcionamiento ataria catastro al contexto"
                                    + " licencias (ARQ-01 §4) y ademas impediria anotar el numero del"
                                    + " cartel de la puerta mientras el otro contexto no lo hubiera"
                                    + " cargado")
                    .isZero();
        }

        @Test
        @DisplayName("un numero de licencia que no existe se admite: es el hallazgo, no un error")
        void unaLicenciaInexistenteSeAdmite() throws SQLException {
            long predio = crearPredio("25010100100100101010002", "AV. ECONOMICA 200");

            FichaCatastral guardada =
                    fichas.registrarPrimera(
                            ficha(predio, TipoFicha.ECONOMICA, "90.00", "COMERCIO")
                                    .conDetalle(
                                            DetalleEconomico.de(
                                                    ActividadEconomica.de("Ana Quispe", "5610")
                                                            .conLicencia("NO-EXISTE-9999", ALTA))),
                            Observacion.de("El local exhibe una licencia que no figura"));

            assertThat(guardada.detalle()).isNotNull();
        }

        @Test
        @DisplayName("una actividad sin licencia se cuenta: es lo que fiscalizacion busca")
        void sinLicenciaSeCuenta() {
            DetalleEconomico detalle =
                    new DetalleEconomico(
                            List.of(
                                    ActividadEconomica.de("Con licencia", "4711")
                                            .conLicencia("LIC-1", ALTA),
                                    ActividadEconomica.de("Sin licencia", "5610")),
                            null);

            assertThat(detalle.sinLicencia()).hasSize(1);
            assertThat(detalle.sinLicencia().get(0).conductor()).isEqualTo("Sin licencia");
        }

        @Test
        @DisplayName("una fecha de licencia sin numero no se construye")
        void fechaSinNumeroNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new ActividadEconomica(
                                            null,
                                            null,
                                            "Conductor",
                                            null,
                                            "4711",
                                            null,
                                            null,
                                            ALTA,
                                            null,
                                            null,
                                            null))
                    .as("una fecha sola no permite comprobar nada contra licencias")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Ficha de bienes comunes (RF-003)")
    class BienesComunes {

        @Test
        @DisplayName("las areas comunes y su reparto sobreviven al versionado")
        void elRepartoSobreviveAlVersionado() throws SQLException {
            long edificio = crearPredio("25010100100100101010101", "AV. EDIFICIO 100");
            long unidad = crearPredio("25010100100100101010102", "AV. EDIFICIO 100 DPTO 302");

            fichas.registrarPrimera(
                    ficha(edificio, TipoFicha.BIENES_COMUNES, "400.00", "MULTIFAMILIAR")
                            .conDenominacion("Residencial Los Algarrobos")
                            .conDetalle(
                                    DetalleDeBienesComunes.de(
                                                    BienComun.de(
                                                            "Escalera comun",
                                                            new AreaM2(new BigDecimal("30.00"))),
                                                    BienComun.de(
                                                            "Azotea",
                                                            new AreaM2(new BigDecimal("70.00"))))
                                            .repartidoEntre(
                                                    List.of(
                                                            ParticipacionComun.de(
                                                                    unidad,
                                                                    new Porcentaje(
                                                                            new BigDecimal(
                                                                                    "60.0000")))))),
                    Observacion.de("Alta de la ficha de bienes comunes de la edificacion"));

            fichas.actualizar(
                    edificio,
                    TipoFicha.BIENES_COMUNES,
                    CAMBIO,
                    kamayuk.rentas.catastro.dominio.OrigenDeLaFicha.FISCALIZACION,
                    "Acta de fiscalizacion 090-2026",
                    null,
                    null,
                    null,
                    Observacion.de("Se rectifica el uso declarado sin tocar las areas comunes"));

            Optional<FichaCatastral> segunda =
                    fichas.vigenteA(edificio, TipoFicha.BIENES_COMUNES, CAMBIO);

            assertThat(segunda).isPresent();
            assertThat(segunda.get().version()).isEqualTo(2);

            DetalleDeBienesComunes detalle = (DetalleDeBienesComunes) segunda.get().detalle();
            assertThat(detalle).isNotNull();
            assertThat(detalle.bienes())
                    .as(
                            "versionar copia tambien lo comun; si no, la version 2 naceria sin"
                                    + " areas y el edificio dejaria de repartir nada sin que ningun"
                                    + " DELETE apareciera en el diff")
                    .hasSize(2);
            assertThat(detalle.participaciones()).hasSize(1);
            assertThat(detalle.areaComunTotal()).isEqualTo(new AreaM2(new BigDecimal("100.00")));

            assertThat(segunda.get().denominacion()).isEqualTo("Residencial Los Algarrobos");
        }

        @Test
        @DisplayName("las participaciones de una ficha no pasan de 100")
        void lasParticipacionesNoPasanDeCien() throws SQLException {
            long edificio = crearPredio("25010100100100101010111", "AV. EDIFICIO 200");
            long uno = crearPredio("25010100100100101010112", "AV. EDIFICIO 200 DPTO 101");
            long otro = crearPredio("25010100100100101010113", "AV. EDIFICIO 200 DPTO 102");

            DetalleDeBienesComunes reparto =
                    DetalleDeBienesComunes.de(
                                    BienComun.de("Patio", new AreaM2(new BigDecimal("20.00"))))
                            .repartidoEntre(
                                    List.of(
                                            ParticipacionComun.de(
                                                    uno, new Porcentaje(new BigDecimal("70.0000"))),
                                            ParticipacionComun.de(
                                                    otro,
                                                    new Porcentaje(new BigDecimal("50.0000")))));

            FichaCatastral inconsistente =
                    ficha(edificio, TipoFicha.BIENES_COMUNES, "300.00", "MULTIFAMILIAR")
                            .conDetalle(reparto);

            assertThatThrownBy(
                            () ->
                                    fichas.registrarPrimera(
                                            inconsistente, Observacion.de("Reparto que suma 120")))
                    .as(
                            "si suman 120, el valor de lo comun se reparte por mas de lo que hay y"
                                    + " todas las unidades del edificio pagan de mas")
                    // La causa raiz, y no el mensaje de arriba, porque el disparador es
                    // diferido: salta en el COMMIT, y ahi Spring ya lo envolvio en un «JDBC
                    // commit failed» que no dice nada. Quien presente este error al usuario
                    // tiene que bajar hasta aqui; darlo por bueno con el mensaje de la
                    // envoltura seria dejar pasar cualquier fallo del commit como si fuera
                    // este.
                    .rootCause()
                    .hasMessageContaining("120");
        }
    }

    @Nested
    @DisplayName("Ficha rural (RF-004)")
    class Rural {

        @Test
        @DisplayName("un predio rustico SIN construccion se ficha igual")
        void sinConstruccionSeFichaIgual() throws SQLException {
            long predio = crearPredio("25010100100100101010201", "FUNDO SIN CASA");

            FichaCatastral guardada =
                    fichas.registrarPrimera(
                            ficha(predio, TipoFicha.RURAL, "25000.00", "AGRICOLA")
                                    .conDenominacion("Fundo La Esperanza")
                                    .conDetalle(
                                            DetalleRural.de(
                                                            TierraRural.de(
                                                                    "CULTIVO_TRANSITORIO",
                                                                    Riego.BAJO_RIEGO,
                                                                    "1.5000"),
                                                            TierraRural.de(
                                                                    "PASTOS_NATURALES",
                                                                    Riego.SECANO,
                                                                    "1.0000"))
                                                    .con(
                                                            List.of(
                                                                    Colindante.por(
                                                                            Orientacion.NORTE,
                                                                            "Fundo San Juan"),
                                                                    Colindante.por(
                                                                            Orientacion.SUR,
                                                                            "Canal de regadio")))),
                            Observacion.de("Alta de la ficha rural del fundo"));

            assertThat(guardada.construcciones())
                    .as(
                            "un predio rustico sin ninguna edificacion es lo normal; exigir una"
                                    + " obligaria al tecnico a inventarse un dato o a no fichar el"
                                    + " predio")
                    .isEmpty();

            DetalleRural detalle = (DetalleRural) guardada.detalle();
            assertThat(detalle).isNotNull();
            assertThat(detalle.tierras()).hasSize(2);
            assertThat(detalle.colindantes()).hasSize(2);
            assertThat(detalle.hectareasTotales().toString()).isEqualTo("2.5000 HA");
        }

        @Test
        @DisplayName("la superficie va en HECTAREAS: guardarla en metros no compila el dato")
        void laSuperficieVaEnHectareas() {
            assertThatThrownBy(
                            () ->
                                    new TierraRural(
                                            null,
                                            null,
                                            "CULTIVO_TRANSITORIO",
                                            null,
                                            Riego.SECANO,
                                            kamayuk.rentas.dominio.Medida.enMetrosCuadrados(
                                                    "15000"),
                                            null))
                    .as(
                            "el arancel rural se publica por hectarea; 15000 leido como hectareas"
                                    + " valoriza diez mil veces de mas")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HA");
        }

        @Test
        @DisplayName("dos colindantes por la misma orientacion se rechazan")
        void dosColindantesPorLaMismaOrientacion() {
            assertThatThrownBy(
                            () ->
                                    new DetalleRural(
                                            List.of(),
                                            List.of(
                                                    Colindante.por(Orientacion.NORTE, "Uno"),
                                                    Colindante.por(Orientacion.NORTE, "Otro"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NORTE");
        }
    }

    @Nested
    @DisplayName("Los cuatro tipos a la vez")
    class LosCuatroTipos {

        @Test
        @DisplayName("un predio puede tener las cuatro fichas vigentes al mismo tiempo")
        void lasCuatroConviven() throws SQLException {
            long predio = crearPredio("25010100100100101010301", "AV. CUATRO TIPOS 100");

            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.UNICA, "200.00", "CASA HABITACION"),
                    Observacion.de("Alta de la ficha urbana"));
            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.ECONOMICA, "200.00", "COMERCIO")
                            .conDetalle(
                                    DetalleEconomico.de(ActividadEconomica.de("Bodega", "4711"))),
                    Observacion.de("Alta de la ficha economica"));
            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.BIENES_COMUNES, "200.00", "MULTIFAMILIAR")
                            .conDetalle(
                                    DetalleDeBienesComunes.de(
                                            BienComun.de(
                                                    "Pasaje",
                                                    new AreaM2(new BigDecimal("10.00"))))),
                    Observacion.de("Alta de la ficha de bienes comunes"));
            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.RURAL, "200.00", "AGRICOLA")
                            .conDetalle(
                                    DetalleRural.de(
                                            TierraRural.de("ERIAZA", Riego.SECANO, "0.5000"))),
                    Observacion.de("Alta de la ficha rural"));

            for (TipoFicha tipo : TipoFicha.values()) {
                assertThat(fichas.vigenteA(predio, tipo, CAMBIO))
                        .as(
                                "ficha_vigente_uq es unico por predio Y tipo: las cuatro conviven,"
                                        + " dos del mismo no")
                        .isPresent();
            }
        }

        @Test
        @DisplayName("dos fichas del mismo tipo y predio no conviven")
        void dosDelMismoTipoNoConviven() throws SQLException {
            long predio = crearPredio("25010100100100101010311", "AV. CUATRO TIPOS 200");

            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.RURAL, "500.00", "AGRICOLA")
                            .conDetalle(
                                    DetalleRural.de(
                                            TierraRural.de("ERIAZA", Riego.SECANO, "0.2000"))),
                    Observacion.de("Alta de la ficha rural"));

            assertThatThrownBy(
                            () ->
                                    fichas.registrarPrimera(
                                            ficha(predio, TipoFicha.RURAL, "600.00", "AGRICOLA"),
                                            Observacion.de("Segunda primera version")))
                    .isInstanceOf(ActualizarFichaCatastral.YaTieneFicha.class);
        }

        @Test
        @DisplayName("una ficha ECONOMICA no acepta detalle rural")
        void elDetalleTieneQueSerDelTipo() {
            assertThatThrownBy(
                            () ->
                                    ficha(1L, TipoFicha.ECONOMICA, "100.00", "COMERCIO")
                                            .conDetalle(
                                                    DetalleRural.de(
                                                            TierraRural.de(
                                                                    "ERIAZA",
                                                                    Riego.SECANO,
                                                                    "1.0000"))))
                    .as(
                            "sin esta comprobacion la combinacion equivocada se escribe sin ruido y"
                                    + " se descubre al leerla, cuando ya nadie recuerda quien la"
                                    + " escribio")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RURAL");
        }
    }

    @Nested
    @DisplayName("Reconstruir el pasado, en los tres tipos")
    class ReconstruirElPasado {

        @Test
        @DisplayName("cada tipo devuelve la version que regia EN esa fecha, no la ultima")
        void cadaTipoDevuelveLaDeSuFecha() throws SQLException {
            long predio = crearPredio("25010100100100101010401", "AV. PASADO 100");

            registrarYVersionar(predio, TipoFicha.ECONOMICA, "COMERCIO");
            registrarYVersionar(predio, TipoFicha.BIENES_COMUNES, "MULTIFAMILIAR");
            registrarYVersionar(predio, TipoFicha.RURAL, "AGRICOLA");

            for (TipoFicha tipo :
                    List.of(TipoFicha.ECONOMICA, TipoFicha.BIENES_COMUNES, TipoFicha.RURAL)) {

                Optional<FichaCatastral> enMarzo =
                        fichas.vigenteA(predio, tipo, LocalDate.of(2026, 3, 15));
                Optional<FichaCatastral> hoy = fichas.vigenteA(predio, tipo, CAMBIO);

                assertThat(enMarzo).isPresent();
                assertThat(enMarzo.get().version())
                        .as(
                                "una notificacion de marzo se defiende con la ficha de marzo; «la"
                                        + " ultima» seria la de julio y la determinacion no se podria"
                                        + " reproducir (%s)",
                                tipo)
                        .isEqualTo(1);
                assertThat(hoy).isPresent();
                assertThat(hoy.get().version()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("la version cerrada conserva SU detalle, no el de la siguiente")
        void laCerradaConservaSuDetalle() throws SQLException {
            long predio = crearPredio("25010100100100101010411", "AV. PASADO 200");

            fichas.registrarPrimera(
                    ficha(predio, TipoFicha.RURAL, "10000.00", "AGRICOLA")
                            .conDetalle(
                                    DetalleRural.de(
                                            TierraRural.de(
                                                    "CULTIVO_TRANSITORIO",
                                                    Riego.SECANO,
                                                    "1.0000"))),
                    Observacion.de("Alta con una hectarea de secano"));

            fichas.actualizar(
                    predio,
                    TipoFicha.RURAL,
                    CAMBIO,
                    kamayuk.rentas.catastro.dominio.OrigenDeLaFicha.DECLARACION_JURADA,
                    "Declaracion jurada 200-2026",
                    null,
                    null,
                    DetalleRural.de(
                            TierraRural.de("CULTIVO_TRANSITORIO", Riego.BAJO_RIEGO, "3.0000")),
                    Observacion.de("Se instala riego y se amplia la superficie declarada"));

            List<FichaCatastral> historial = fichas.historial(predio, TipoFicha.RURAL);

            assertThat(historial).hasSize(2);
            DetalleRural nueva = (DetalleRural) historial.get(0).detalle();
            DetalleRural vieja = (DetalleRural) historial.get(1).detalle();
            assertThat(nueva).isNotNull();
            assertThat(vieja).isNotNull();

            assertThat(nueva.hectareasTotales().toString()).isEqualTo("3.0000 HA");
            assertThat(vieja.hectareasTotales().toString())
                    .as(
                            "la version de enero se calculo con una hectarea de secano; si al"
                                    + " versionar se le hubiera cambiado el detalle, la determinacion"
                                    + " de ese ejercicio dejaria de reproducirse")
                    .isEqualTo("1.0000 HA");
            assertThat(vieja.tierras().get(0).riego()).isEqualTo(Riego.SECANO);
        }
    }

    @Nested
    @DisplayName("La inscripcion: el predio nace con su primera ficha (#290)")
    class Inscripcion {

        @Test
        @DisplayName("predio, ficha y titularidad quedan escritos, con la MISMA observacion")
        void elActoEnteroQuedaEscrito() throws SQLException {
            FichaCatastral ficha =
                    inscribir.inscribir(
                            predioNuevo("25010100100100101010501", "AV. INSCRITA 100"),
                            fichaUrbana(),
                            new InscribirFicha.DatosDelTitular(
                                    "CT-0001",
                                    CondicionDeTitularidad.PROPIETARIO_UNICO,
                                    null,
                                    "Partida registral 11223344"),
                            Observacion.de("Inscripcion del predio por levantamiento catastral"));

            assertThat(ficha.id()).isNotNull();
            assertThat(ficha.version()).isEqualTo(1);

            long predioId = ficha.predioId();
            assertThat(contarEn("predio", "id = " + predioId)).isEqualTo(1);
            assertThat(contarEn("titularidad", "predio_id = " + predioId)).isEqualTo(1);

            // Por ->>'campo' y no por subcadena: jsonb renormaliza lo que se le escribe y una
            // comparacion por texto se rompe por donde no es.
            assertThat(
                            unaColumna(
                                    "SELECT datos_nuevos->>'codigo' FROM auditoria"
                                            + " WHERE tabla = 'predio' AND clave = '"
                                            + predioId
                                            + "'"))
                    .isEqualTo("25010100100100101010501");
            assertThat(
                            unaColumna(
                                    "SELECT datos_nuevos->>'contribuyenteId' FROM auditoria"
                                            + " WHERE tabla = 'titularidad' AND observacion LIKE"
                                            + " 'Inscripcion del predio%'"))
                    .isEqualTo(String.valueOf(contribuyente));

            assertThat(
                            contarEn(
                                    "auditoria",
                                    "observacion = 'Inscripcion del predio por levantamiento"
                                            + " catastral'"))
                    .as("es un acto, no tres: las tres filas llevan la observacion del usuario")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("si el titular no existe, NO queda ni el predio ni la ficha ni la auditoria")
        void siElTitularNoExisteNoQuedaNada() throws SQLException {
            assertThatThrownBy(
                            () ->
                                    inscribir.inscribir(
                                            predioNuevo(
                                                    "25010100100100101010511", "AV. DESHECHA 200"),
                                            fichaUrbana(),
                                            new InscribirFicha.DatosDelTitular(
                                                    "NO-EXISTE",
                                                    CondicionDeTitularidad.PROPIETARIO_UNICO,
                                                    null,
                                                    "Partida registral 99999999"),
                                            Observacion.de("Inscripcion con un titular que no")))
                    .isInstanceOf(InscribirFicha.ReferenciaInexistente.class);

            assertThat(contarEn("predio", "codigo_ref_catastral = '25010100100100101010511'"))
                    .as(
                            "el predio se escribio antes que la titularidad; si la transaccion no"
                                    + " fuera una sola, ese predio se quedaria en el padron sin"
                                    + " ficha y sin que ninguna pantalla lo muestre")
                    .isZero();
            assertThat(contarEn("auditoria", "observacion = 'Inscripcion con un titular que no'"))
                    .as("no queda constancia de algo que no paso")
                    .isZero();
        }

        @Test
        @DisplayName("el titular es opcional: el predio se ficha antes de saber de quien es")
        void elTitularEsOpcional() throws SQLException {
            FichaCatastral ficha =
                    inscribir.inscribir(
                            predioNuevo("25010100100100101010521", "AV. SIN TITULAR 300"),
                            fichaUrbana(),
                            null,
                            Observacion.de("Levantamiento sin titular identificado todavia"));

            assertThat(contarEn("titularidad", "predio_id = " + ficha.predioId())).isZero();
            assertThat(contarEn("predio", "id = " + ficha.predioId())).isEqualTo(1);
        }

        @Test
        @DisplayName("sobre un predio que ya existe no se crea otro: se abre la otra ficha")
        void sobreUnPredioExistenteSeAbreLaOtraFicha() throws SQLException {
            FichaCatastral urbana =
                    inscribir.inscribir(
                            predioNuevo("25010100100100101010531", "AV. DOS FICHAS 400"),
                            fichaUrbana(),
                            null,
                            Observacion.de("Alta de la ficha urbana del predio"));

            FichaCatastral economica =
                    inscribir.inscribir(
                            predioNuevo("25010100100100101010531", "AV. DOS FICHAS 400"),
                            new InscribirFicha.DatosDeLaFicha(
                                    TipoFicha.ECONOMICA,
                                    new AreaM2(new BigDecimal("200.00")),
                                    "COMERCIO",
                                    null,
                                    ALTA,
                                    kamayuk.rentas.catastro.dominio.OrigenDeLaFicha
                                            .DECLARACION_JURADA,
                                    "DJ 531-2026",
                                    List.of(),
                                    List.of(),
                                    DetalleEconomico.de(
                                            ActividadEconomica.de("Bodega el Sol", "4711"))),
                            null,
                            Observacion.de("El mismo predio abre su ficha economica"));

            assertThat(economica.predioId())
                    .as("un predio tiene una ficha de cada tipo, no un predio por ficha")
                    .isEqualTo(urbana.predioId());
            assertThat(contarEn("predio", "codigo_ref_catastral = '25010100100100101010531'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("una segunda ficha del mismo tipo se rechaza: lo que toca es actualizarla")
        void unaSegundaDelMismoTipoSeRechaza() {
            inscribir.inscribir(
                    predioNuevo("25010100100100101010541", "AV. REPETIDA 500"),
                    fichaUrbana(),
                    null,
                    Observacion.de("Alta de la ficha urbana del predio"));

            assertThatThrownBy(
                            () ->
                                    inscribir.inscribir(
                                            predioNuevo(
                                                    "25010100100100101010541", "AV. REPETIDA 500"),
                                            fichaUrbana(),
                                            null,
                                            Observacion.de("Segunda primera version")))
                    .isInstanceOf(ActualizarFichaCatastral.YaTieneFicha.class);
        }

        @Test
        @DisplayName("el sector y la manzana entran por codigo y se resuelven dentro del acto")
        void laUbicacionSeResuelvePorCodigo() throws SQLException {
            long sector = crearSector("SC-500", "Sector de la inscripcion");
            long manzana = crearManzana(sector, "001");

            FichaCatastral ficha =
                    inscribir.inscribir(
                            new InscribirFicha.DatosDelPredio(
                                    CodigoReferenciaCatastral.de("25010100100100101010551"),
                                    kamayuk.rentas.catastro.dominio.TipoPredio.URBANO,
                                    "AV. UBICADA 600",
                                    null,
                                    null,
                                    "SC-500",
                                    "001",
                                    "12",
                                    null),
                            fichaUrbana(),
                            null,
                            Observacion.de("Alta con la ubicacion territorial completa"));

            assertThat(
                            unaColumna(
                                    "SELECT sector_id || '/' || manzana_id FROM predio"
                                            + " WHERE id = "
                                            + ficha.predioId()))
                    .isEqualTo(sector + "/" + manzana);
        }

        @Test
        @DisplayName("un sector que no existe deshace el acto entero")
        void unSectorInexistenteDeshaceElActo() throws SQLException {
            assertThatThrownBy(
                            () ->
                                    inscribir.inscribir(
                                            new InscribirFicha.DatosDelPredio(
                                                    CodigoReferenciaCatastral.de(
                                                            "25010100100100101010561"),
                                                    kamayuk.rentas.catastro.dominio.TipoPredio
                                                            .URBANO,
                                                    "AV. SIN SECTOR 700",
                                                    null,
                                                    null,
                                                    "SC-NADA",
                                                    null,
                                                    null,
                                                    null),
                                            fichaUrbana(),
                                            null,
                                            Observacion.de("Alta con un sector inexistente")))
                    .isInstanceOf(InscribirFicha.ReferenciaInexistente.class);

            assertThat(contarEn("predio", "codigo_ref_catastral = '25010100100100101010561'"))
                    .isZero();
        }

        private InscribirFicha.DatosDelPredio predioNuevo(String codigo, String direccion) {
            return new InscribirFicha.DatosDelPredio(
                    CodigoReferenciaCatastral.de(codigo),
                    kamayuk.rentas.catastro.dominio.TipoPredio.URBANO,
                    direccion,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        private InscribirFicha.DatosDeLaFicha fichaUrbana() {
            return new InscribirFicha.DatosDeLaFicha(
                    TipoFicha.UNICA,
                    new AreaM2(new BigDecimal("200.00")),
                    "CASA HABITACION",
                    null,
                    ALTA,
                    kamayuk.rentas.catastro.dominio.OrigenDeLaFicha.DECLARACION_JURADA,
                    "DJ 500-2026",
                    List.of(),
                    List.of(),
                    null);
        }
    }

    // ------------------------------------------------------------------

    /** El catalogo vial no participa en estas pruebas: ningun predio declara su via. */
    private static final class SinVias implements ViaRepository {

        @Override
        public Optional<Via> findByCodigo(String codigo) {
            return Optional.empty();
        }

        @Override
        public Optional<Via> findById(long id) {
            throw new UnsupportedOperationException("La inscripcion resuelve la via por codigo");
        }

        @Override
        public Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("La inscripcion no lista vias");
        }

        @Override
        public Via save(Via via) {
            throw new UnsupportedOperationException("La inscripcion no crea vias");
        }
    }

    /**
     * El padron, por su API publica.
     *
     * <p>Es un doble y no {@code DirectorioJdbc} porque lo que estas pruebas verifican es la
     * <b>atomicidad del acto</b>, no como se lee el padron; lo que si es real es el identificador
     * que devuelve, que es el de una fila de {@code contribuyente} sembrada en esta base. Con uno
     * inventado la clave ajena de {@code titularidad} lo rechazaria, y el rojo no diria nada del
     * caso que se prueba.
     */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return "CT-0001".equals(codigo)
                    ? Optional.of(
                            new ResumenDeContribuyente(
                                    contribuyente, "CT-0001", "PEREZ GARCIA, JUAN", "DNI 12345678"))
                    : Optional.empty();
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La inscripcion no busca en el padron");
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            throw new UnsupportedOperationException("La inscripcion no resuelve nombres");
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("La inscripcion no lee domicilios");
        }
    }

    private static long contarEn(String tabla, String condicion) throws SQLException {
        String valor = unaColumna("SELECT count(*) FROM " + tabla + " WHERE " + condicion);
        return valor == null ? 0L : Long.parseLong(valor);
    }

    /** Se consulta como administrador: lo que se comprueba es que la fila este o no este. */
    private static @Nullable String unaColumna(String consulta) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(consulta);
                ResultSet fila = sentencia.executeQuery()) {
            return fila.next() ? fila.getString(1) : null;
        }
    }

    private static long crearSector(String codigo, String nombre) throws SQLException {
        return insertar(
                "INSERT INTO sector (municipalidad_id, codigo, nombre) VALUES (?, ?, ?)"
                        + " RETURNING id",
                codigo,
                nombre);
    }

    private static long crearManzana(long sectorId, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO manzana (municipalidad_id, sector_id, codigo)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, sectorId);
                sentencia.setString(3, codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearContribuyente(String codigo, String nombre) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', '12345678', 'NATURAL', ?,"
                                    + " 'catastro.tecnico') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long insertar(String sql, String primero, String segundo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, primero);
                sentencia.setString(3, segundo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private void registrarYVersionar(long predio, TipoFicha tipo, String uso) {
        fichas.registrarPrimera(
                ficha(predio, tipo, "300.00", uso).conDetalle(detalleDe(tipo)),
                Observacion.de("Alta de la ficha " + tipo));

        fichas.actualizar(
                predio,
                tipo,
                CAMBIO,
                kamayuk.rentas.catastro.dominio.OrigenDeLaFicha.FISCALIZACION,
                "Acta de fiscalizacion 300-2026",
                null,
                null,
                null,
                Observacion.de("Se rectifica lo declarado en la inspeccion de julio"));
    }

    private static kamayuk.rentas.catastro.dominio.DetalleDeLaFicha detalleDe(TipoFicha tipo) {
        return switch (tipo) {
            case ECONOMICA ->
                    DetalleEconomico.de(ActividadEconomica.de("Conductor de prueba", "4711"));
            case BIENES_COMUNES ->
                    DetalleDeBienesComunes.de(
                            BienComun.de("Escalera", new AreaM2(new BigDecimal("12.00"))));
            case RURAL -> DetalleRural.de(TierraRural.de("ERIAZA", Riego.SECANO, "0.7500"));
            case UNICA ->
                    throw new IllegalArgumentException("La ficha unica no tiene detalle propio");
        };
    }

    private static FichaCatastral ficha(long predioId, TipoFicha tipo, String area, String uso) {
        return FichaCatastral.primera(
                predioId,
                tipo,
                new AreaM2(new BigDecimal(area)),
                uso,
                ALTA,
                kamayuk.rentas.catastro.dominio.OrigenDeLaFicha.DECLARACION_JURADA,
                "Declaracion jurada 100-2026",
                Observacion.de("Version inicial de la ficha del predio"));
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

    private static long crearPredio(String codigo, String direccion) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, direccion);
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
