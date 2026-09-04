package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.dominio.CriterioDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.FaseDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionador;
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
 * La fase del procedimiento sancionador, derivada contra PostgreSQL de verdad y conectada como
 * {@code sgtm_app} (#397).
 *
 * <h2>Por qué esta prueba tiene que ser contra la base</h2>
 *
 * <p>Porque la fase <b>no existe en ningún sitio hasta que la consulta la calcula</b>: no hay
 * columna, no hay caso de uso y no hay función pura que se pueda llamar sin motor. Los hechos de
 * los que sale —el estado de la papeleta, la resolución que existe o no, la notificación previa que
 * sigue abierta o venció— viven en cuatro tablas, y comprobarlos con dobles sería comprobar los
 * dobles.
 *
 * <p>Y se conecta como {@code sgtm_app}, no como superusuario: un superusuario omite RLS incluso
 * con {@code FORCE ROW LEVEL SECURITY}, y esta consulta cruza {@code contribuyente}, {@code
 * codigo_infraccion}, {@code notificacion_administrativa} y {@code resolucion_gerencia} — cuatro
 * oportunidades de traerse filas de otra municipalidad si alguna política faltara.
 */
@DisplayName("#397 — La fase del procedimiento sancionador")
class ProcedimientoSancionadorRepositoryJdbcTest {

    /** El día desde el que se mira todo, salvo donde la prueba mueva el corte a propósito. */
    private static final LocalDate CORTE = LocalDate.of(2026, 8, 13);

    private static final LocalDate FECHA_ACTA = LocalDate.of(2026, 8, 10);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static PapeletaRepositoryJdbc papeletas;
    private static ProcedimientoSancionadorRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    private static long administradoDeA;
    private static long codigoDeA;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250701", "Municipalidad de procedimientos A");
        municipalidadB = crearMunicipalidad("250702", "Municipalidad de procedimientos B");
        administradoDeA = crearContribuyente(municipalidadA, "20525118447", "NOBLECILLA SAC");
        codigoDeA = crearCodigo(municipalidadA, "C-101");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        papeletas = new PapeletaRepositoryJdbc(jdbc);
        repositorio = new ProcedimientoSancionadorRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("inspector.municipal", null, null));
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ---------------------------------------------------------------- las cinco fases

    @Test
    @DisplayName("una acta cuya preventiva sigue en plazo esta en PREVENTIVA")
    void laPreventivaAbiertaEstaEnPreventiva() {
        long previa = crearNotificacionPrevia("NP-0001", FECHA_ACTA, (short) 5, "EMITIDA");
        acta("AC-PREV-01", previa);

        assertThat(faseDe("AC-PREV-01", CORTE)).isEqualTo(FaseDelProcedimiento.PREVENTIVA);
    }

    @Test
    @DisplayName("sin notificacion previa, el acta esta CONSTATADA: el manual la permite sin ella")
    void sinNotificacionPreviaEstaConstatada() {
        acta("AC-CONS-01", null);

        assertThat(faseDe("AC-CONS-01", CORTE)).isEqualTo(FaseDelProcedimiento.CONSTATADA);
    }

    @Test
    @DisplayName("con la preventiva subsanada, tambien CONSTATADA: ya no esta abierta")
    void conLaPreventivaSubsanadaEstaConstatada() {
        long previa = crearNotificacionPrevia("NP-0002", FECHA_ACTA, (short) 5, "SUBSANADA");
        acta("AC-CONS-02", previa);

        assertThat(faseDe("AC-CONS-02", CORTE)).isEqualTo(FaseDelProcedimiento.CONSTATADA);
    }

    @Test
    @DisplayName("con la RIS dictada, SANCIONADA")
    void conLaRisDictadaEstaSancionada() {
        Papeleta acta = acta("AC-SANC-01", null);
        dictarResolucionAdministrativa(acta.identificador(), "RIS-0001");

        assertThat(faseDe("AC-SANC-01", CORTE)).isEqualTo(FaseDelProcedimiento.SANCIONADA);
    }

    @Test
    @DisplayName("pagada y en coactiva se dicen con la palabra de la deuda, que es de la deuda")
    void pagadaYCoactivaSeDicenConLaPalabraDeLaDeuda() {
        acta("AC-PAGA-01", null);
        acta("AC-COAC-01", null);
        moverEstado("AC-PAGA-01", "PAGADA");
        moverEstado("AC-COAC-01", "COACTIVA");

        assertThat(faseDe("AC-PAGA-01", CORTE)).isEqualTo(FaseDelProcedimiento.PAGADA);
        assertThat(faseDe("AC-COAC-01", CORTE)).isEqualTo(FaseDelProcedimiento.COACTIVA);
    }

    @Test
    @DisplayName("la fase mas avanzada gana: pagada despues de la RIS dice PAGADA, no SANCIONADA")
    void laFaseMasAvanzadaGana() {
        Papeleta acta = acta("AC-PAGA-02", null);
        dictarResolucionAdministrativa(acta.identificador(), "RIS-0002");
        moverEstado("AC-PAGA-02", "PAGADA");

        assertThat(faseDe("AC-PAGA-02", CORTE)).isEqualTo(FaseDelProcedimiento.PAGADA);
    }

    @Test
    @DisplayName("un acta anulada no tiene fase: ninguna de las cinco palabras la nombra")
    void unActaAnuladaNoTieneFase() {
        acta("AC-ANUL-01", null);
        moverEstado("AC-ANUL-01", "ANULADA");

        assertThat(faseDe("AC-ANUL-01", CORTE)).isNull();
        // Y la que saldria sola —la de la rama ELSE— seria justo la equivocada.
        assertThat(faseDe("AC-ANUL-01", CORTE)).isNotEqualTo(FaseDelProcedimiento.CONSTATADA);
    }

    // ---------------------------------------------------------------- la fecha

    @Test
    @DisplayName("la misma acta cambia de fase cuando vence el plazo, y por eso la fecha viaja")
    void laMismaActaCambiaDeFaseCuandoVenceElPlazo() {
        long previa = crearNotificacionPrevia("NP-0003", FECHA_ACTA, (short) 5, "EMITIDA");
        acta("AC-PREV-02", previa);

        // 2026-08-10 + 5 dias = 2026-08-15. El dia 14 sigue abierta; el 16 no.
        assertThat(faseDe("AC-PREV-02", LocalDate.of(2026, 8, 14)))
                .isEqualTo(FaseDelProcedimiento.PREVENTIVA);
        assertThat(faseDe("AC-PREV-02", LocalDate.of(2026, 8, 16)))
                .isEqualTo(FaseDelProcedimiento.CONSTATADA);

        // Y la fila dice a que fecha lo dijo (regla 9, RNF-075).
        assertThat(filaDe("AC-PREV-02", LocalDate.of(2026, 8, 16)).faseAlDia())
                .isEqualTo(LocalDate.of(2026, 8, 16));
    }

    @Test
    @DisplayName("una preventiva sin plazo no vence nunca, igual que en las vencidas (#47 AC3)")
    void unaPreventivaSinPlazoNoVenceNunca() {
        long previa = crearNotificacionPrevia("NP-0004", FECHA_ACTA, null, "EMITIDA");
        acta("AC-PREV-03", previa);

        assertThat(faseDe("AC-PREV-03", LocalDate.of(2030, 1, 1)))
                .isEqualTo(FaseDelProcedimiento.PREVENTIVA);
    }

    // ---------------------------------------------------------------- el filtro

    @Test
    @DisplayName("el filtro por fase encuentra EXACTAMENTE lo que la columna ensena")
    void elFiltroEncuentraLoQueLaColumnaEnsena() {
        long previa = crearNotificacionPrevia("NP-0005", FECHA_ACTA, (short) 5, "EMITIDA");
        acta("AC-F-PREV", previa);
        acta("AC-F-CONS", null);
        Papeleta sancionada = acta("AC-F-SANC", null);
        dictarResolucionAdministrativa(sancionada.identificador(), "RIS-0005");
        // Pagada Y sancionada a la vez: es la fila que distingue el orden de
        // las ramas, y sin ella la prueba pasa en verde con las dos copias del
        // CASE divergiendo (se midio: la mutacion que adelanta la rama de la
        // RIS a la del pago se colaba entera).
        Papeleta pagada = acta("AC-F-PAGA", null);
        dictarResolucionAdministrativa(pagada.identificador(), "RIS-0006");
        acta("AC-F-COAC", null);
        acta("AC-F-ANUL", null);
        moverEstado("AC-F-PAGA", "PAGADA");
        moverEstado("AC-F-COAC", "COACTIVA");
        moverEstado("AC-F-ANUL", "ANULADA");

        List<ProcedimientoSancionador> todas = todas(CORTE);

        for (FaseDelProcedimiento fase : FaseDelProcedimiento.values()) {
            List<String> esperadas =
                    todas.stream()
                            .filter(fila -> fila.fase() == fase)
                            .map(ProcedimientoSancionador::numeroActa)
                            .sorted()
                            .toList();
            List<String> filtradas =
                    pagina(new CriterioDelProcedimiento(null, null, null, fase, CORTE))
                            .contenido()
                            .stream()
                            .map(ProcedimientoSancionador::numeroActa)
                            .sorted()
                            .toList();

            assertThat(filtradas).as("filtro por %s", fase).isEqualTo(esperadas);
        }

        // Y el acta sin fase no la encuentra ningun filtro: no es que salga en
        // «CONSTATADA», es que no sale en ninguno.
        assertThat(todas.stream().map(ProcedimientoSancionador::numeroActa)).contains("AC-F-ANUL");
        for (FaseDelProcedimiento fase : FaseDelProcedimiento.values()) {
            assertThat(
                            pagina(new CriterioDelProcedimiento(null, null, null, fase, CORTE))
                                    .contenido()
                                    .stream()
                                    .map(ProcedimientoSancionador::numeroActa))
                    .as("el acta anulada no aparece bajo %s", fase)
                    .doesNotContain("AC-F-ANUL");
        }
    }

    @Test
    @DisplayName("los otros tres filtros siguen viajando: acta, administrado y codigo del CUIS")
    void losOtrosTresFiltrosSiguenViajando() {
        acta("AC-FILT-01", null);

        assertThat(
                        pagina(new CriterioDelProcedimiento("AC-FILT-01", null, null, null, CORTE))
                                .totalElementos())
                .isEqualTo(1);
        assertThat(
                        pagina(new CriterioDelProcedimiento(null, "20525118447", null, null, CORTE))
                                .totalElementos())
                .isPositive();
        assertThat(
                        pagina(new CriterioDelProcedimiento(null, null, "C-101", null, CORTE))
                                .totalElementos())
                .isPositive();
        assertThat(
                        pagina(new CriterioDelProcedimiento(null, null, "C-999", null, CORTE))
                                .totalElementos())
                .isZero();
    }

    @Test
    @DisplayName("se puede ordenar por la fase, que no es columna de ninguna tabla")
    void sePuedeOrdenarPorLaFase() {
        acta("AC-ORD-01", null);

        // `ORDER BY fase` se resuelve contra el NOMBRE DE SALIDA del SELECT, no
        // contra una columna: si el alias faltara —o si la lista blanca lo
        // admitiera sin que el SELECT lo publique—, PostgreSQL respondería con un
        // error de columna inexistente y la pantalla se caería al ordenar.
        Pagina<ProcedimientoSancionador> ordenada =
                transaccion.execute(
                        estado ->
                                repositorio.buscar(
                                        new CriterioDelProcedimiento(
                                                "AC-ORD-01", null, null, null, CORTE),
                                        Paginacion.de(0, 20, "fase")));

        assertThat(ordenada.totalElementos()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- las ocho columnas

    @Test
    @DisplayName(
            "la fila trae las ocho columnas del manual, y las cuatro que cruza salen del cruce")
    void laFilaTraeLasOchoColumnas() {
        acta("AC-COLS-01", null);

        ProcedimientoSancionador fila = filaDe("AC-COLS-01", CORTE);

        assertThat(fila.numeroActa()).isEqualTo("AC-COLS-01");
        assertThat(fila.administrado()).isEqualTo("NOBLECILLA SAC");
        assertThat(fila.codigoCuis()).isEqualTo("C-101");
        assertThat(fila.descripcionInfraccion()).isEqualTo("Funcionar sin licencia municipal");
        assertThat(fila.medidaComplementaria()).isEqualTo("Clausura temporal");
        // El porcentaje es el del ACTA, congelado, no el que hoy tenga el catalogo.
        assertThat(fila.porcentajeInfraccion()).isEqualTo(Alicuota.de("50"));
        assertThat(fila.importeAPagar()).isEqualTo(Dinero.de("2675"));
        assertThat(fila.fechaInfraccion()).isEqualTo(FECHA_ACTA);
    }

    // ---------------------------------------------------------------- RLS

    @Test
    @DisplayName("la grilla no cruza la municipalidad, con sus cuatro tablas")
    void laGrillaNoCruzaLaMunicipalidad() {
        acta("AC-RLS-01", null);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        assertThat(
                        pagina(new CriterioDelProcedimiento("AC-RLS-01", null, null, null, CORTE))
                                .totalElementos())
                .isZero();
    }

    // ------------------------------------------------------------------

    private static FaseDelProcedimiento faseDe(String numeroActa, LocalDate aLaFecha) {
        return filaDe(numeroActa, aLaFecha).fase();
    }

    private static ProcedimientoSancionador filaDe(String numeroActa, LocalDate aLaFecha) {
        return pagina(new CriterioDelProcedimiento(numeroActa, null, null, null, aLaFecha))
                .contenido()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No se encontro " + numeroActa));
    }

    private static List<ProcedimientoSancionador> todas(LocalDate aLaFecha) {
        return pagina(new CriterioDelProcedimiento(null, null, null, null, aLaFecha)).contenido();
    }

    private static Pagina<ProcedimientoSancionador> pagina(CriterioDelProcedimiento criterio) {
        return transaccion.execute(
                estado -> repositorio.buscar(criterio, Paginacion.de(0, 100, "fechaInfraccion")));
    }

    private static Papeleta acta(String numero, Long notificacionPreviaId) {
        return transaccion.execute(
                estado ->
                        papeletas.insertar(
                                Papeleta.nuevaAdministrativa(
                                        numero,
                                        codigoDeA,
                                        FECHA_ACTA,
                                        null,
                                        "AV. JOSE DE LAMA 1180",
                                        administradoDeA,
                                        null,
                                        notificacionPreviaId,
                                        administradoDeA,
                                        Dinero.de("5350"),
                                        Alicuota.de("50"),
                                        Dinero.de("2675"),
                                        Alicuota.de("100"),
                                        Dinero.de("2675"),
                                        null,
                                        Observacion.de("Acta levantada en la inspeccion"))));
    }

    /**
     * Mueve el estado de la DEUDA por SQL directo, que es lo único que hay hoy: nada en {@code
     * sanciones} lo mueve todavía —la papeleta nace {@code IMPUESTA} y ahí se queda—. Lo que esta
     * prueba necesita es una fila en cada estado, no el camino que la lleva ahí.
     */
    private static void moverEstado(String numeroActa, String estado) {
        ejecutar(
                "UPDATE papeleta SET estado = '"
                        + estado
                        + "' WHERE familia = 'ADMINISTRATIVA' AND numero = '"
                        + numeroActa
                        + "'");
    }

    private static void dictarResolucionAdministrativa(long papeletaId, String numero) {
        long documento = documentoSuelto("RGA", numero);
        ejecutar(
                "INSERT INTO resolucion_gerencia (municipalidad_id, papeleta_id, tipo, numero,"
                        + " documento_id, fecha, sustento, usuario_registro, fecha_registro,"
                        + " observacion) VALUES ("
                        + municipalidadA
                        + ", "
                        + papeletaId
                        + ", 'ADMINISTRATIVA', '"
                        + numero
                        + "', "
                        + documento
                        + ", DATE '2026-08-12', 'sustento de la prueba', 'gerencia', now(),"
                        + " 'dictada para la prueba')");
    }

    private static long crearNotificacionPrevia(
            String numero, LocalDate fecha, Short plazoDias, String estado) {
        return insertar(
                "INSERT INTO notificacion_administrativa (municipalidad_id, numero, fecha,"
                        + " contribuyente_id, direccion, motivo, plazo_dias, estado,"
                        + " usuario_registro) VALUES ("
                        + municipalidadA
                        + ", '"
                        + numero
                        + "', DATE '"
                        + fecha
                        + "', "
                        + administradoDeA
                        + ", 'AV. JOSE DE LAMA 1180', 'Funciona sin licencia', "
                        + (plazoDias == null ? "NULL" : plazoDias.toString())
                        + ", '"
                        + estado
                        + "', 'inspector.municipal') RETURNING id");
    }

    private static long documentoSuelto(String tipo, String numero) {
        return insertar(
                "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                        + " referencia, datos, formato, resumen, fecha_emision, usuario_emision,"
                        + " observacion) VALUES ("
                        + municipalidadA
                        + ", '"
                        + tipo
                        + "', '"
                        + numero
                        + "', 2026, 'prueba', CAST('{\"titulo\":\"x\",\"subtitulo\":null,"
                        + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],\"pie\":[],"
                        + "\"duplicado\":null}' AS jsonb), 'PDF', repeat('f', 64),"
                        + " DATE '2026-01-01', 'siembra', 'documento de prueba') RETURNING id");
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

    private static long crearContribuyente(
            long municipalidadId, String numeroDocumento, String nombre) {
        return insertarEn(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente, tipo_documento,"
                        + " numero_documento, tipo_persona, nombre_razon_social, usuario_registro)"
                        + " VALUES ("
                        + municipalidadId
                        + ", 'C-"
                        + numeroDocumento
                        + "', 'RUC', '"
                        + numeroDocumento
                        + "', 'JURIDICA', '"
                        + nombre
                        + "', 'prueba') RETURNING id");
    }

    private static long crearCodigo(long municipalidadId, String codigo) {
        return insertarEn(
                municipalidadId,
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, medida, base_legal, vigencia_desde) VALUES ("
                        + municipalidadId
                        + ", 'ADMINISTRATIVA', '"
                        + codigo
                        + "', 'Funcionar sin licencia municipal', 50.0000,"
                        + " 'Clausura temporal', 'Ordenanza de la prueba', '2020-01-01')"
                        + " RETURNING id");
    }

    private static long insertar(String sql) {
        return insertarEn(municipalidadA, sql);
    }

    private static long insertarEn(long municipalidadId, String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                app.commit();
                return id;
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, excepcion);
        }
    }

    private static void ejecutar(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException("No se pudo ejecutar: " + sql, excepcion);
        }
    }
}
