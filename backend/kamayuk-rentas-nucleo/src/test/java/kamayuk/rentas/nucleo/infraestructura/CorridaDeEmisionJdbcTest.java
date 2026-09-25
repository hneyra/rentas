package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El rastro de la corrida de emision predial, contra PostgreSQL de verdad (#523).
 *
 * <p>Conectado como {@code kamayuk_app}, que es lo unico que hace que estas pruebas verifiquen
 * algo: un superusuario <b>omite RLS incluso con FORCE ROW LEVEL SECURITY</b>, y la mitad de este
 * archivo pasaria en verde sin comprobar nada (DAT-01 §0).
 *
 * <p>Lo que se mide aqui es lo que ninguna prueba de capa web puede decir: que las corridas de una
 * municipalidad no se ven desde otra, y que <b>no se pueden corregir</b> — una corrida es un hecho,
 * y la inmutabilidad la sostiene el privilegio, no que nadie escriba el verbo.
 */
@DisplayName("#523 — La corrida de emision predial deja rastro")
class CorridaDeEmisionJdbcTest {

    /** El conjunto con que la corrida de prueba emitio, sellado en la fila (V23, #312). */
    private static final long CONJUNTO_SELLADO = 31L;

    /** El derecho de emision que esa corrida aplico a cada cuenta, en soles. */
    private static final String DERECHO_SELLADO = "4.50";

    /**
     * Una «SUCESION INDIVISA …» de 215 caracteres: los herederos van en el nombre, y el padron
     * admite hasta 240 ({@code nombre_razon_social}).
     */
    private static final String SUCESION_DE_215 =
            "SUCESION INDIVISA DE MEDINA MEDINA, RUFINA DEL CARMEN Y" + " HEREDEROS".repeat(16);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-01-28T07:14:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static CorridaDeEmisionRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("260101", "Municipalidad de corridas A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad de corridas B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new CorridaDeEmisionRepositoryJdbc(JdbcClient.create(pool), RELOJ);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("la corrida se guarda con sus observados, y se relee entera")
    void seGuardaYSeRelee() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2026, 3, 2, "9412204.60", observadosDePrueba()),
                                        Observacion.de("Emision anual 2026")));

        assertThat(guardada).isNotNull();
        assertThat(guardada.id()).isNotNull();

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2026)));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().determinados()).isEqualTo(2);
        assertThat(ultima.get().leidos()).isEqualTo(3);
        assertThat(ultima.get().montoEmitido()).isEqualTo(Dinero.de("9412204.60"));
        assertThat(ultima.get().fechaCalculo()).isEqualTo(LocalDate.of(2026, 1, 28));
        // **El sello de V23 (#312).** La corrida aplicaba el derecho dentro de `monto_emitido` y
        // no lo guardaba; ahora vuelve con el, y con el conjunto del que salio — que es lo unico
        // con lo que se puede volver a leer aquel cuadro sin mirar el conjunto vigente HOY.
        assertThat(ultima.get().derechoDeEmision()).isEqualTo(Dinero.de(DERECHO_SELLADO));
        assertThat(ultima.get().conjuntoId()).isEqualTo(CONJUNTO_SELLADO);
    }

    /**
     * <b>La cabecera se lee sin ellos, y eso es deliberado.</b> Son cientos, y una portada que los
     * trajera siempre seria la peticion mas pesada del sistema para una cifra que casi nadie abre.
     */
    @Test
    @DisplayName("los observados se piden aparte, con su motivo")
    void losObservadosSePidenAparte() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2025, 3, 2, "100.00", observadosDePrueba()),
                                        Observacion.de("Emision anual 2025")));

        Optional<CorridaDeEmision> cabecera =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2025)));
        assertThat(cabecera).isPresent();
        assertThat(cabecera.get().observados())
                .as("la cabecera no los trae: se piden aparte y paginados")
                .isEmpty();

        Pagina<CorridaDeEmision.Observado> observados =
                transaccion.execute(
                        estado ->
                                repositorio.observadosDe(
                                        requireId(guardada),
                                        new Paginacion(
                                                0, 20, "id", Paginacion.Sentido.ASCENDENTE)));

        assertThat(observados.contenido()).hasSize(1);
        assertThat(observados.contenido().get(0).codContribuyente()).isEqualTo("C-000042");
        assertThat(observados.contenido().get(0).motivo())
                .as("un observado sin motivo no se puede arreglar, que es para lo que existe")
                .contains("sin arancel");
    }

    /**
     * <b>Una simulacion tambien deja rastro, y se distingue.</b> Esconderla haria que «ver los
     * observados antes de emitir» no dejara nada que mirar despues.
     */
    @Test
    @DisplayName("la ultima puede ser una simulacion, y la fila lo dice")
    void laUltimaPuedeSerUnaSimulacion() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                corridaDe(2024, 5, 5, "500.00", List.of()),
                                Observacion.de("Emision 2024")));
        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                new CorridaDeEmision(
                                        null,
                                        new Ejercicio(2024),
                                        "TODOS",
                                        null,
                                        null,
                                        null,
                                        "TRIMESTRAL",
                                        true,
                                        "",
                                        // Una simulacion que no resolvio ningun conjunto: no hay
                                        // nada que sellar, y los dos van nulos a la vez (V23).
                                        null,
                                        null,
                                        5,
                                        5,
                                        Dinero.de("500.00"),
                                        LocalDate.of(2026, 1, 28),
                                        List.of()),
                                Observacion.de("Simulacion antes de reemitir")));

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2024)));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().simulacion())
                .as("la ultima es la simulacion, y la lectura no la esconde ni la disfraza")
                .isTrue();
    }

    @Test
    @DisplayName("sin corridas del ejercicio no hay cabecera de ceros: no hay nada")
    void sinCorridasNoHayCabeceraDeCeros() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        Optional<CorridaDeEmision> ninguna =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2019)));

        assertThat(ninguna)
                .as("«todavia no se ha corrido» y «se corrio y no emitio nada» son dos cosas")
                .isEmpty();
    }

    @Test
    @DisplayName("las corridas de una municipalidad no se ven desde otra")
    void noSeVenDesdeOtraMunicipalidad() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                corridaDe(2023, 9, 9, "999.00", List.of()),
                                Observacion.de("Emision 2023 de A")));

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        Optional<CorridaDeEmision> desdeB =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2023)));

        assertThat(desdeB)
                .as(
                        "la corrida de A no existe para B: RLS con FORCE, y conectados como kamayuk_app")
                .isEmpty();
    }

    /**
     * <b>Una corrida escrita ANTES de {@code V23} se relee sin sello, y sin inventarle uno</b>
     * (#312).
     *
     * <h2>Por que esta fila se escribe con SQL crudo y no con el repositorio</h2>
     *
     * <p>Porque el repositorio de hoy ya no puede producirla: el {@code INSERT} nombra las dos
     * columnas nuevas. Lo que hay en una base en marcha son filas escritas por el codigo de ayer,
     * que no las nombraba, y esas son las que esta lectura tiene que saber leer. Insertarla a mano
     * es la unica forma de tener una.
     *
     * <h2>Y por que importa que NO vuelva con ceros</h2>
     *
     * <p>{@code ResultSet.getLong} devuelve {@code 0} para un {@code NULL} de SQL <b>sin
     * avisar</b>: con el, esta corrida saldria sellada con «el conjunto numero cero», que no
     * existe. Y un derecho de cero diria «no se cobro derecho de emision», que es falso — se cobro,
     * y esta sumado dentro de {@code monto_emitido}. Las dos son cifras equivocadas que parecen
     * correctas, que es justo lo que #312 viene a quitar de en medio.
     */
    @Test
    @DisplayName("una corrida anterior a V23 se relee con el sello en nulo, no en cero")
    void laCorridaAnteriorALaMigracionSeReleeSinSello() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        // La fila tal como la escribia el codigo anterior a #312: sin `conjunto_id` ni
        // `derecho_emision`. El ejercicio es suyo y de nadie mas, para que sea «la ultima».
        ejecutarComoApp(
                "INSERT INTO corrida_predial (municipalidad_id, ejercicio, alcance, modalidad,"
                        + " simulacion, conjunto, leidos, determinados, monto_emitido,"
                        + " fecha_calculo, usuario_registro, fecha_registro, observacion)"
                        + " VALUES ("
                        + municipalidadA
                        + ", 2021, 'TODOS', 'TRIMESTRAL', false, '2021 v1', 3, 3, 3000.00,"
                        + " DATE '2021-01-28', 'jefe.rentas', now(), 'Emision anual 2021')");

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2021)));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().conjuntoId())
                .as(
                        "`getLong` devolveria 0 y la corrida saldria sellada con un conjunto que no existe")
                .isNull();
        assertThat(ultima.get().derechoDeEmision())
                .as("un cero diria «no se cobro derecho», y se cobro: esta dentro de monto_emitido")
                .isNull();
        // Y lo demas de la fila se lee igual que siempre: lo que falta es el sello, no la corrida.
        assertThat(ultima.get().determinados()).isEqualTo(3);
        assertThat(ultima.get().montoEmitido()).isEqualTo(Dinero.de("3000.00"));
    }

    /**
     * <b>El `CHECK` de {@code V23} no deja media fila sellada</b> (#312).
     *
     * <p>El dominio lo exige al construir, pero el dominio no es lo unico que escribe en esta
     * tabla: una carga, un arreglo a mano o un codigo futuro entran por SQL. La cifra sin su
     * conjunto vuelve a ser un numero sin fuente —que es lo que la columna existe para dejar de
     * ser—, asi que la base lo rechaza tambien.
     */
    @Test
    @DisplayName("media fila sellada no entra: el derecho sin su conjunto lo rechaza la base")
    void mediaFilaSelladaNoEntra() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        assertThatThrownBy(
                        () ->
                                ejecutarComoApp(
                                        "INSERT INTO corrida_predial (municipalidad_id, ejercicio,"
                                                + " alcance, modalidad, simulacion, conjunto,"
                                                + " derecho_emision, leidos, determinados,"
                                                + " monto_emitido, fecha_calculo, usuario_registro,"
                                                + " fecha_registro, observacion) VALUES ("
                                                + municipalidadA
                                                + ", 2020, 'TODOS', 'TRIMESTRAL', false, '2020"
                                                + " v1', 4.50, 1, 1, 100.00, DATE '2020-01-28',"
                                                + " 'jefe.rentas', now(), 'media fila')"))
                .as("una cifra sellada sin el conjunto del que salio no se puede contrastar")
                .hasMessageContaining("corrida_predial_sello_completo_ck");
    }

    /**
     * <b>La inmutabilidad la sostiene el privilegio, no el codigo.</b> {@code V62} no le concede a
     * {@code kamayuk_app} ni {@code UPDATE} ni {@code DELETE}, asi que no hace falta confiar en que
     * nadie escriba el verbo: escribirlo falla.
     */
    @Test
    @DisplayName("una corrida no se corrige ni se borra: kamayuk_app no puede")
    void unaCorridaNoSeCorrigeNiSeBorra() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2022, 1, 1, "1.00", List.of()),
                                        Observacion.de("Emision 2022")));
        long id = requireId(guardada);

        assertThatThrownBy(() -> ejecutarComoApp("UPDATE corrida_predial SET determinados = 999"))
                .as("corregir lo que emitio se hace corriendo otra, que deja su propia fila")
                .hasMessageContaining("permission denied");

        assertThatThrownBy(() -> ejecutarComoApp("DELETE FROM corrida_predial WHERE id = " + id))
                .as("y no se borra: sin DELETE en nada que sea un hecho (regla 4)")
                .hasMessageContaining("permission denied");

        assertThatThrownBy(
                        () -> ejecutarComoApp("UPDATE corrida_predial_observado SET motivo = 'x'"))
                .as("el motivo de un observado tampoco se reescribe")
                .hasMessageContaining("permission denied");
    }

    /**
     * <b>El observado de nombre largo y el sector de 20 no se pierden</b> (#408).
     *
     * <p>El nombre se copia entero de {@code nombre_razon_social}, que admite 240 caracteres, y una
     * «SUCESION INDIVISA …» los usa; el sector se compara con {@code predio_ref.sector_codigo
     * varchar(20)}. Hasta #408 la corrida los guardaba en {@code varchar(200)} y {@code
     * varchar(10)}: el rastro reventaba con 22001 al final, con la emision ya hecha, y la lista de
     * observados —lo unico que no se puede recomponer— se perdia. {@link #observadosDePrueba()} era
     * de nombres cortos: la muestra uniforme es la que lo escondio.
     */
    @Test
    @DisplayName(
            "#408 — un observado de 215 caracteres de nombre y un sector de 20 se guardan y se"
                    + " releen enteros")
    void elNombreLargoYElSectorAnchoSeGuardanEnteros() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        String sucesion = SUCESION_DE_215;
        assertThat(sucesion).hasSize(215);
        String sector = "SECTOR-URB-0000-0408";
        assertThat(sector).hasSize(20);

        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        new CorridaDeEmision(
                                                null,
                                                new Ejercicio(2018),
                                                "SECTOR",
                                                sector,
                                                null,
                                                null,
                                                "TRIMESTRAL",
                                                true,
                                                "",
                                                null,
                                                null,
                                                1,
                                                0,
                                                Dinero.CERO,
                                                LocalDate.of(2026, 1, 28),
                                                List.of(
                                                        new CorridaDeEmision.Observado(
                                                                "C-000408",
                                                                sucesion,
                                                                "Uno de sus predios esta sin"
                                                                        + " arancel"))),
                                        Observacion.de("Simulacion del sector")));

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2018)));
        assertThat(ultima).isPresent();
        assertThat(ultima.get().sector()).isEqualTo(sector);

        Pagina<CorridaDeEmision.Observado> observados =
                transaccion.execute(
                        estado ->
                                repositorio.observadosDe(
                                        requireId(guardada),
                                        new Paginacion(
                                                0, 20, "id", Paginacion.Sentido.ASCENDENTE)));
        assertThat(observados.contenido())
                .extracting(CorridaDeEmision.Observado::nombre)
                .as("el nombre entero: recortarlo seria otro contribuyente en el informe")
                .containsExactly(sucesion);
    }

    // ------------------------------------------------------------ ayudantes

    private static long requireId(CorridaDeEmision corrida) {
        Long id = corrida.id();
        if (id == null) {
            throw new IllegalStateException("La corrida guardada tiene id");
        }
        return id;
    }

    private static CorridaDeEmision corridaDe(
            int ejercicio,
            int leidos,
            int determinados,
            String monto,
            List<CorridaDeEmision.Observado> observados) {
        return new CorridaDeEmision(
                null,
                new Ejercicio(ejercicio),
                "TODOS",
                null,
                null,
                null,
                "TRIMESTRAL",
                false,
                "Conjunto 2026 v1",
                CONJUNTO_SELLADO,
                Dinero.de(DERECHO_SELLADO),
                leidos,
                determinados,
                Dinero.de(monto),
                LocalDate.of(2026, 1, 28),
                observados);
    }

    private static List<CorridaDeEmision.Observado> observadosDePrueba() {
        return List.of(
                new CorridaDeEmision.Observado(
                        "C-000042",
                        "MEDINA MEDINA, RUFINA",
                        "Uno de sus predios esta sin arancel para el ejercicio"));
    }

    private static void ejecutarComoApp(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException(fallo.getMessage(), fallo);
        }
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
}
