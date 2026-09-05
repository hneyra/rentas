package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellaDelLote;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La anti-entropia encuentra la fila desincronizada y NOMBRA el sector (P6, punto 4, AC 2).
 *
 * <p>Contra PostgreSQL de verdad y conectado como {@code sgtm_app}, que es lo unico que hace fiel a
 * esta prueba: la proyeccion lleva RLS con {@code FORCE}, asi que una prueba escrita con el dueno
 * de las tablas pasaria en verde con el aislamiento roto (#537, #545, #601). El centinela lo fija.
 *
 * <h2>Que hace de «catastro» aqui, y por que eso es legitimo</h2>
 *
 * <p>El otro lado no es un doble que promete una huella: es {@link HuellaDelLote} —la funcion pura
 * de produccion— aplicada a las filas que se sembraron. Es decir, este lado calcula sobre
 * PostgreSQL y el otro sobre los mismos datos con el mismo algoritmo, que es exactamente lo que
 * pasa en produccion con dos bases. Lo que garantiza que las dos implementaciones sean la misma
 * funcion es otra prueba —los vectores de oro, que {@code catastro} reproduce en su propio CI—;
 * aqui lo que se mide es que una fila movida se vea.
 *
 * <p>Sin esa separacion, esta prueba tendria que levantar los dos sistemas para decir algo, y
 * entonces no se correria en cada PR.
 */
@DisplayName("P6 — La anti-entropia encuentra el sector desincronizado")
class AntiEntropiaJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 4);

    /**
     * El escenario: seis lotes en tres sectores, y uno sin sectorizar.
     *
     * <p>Tres sectores y no uno: con un solo sector, «la anti-entropia encuentra la discrepancia» y
     * «la anti-entropia dice que todo discrepa» dan el mismo resultado, y son lo contrario. Lo que
     * el AC pide es que <b>nombre</b> el sector, y nombrar solo tiene sentido si los demas callan.
     */
    private static final List<Lote> PADRON =
            List.of(
                    new Lote(9001L, "250901010100100001", "AV. GRAU 100", "SA", "ACTIVO"),
                    new Lote(9002L, "250901010100100002", "AV. GRAU 102", "SA", "ACTIVO"),
                    new Lote(9003L, "250901020100100001", "CALLE LIMA 10", "SB", "ACTIVO"),
                    new Lote(9004L, "250901020100100002", "CALLE LIMA 12", "SB", "ACTIVO"),
                    new Lote(9005L, "250901030100100001", "JR. PIURA 5", "SC", "ACTIVO"),
                    new Lote(9006L, "250901090900100001", "SIN SECTOR S/N", null, "ACTIVO"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static HuellasDeLaProyeccionJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad =
                DatosDePrueba.crearMunicipalidad(base, "250901", "Municipalidad de anti-entropia");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new HuellasDeLaProyeccionJdbc(jdbc);

        proyectar(PADRON);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    /**
     * El centinela de #545: la conexion tiene que ser la de la aplicacion.
     *
     * <p>Con {@code FORCE ROW LEVEL SECURITY}, el DUENO de las tablas tambien queda sujeto a la
     * politica, asi que la rotura de aislamiento que uno teclea por costumbre —cambiar el rol por
     * {@code sgtm_owner}— dejaria estas pruebas en verde sin medir nada.
     */
    @Test
    @DisplayName("se conecta como sgtm_app, no como el dueno")
    void seConectaComoSgtmApp() {
        assertThat(comoApp(() -> jdbc.sql("SELECT current_user").query(String.class).single()))
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("con la proyeccion al dia, ningun sector discrepa")
    void conLaProyeccionAlDiaNingunoDiscrepa() {
        AntiEntropia.Informe informe =
                AntiEntropia.comparar(deCatastro(PADRON), comoApp(repositorio::porSector), HOY);

        // Las dos mitades: que no haya discrepancias Y que se haya comparado algo. «0 de 0» y
        // «0 de 4» se leen igual en un booleano, y la primera es que nadie comparo nada.
        assertThat(informe.cuadra()).as(informe.comoTexto()).isTrue();
        assertThat(informe.sectoresComparados()).isEqualTo(4);
    }

    /**
     * AC 2 de P6: se desincroniza a mano UNA fila y la anti-entropia la encuentra, nombrando el
     * sector.
     */
    @Test
    @DisplayName("AC 2 — una sola fila desincronizada, y el informe nombra su sector")
    void unaFilaDesincronizadaSaleConSuSector() throws SQLException {
        // La direccion de UN predio del sector SB cambia en el origen y la proyeccion se queda
        // con la vieja. Es el evento que no llego: la fila esta, tiene la forma correcta y dice
        // otra cosa. Ninguna consulta del sistema lo delata.
        List<Lote> enCatastro = new ArrayList<>(PADRON);
        enCatastro.set(3, new Lote(9004L, "250901020100100002", "CALLE LIMA 14", "SB", "ACTIVO"));

        AntiEntropia.Informe informe =
                AntiEntropia.comparar(deCatastro(enCatastro), comoApp(repositorio::porSector), HOY);

        assertThat(informe.cuadra()).isFalse();
        assertThat(informe.noCuadran()).hasSize(1);

        AntiEntropia.SectorQueNoCuadra sector = informe.noCuadran().get(0);
        assertThat(sector.sector()).isEqualTo("SB");
        assertThat(sector.porQue()).isEqualTo(AntiEntropia.Discrepancia.HUELLA_DISTINTA);
        // Los recuentos coinciden: no faltan filas, una dice otra cosa. Es la distincion que
        // separa «se perdio un evento» de «se aplico uno con otro contenido», y se arreglan
        // distinto.
        assertThat(sector.lotesEnCatastro()).isEqualTo(2);
        assertThat(sector.lotesEnLaProyeccion()).isEqualTo(2);
        assertThat(informe.comoTexto()).contains("sector «SB»");

        // Y los otros tres callan, que es la mitad que hace util «nombra el sector».
        assertThat(informe.sectoresComparados()).isEqualTo(4);
    }

    @Test
    @DisplayName("y un sector entero que no llego se dice de otra manera")
    void unSectorQueNoLlegoSeDiceDeOtraManera() {
        List<Lote> enCatastro = new ArrayList<>(PADRON);
        enCatastro.add(new Lote(9007L, "250901040100100001", "PSJE. NUEVO 1", "SD", "ACTIVO"));

        AntiEntropia.Informe informe =
                AntiEntropia.comparar(deCatastro(enCatastro), comoApp(repositorio::porSector), HOY);

        assertThat(informe.noCuadran()).hasSize(1);
        assertThat(informe.noCuadran().get(0).porQue())
                .as("falta el sector entero: no hay ninguna fila que comparar")
                .isEqualTo(AntiEntropia.Discrepancia.FALTA_EN_LA_PROYECCION);
        assertThat(informe.comoTexto())
                .contains("«SD»")
                .contains("la proyeccion no tiene el sector");
    }

    /**
     * Y el sector sin codigo se compara igual que los demas.
     *
     * <p>El caso que se cae solo si alguien lo deja fuera del mapa por ser nulo: sus predios
     * existen, y un lote sin sectorizar que cambie tiene que verse. Sale nombrado como «sin
     * sectorizar» y no como una cadena vacia, que se leeria como un sector que se llama «».
     */
    @Test
    @DisplayName("y el sector sin codigo se compara y se nombra")
    void elSectorSinCodigoSeComparaYSeNombra() {
        List<Lote> enCatastro = new ArrayList<>(PADRON);
        enCatastro.set(5, new Lote(9006L, "250901090900100001", "SIN SECTOR S/N", null, "BAJA"));

        AntiEntropia.Informe informe =
                AntiEntropia.comparar(deCatastro(enCatastro), comoApp(repositorio::porSector), HOY);

        assertThat(informe.noCuadran()).hasSize(1);
        assertThat(informe.noCuadran().get(0).sector()).isNull();
        assertThat(informe.comoTexto()).contains("«sin sectorizar»");
    }

    // ------------------------------------------------------------------

    /** Lo que el padron dice de si mismo: la misma funcion pura, sobre los mismos datos. */
    private static List<AntiEntropia.HuellaDeSector> deCatastro(List<Lote> lotes) {
        List<Lote> ordenados = new ArrayList<>(lotes);
        ordenados.sort(
                java.util.Comparator.comparing(
                                (Lote lote) -> lote.sector() == null ? "" : lote.sector())
                        .thenComparingLong(Lote::predioId));

        List<AntiEntropia.HuellaDeSector> huellas = new ArrayList<>();
        List<String> deEste = new ArrayList<>();
        String enCurso = null;
        boolean empezado = false;
        for (Lote lote : ordenados) {
            if (empezado && !java.util.Objects.equals(enCurso, lote.sector())) {
                huellas.add(
                        new AntiEntropia.HuellaDeSector(
                                enCurso,
                                deEste.size(),
                                HuellaDelLote.deUnSector(List.copyOf(deEste))));
                deEste = new ArrayList<>();
            }
            enCurso = lote.sector();
            empezado = true;
            deEste.add(lote.huella());
        }
        if (empezado) {
            huellas.add(
                    new AntiEntropia.HuellaDeSector(
                            enCurso, deEste.size(), HuellaDelLote.deUnSector(List.copyOf(deEste))));
        }
        return List.copyOf(huellas);
    }

    private static <T> T comoApp(java.util.function.Supplier<T> lectura) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return transaccion.execute(estado -> lectura.get());
        } finally {
            TenantContext.limpiar();
        }
    }

    /**
     * Siembra la proyeccion con el rol del ingestor.
     *
     * <p>`V4` solo le da `SELECT` a `sgtm_app` sobre `predio_ref`, y eso no es una precaucion: es
     * lo que hace cierto ADR-0027 §3 en vez de una promesa. Una prueba que sembrara con la conexion
     * de la aplicacion estaria midiendo un sistema que no es el que se despliega.
     */
    private static void proyectar(List<Lote> lotes) throws SQLException {
        UUID evento = UUID.randomUUID();
        try (Connection ingestor = base.conexion("rol_ingestor_catastro")) {
            try (PreparedStatement contexto =
                    ingestor.prepareStatement(
                            "SELECT set_config('app.municipalidad_id', ?, false)")) {
                contexto.setString(1, Long.toString(municipalidad));
                contexto.execute();
            }
            try (PreparedStatement buzon =
                    ingestor.prepareStatement(
                            """
                            INSERT INTO catastro_evento_aplicado
                                   (municipalidad_id, evento_id, secuencia, tipo, aplicado_en, huella)
                            VALUES (?, ?, 1, 'PREDIO_PROYECTADO', now(), ?)
                            """)) {
                buzon.setLong(1, municipalidad);
                buzon.setObject(2, evento);
                buzon.setString(3, "0".repeat(64));
                buzon.executeUpdate();
            }
            try (PreparedStatement fila =
                    ingestor.prepareStatement(
                            """
                            INSERT INTO predio_ref
                                   (municipalidad_id, predio_id, codigo_ref_catastral, direccion,
                                    sector_codigo, estado, secuencia, proyectado_en, evento_id, huella)
                            VALUES (?, ?, ?, ?, ?, ?, 1, now(), ?, ?)
                            """)) {
                for (Lote lote : lotes) {
                    fila.setLong(1, municipalidad);
                    fila.setLong(2, lote.predioId());
                    fila.setString(3, lote.codRefCatastral());
                    fila.setString(4, lote.direccion());
                    fila.setString(5, lote.sector());
                    fila.setString(6, lote.estado());
                    fila.setObject(7, evento);
                    fila.setString(8, lote.huella());
                    fila.addBatch();
                }
                fila.executeBatch();
            }
            // Sin autocommit, y a proposito:  lo apaga porque el contexto de
            // tenant se fija con `SET LOCAL` y eso exige una transaccion abierta. Sin este
            // `commit` la siembra no llega a existir y la comparacion sale con los cuatro
            // sectores «faltan en la proyeccion» — que es un rojo verdadero por un motivo que
            // no es el que se mide.
            ingestor.commit();
        }
    }

    /** Un lote del escenario. Calcula su huella con la funcion de produccion. */
    private record Lote(
            long predioId, String codRefCatastral, String direccion, String sector, String estado) {

        String huella() {
            return HuellaDelLote.deUnLote(predioId, codRefCatastral, direccion, sector, estado);
        }
    }
}
