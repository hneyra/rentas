package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.CargosEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.DocumentosEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.PadronQueVersiona;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * La transferencia a rentas: la frontera delicada del sistema (#52, RF-054, RF-057).
 *
 * <p>Sin base de datos y sin reloj del sistema: lo que se prueba aqui es <b>que decide</b> la
 * transferencia —que exige, en que orden escribe, con que sustento— y no como lo persiste. La
 * atomicidad, la concurrencia y la reconstruccion del padron necesitan PostgreSQL de verdad y estan
 * en {@code TransferenciaJdbcTest}.
 *
 * <p>Los cinco AC que se comprueban aqui: el sustento (AC 3), la version con su origen y su
 * documento (AC 2), que no se transfiera dos veces (AC 6, la mitad sin concurrencia), que los
 * cargos salgan de la liquidacion y no de un calculo, y que la estructura se transfiera aunque no
 * haya ni un importe —que es el estado de hoy, con D-02a abierta—.
 */
@DisplayName("#52 — Transferencia a rentas")
class TransferirARentasTest {

    private static final Observacion PORQUE =
            Observacion.de("Se transfiere lo hallado en la inspeccion");
    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final Ejercicio E2025 = new Ejercicio(2025);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final long CONTRIBUYENTE = 7L;
    private static final long PREDIO = 33L;
    private static final long CONJUNTO = 91L;

    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ActasEnMemoria actas;
    private ResolucionesEnMemoria resoluciones;
    private PadronQueVersiona padron;
    private CargosEnMemoria cargos;
    private DocumentosEnMemoria documentos;
    private TransferirARentas transferir;

    @BeforeEach
    void montar() {
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        actas = new ActasEnMemoria();
        resoluciones = new ResolucionesEnMemoria();
        padron = new PadronQueVersiona().con(PREDIO, "120.00", "CASA_HABITACION");
        cargos = new CargosEnMemoria();
        documentos = new DocumentosEnMemoria();

        transferir =
                new TransferirARentas(
                        liquidaciones,
                        movimientos,
                        actas,
                        resoluciones,
                        padron,
                        cargos,
                        new ContribuyentesDeMentira()
                                .con(CONTRIBUYENTE, "C-0007", "PEREZ, JUAN", "Jr. Union 100"),
                        new kamayuk.rentas.documentos.EmitirDocumento(
                                documentos,
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                (RegistroDeAuditoria registro) -> {},
                                RELOJ),
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ);
    }

    @Nested
    @DisplayName("AC 2 — Deja la ficha anterior intacta y una version nueva con su sustento")
    class DeLaVersion {

        @Test
        @DisplayName("inscribe lo hallado como version nueva, con el area y el uso del contraste")
        void inscribeLoHalladoComoVersionNueva() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());

            TransferirARentas.Transferencia hecha = transferir(liquidacion);

            assertThat(padron.escrituras()).as("una escritura en el padron, no dos").isEqualTo(1);
            PadronQueVersiona.Inscrito inscrito = padron.vigenteDe(PREDIO);
            assertThat(inscrito.version()).as("la version sube, no se sobrescribe").isEqualTo(2);
            assertThat(inscrito.area()).isEqualTo(AreaM2.de("300.00"));
            assertThat(inscrito.uso()).isEqualTo("COMERCIO");

            assertThat(hecha.version()).isNotNull();
            assertThat(hecha.version().areaAnterior()).isEqualTo(AreaM2.de("120.00"));
            assertThat(hecha.version().cambioElArea()).isTrue();
            assertThat(hecha.version().cambioElUso()).isTrue();
        }

        @Test
        @DisplayName("la version nueva se sustenta en el numero de la liquidacion")
        void laVersionSeSustentaEnLaLiquidacion() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());

            transferir(liquidacion);

            assertThat(padron.documentosDeOrigen())
                    .as(
                            "el documento_origen de la ficha es el acto que determino la"
                                    + " diferencia; es ademas lo unico que se conoce antes de"
                                    + " emitir el papel")
                    .containsExactly(liquidacion.numero());
        }

        @Test
        @DisplayName("la resolucion dice de que version a cual fue el padron")
        void laResolucionAtaLasDosVersiones() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());

            ResolucionDeDeterminacion registrada = transferir(liquidacion).resolucion();

            assertThat(registrada.fichaAnteriorId()).isNotNull();
            assertThat(registrada.fichaNuevaId()).isNotNull();
            assertThat(registrada.fichaAnteriorId()).isNotEqualTo(registrada.fichaNuevaId());
            assertThat(registrada.predioId()).isEqualTo(PREDIO);
            assertThat(registrada.liquidacionId()).isEqualTo(liquidacion.identificador());
        }

        @Test
        @DisplayName("una transferencia predial sin ficha nueva no se puede ni construir")
        void unaPredialSinFichaNuevaNoSeConstruye() {
            // La misma guarda que `resolucion_determinacion_version_ck` (V49) impone en la base:
            // registrar el acto sin su efecto seria decir que el padron cambio sin que cambiara.
            assertThatThrownBy(
                            () ->
                                    new ResolucionDeDeterminacion(
                                            null,
                                            "RDF-2026-000001",
                                            1L,
                                            1L,
                                            CONTRIBUYENTE,
                                            PREDIO,
                                            null,
                                            null,
                                            null,
                                            HOY,
                                            "ACTA-1",
                                            "sustento",
                                            "base legal",
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SIEMPRE versiona la ficha");
        }
    }

    @Nested
    @DisplayName("AC 3 — Sin sustento documental no se transfiere")
    class DelSustento {

        @Test
        @DisplayName("sin el papel que la respalda, se rechaza y no se toca el padron")
        void sinPapelNoSeTransfiere() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());

            assertThatThrownBy(
                            () ->
                                    transferir.transferir(
                                            new TransferirARentas.Peticion(
                                                    liquidacion.numero(),
                                                    HOY,
                                                    "   ",
                                                    "sustento",
                                                    "Codigo Tributario, art. 76"),
                                            FormatoDeDocumento.PDF,
                                            PORQUE))
                    .isInstanceOf(TransferirARentas.SinSustentoDocumental.class)
                    .hasMessageContaining("el papel que lo respalda");

            assertThat(padron.escrituras()).as("y el padron no se toco").isZero();
            assertThat(resoluciones.cuantas()).isZero();
        }

        @Test
        @DisplayName("una liquidacion ABIERTA no se transfiere: su contraste todavia se revisa")
        void abiertaNoSeTransfiere() {
            Liquidacion liquidacion = liquidacionEnEstado(EstadoDeLiquidacion.ABIERTA, sinCifras());

            assertThatThrownBy(() -> transferir(liquidacion))
                    .isInstanceOf(TransferirARentas.SinSustentoDocumental.class)
                    .hasMessageContaining("ABIERTA");

            assertThat(padron.escrituras()).isZero();
        }

        @Test
        @DisplayName("una liquidacion ANULADA tampoco")
        void anuladaNoSeTransfiere() {
            Liquidacion liquidacion = liquidacionEnEstado(EstadoDeLiquidacion.ANULADA, sinCifras());

            assertThatThrownBy(() -> transferir(liquidacion))
                    .isInstanceOf(TransferirARentas.SinSustentoDocumental.class)
                    .hasMessageContaining("ANULADA");
        }

        @Test
        @DisplayName("una NOTIFICADA si: el papel ya salio y el contraste es el definitivo")
        void notificadaSiSeTransfiere() {
            Liquidacion liquidacion =
                    liquidacionEnEstado(EstadoDeLiquidacion.NOTIFICADA, sinCifras());

            assertThat(transferir(liquidacion).resolucion().numero()).startsWith("RDF-2026-");
        }

        @Test
        @DisplayName("una version sustituida por una reliquidacion no se transfiere")
        void laVersionSustituidaNoSeTransfiere() {
            Liquidacion primera = liquidacionLista(sinCifras());
            liquidaciones.insertar(
                    primera.reliquidadaPor(
                            "LIQ-2026-000002",
                            new Ejercicio(2026),
                            2L,
                            E2024,
                            E2025,
                            TipoDeFiscalizacion.CIERTA,
                            "Area corregida",
                            HOY,
                            PORQUE),
                    sinCifras());

            assertThatThrownBy(() -> transferir(primera))
                    .isInstanceOf(TransferirARentas.LiquidacionSustituida.class)
                    .hasMessageContaining("LIQ-2026-000002");

            assertThat(padron.escrituras())
                    .as("transferir el contraste ya rectificado inscribiria un area que no es")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("AC 6 — Transferir dos veces no duplica")
    class DeLaSegundaVez {

        @Test
        @DisplayName("la segunda transferencia se rechaza, y el padron sigue en una sola version")
        void laSegundaSeRechaza() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());
            transferir(liquidacion);

            assertThatThrownBy(() -> transferir(liquidacion))
                    .isInstanceOf(
                            ResolucionDeDeterminacionRepository.LiquidacionYaTransferida.class)
                    .hasMessageContaining("segunda version de la ficha");

            assertThat(padron.escrituras()).isEqualTo(1);
            assertThat(padron.vigenteDe(PREDIO).version()).isEqualTo(2);
            assertThat(resoluciones.cuantas()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Los cargos de la diferencia")
    class DeLosCargos {

        @Test
        @DisplayName("con D-02a abierta la estructura se transfiere y no se asienta ni un cargo")
        void sinImportesNoHayCargos() {
            Liquidacion liquidacion = liquidacionLista(sinCifras());

            TransferirARentas.Transferencia hecha = transferir(liquidacion);

            assertThat(hecha.cargosAsentados())
                    .as(
                            "no es media transferencia: es la mitad que no depende de D-02,"
                                    + " hecha entera (#198)")
                    .isZero();
            assertThat(cargos.asentados()).isEmpty();
            assertThat(padron.vigenteDe(PREDIO).version())
                    .as("y la estructura si se inscribio")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("con importes, un cargo por linea y otro por multa, copiados de la linea")
        void conImportesUnCargoPorLinea() {
            Liquidacion liquidacion = liquidacionLista(conCifras());

            TransferirARentas.Transferencia hecha = transferir(liquidacion);

            assertThat(hecha.cargosAsentados()).isEqualTo(4);
            assertThat(cargos.asentados())
                    .extracting(CargosEnMemoria.Cargo::tributo)
                    .containsExactly("PREDIAL", "MULTA_TRIBUTARIA", "PREDIAL", "MULTA_TRIBUTARIA");
            assertThat(cargos.asentados())
                    .extracting(CargosEnMemoria.Cargo::monto)
                    .as("la cifra sale de la liquidacion; aqui no se calcula nada (regla 5)")
                    .containsExactly(
                            Dinero.de("450.00"),
                            Dinero.de("200.00"),
                            Dinero.de("510.00"),
                            Dinero.de("200.00"));
        }

        @Test
        @DisplayName("el cargo apunta al numero de la resolucion, que es el papel notificado")
        void elCargoApuntaAlPapel() {
            Liquidacion liquidacion = liquidacionLista(conCifras());

            TransferirARentas.Transferencia hecha = transferir(liquidacion);

            assertThat(cargos.asentados())
                    .allSatisfy(
                            cargo ->
                                    assertThat(cargo.documentoOrigen())
                                            .isEqualTo(hecha.resolucion().numero()));
        }

        @Test
        @DisplayName("el cargo va sin periodo: la diferencia es anual, no una cuota mas")
        void elCargoVaSinPeriodo() {
            transferir(liquidacionLista(conCifras()));

            assertThat(cargos.asentados())
                    .allSatisfy(
                            cargo -> {
                                assertThat(cargo.periodo()).isNull();
                                assertThat(cargo.predioId()).isEqualTo(PREDIO);
                                assertThat(cargo.contribuyenteId()).isEqualTo(CONTRIBUYENTE);
                            });
        }

        @Test
        @DisplayName("el tributo es el mismo con que rentas asienta lo ordinario")
        void elTributoEsElDeRentas() {
            transferir(liquidacionLista(conCifras()));

            // Si fuera «PREDIAL_FISCALIZADO», la diferencia crearia una obligacion paralela que
            // ninguna consulta de deuda sumaria con la ordinaria del mismo ejercicio y predio.
            assertThat(cargos.asentados())
                    .extracting(CargosEnMemoria.Cargo::ejercicio)
                    .containsExactly(E2024, E2024, E2025, E2025);
        }
    }

    @Nested
    @DisplayName("La resolucion y su papel")
    class DelPapel {

        @Test
        @DisplayName("el numero de la resolucion es el del documento, y sale un solo papel")
        void elNumeroEsElDelDocumento() {
            TransferirARentas.Transferencia hecha = transferir(liquidacionLista(sinCifras()));

            assertThat(hecha.resolucion().numero())
                    .isEqualTo(hecha.emision().registro().numero())
                    .isEqualTo("RDF-2026-000001");
            assertThat(hecha.emision().contenido()).isNotEmpty();
        }

        @Test
        @DisplayName("sin cifra el papel escribe una raya, y nunca un cero")
        void sinCifraNoSeDibujaUnCero() {
            TransferirARentas.Transferencia hecha = transferir(liquidacionLista(sinCifras()));

            assertThat(hecha.emision().registro().datos().tablas().get(0).filas())
                    .as("un contribuyente lee un cero como «no debo nada»")
                    .allSatisfy(fila -> assertThat(fila.subList(1, fila.size())).containsOnly("—"));
            assertThat(hecha.emision().registro().datos().pie())
                    .anySatisfy(
                            linea ->
                                    assertThat(linea)
                                            .contains("pendientes de determinacion")
                                            .contains("no significan deuda cero"));
        }

        @Test
        @DisplayName("el papel lleva el cuadro del padron: que constaba y que queda inscrito")
        void elPapelLlevaElCuadroDelPadron() {
            TransferirARentas.Transferencia hecha = transferir(liquidacionLista(sinCifras()));

            assertThat(hecha.emision().registro().datos().tablas())
                    .as("el cuadro de la determinacion y el de la inscripcion")
                    .hasSize(2);
            assertThat(hecha.emision().registro().datos().tablas().get(1).filas())
                    .anySatisfy(
                            fila ->
                                    assertThat(fila)
                                            .containsExactly(
                                                    "Area de terreno (m2)", "120.00", "300.00"));
        }

        @Test
        @DisplayName("el papel dice a que fecha estan sus cifras (regla 9)")
        void elPapelLlevaSuFecha() {
            TransferirARentas.Transferencia hecha = transferir(liquidacionLista(conCifras()));

            assertThat(hecha.emision().registro().datos().aLaFecha()).isEqualTo(HOY);
            assertThat(hecha.aLaFecha()).isEqualTo(HOY);
            assertThat(hecha.emision().registro().datos().tablas().get(0).titulo())
                    .contains(HOY.toString());
        }
    }

    // ------------------------------------------------------------------

    private TransferirARentas.Transferencia transferir(Liquidacion liquidacion) {
        return transferir.transferir(
                new TransferirARentas.Peticion(
                        liquidacion.numero(),
                        HOY,
                        "ACTA-2026-000001",
                        "Ampliacion no declarada, verificada en inspeccion",
                        "TUO del Codigo Tributario, arts. 76 y 77"),
                FormatoDeDocumento.PDF,
                PORQUE);
    }

    /** Una liquidacion emitida y cerrada, lista para transferirse. */
    private Liquidacion liquidacionLista(List<LineaDeLiquidacion> lineas) {
        return liquidacionEnEstado(EstadoDeLiquidacion.LIQUIDADA, lineas);
    }

    private Liquidacion liquidacionEnEstado(
            EstadoDeLiquidacion estado, List<LineaDeLiquidacion> lineas) {
        long actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                null,
                                LocalDate.of(2026, 3, 1),
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                null,
                                PORQUE));
        Liquidacion guardada =
                liquidaciones.insertar(
                        Liquidacion.primera(
                                "LIQ-2026-000001",
                                new Ejercicio(2026),
                                1L,
                                actaId,
                                E2024,
                                E2025,
                                TipoDeFiscalizacion.CIERTA,
                                "Ampliacion detectada",
                                HOY,
                                PORQUE),
                        lineas);
        movimientos.insertar(
                MovimientoDeLiquidacion.apertura(guardada.identificador(), HOY, "emitida", PORQUE));
        if (estado != EstadoDeLiquidacion.ABIERTA) {
            movimientos.insertar(
                    MovimientoDeLiquidacion.cambioDeEstado(
                            guardada.identificador(), estado, HOY, "cerrada", PORQUE));
        }
        return guardada;
    }

    /** El contraste tal como lo emite #49 hoy: estructura si, importes no (D-02a, #198). */
    private static List<LineaDeLiquidacion> sinCifras() {
        return List.of(linea(E2024, null, null), linea(E2025, null, null));
    }

    /**
     * El mismo contraste con importes.
     *
     * <p>Las cifras entran como <b>dato de prueba</b> por el constructor del tipo, no las calcula
     * nadie: el mecanismo de la transferencia no depende de D-02a, y probarlo con importes es lo
     * unico que permite comprobar que el cargo se asienta cuando lo hay.
     */
    private static List<LineaDeLiquidacion> conCifras() {
        return List.of(
                linea(E2024, Dinero.de("450.00"), Dinero.de("200.00")),
                linea(E2025, Dinero.de("510.00"), Dinero.de("200.00")));
    }

    private static LineaDeLiquidacion linea(
            Ejercicio ejercicio, @Nullable Dinero insoluto, @Nullable Dinero multa) {
        return new LineaDeLiquidacion(
                null,
                null,
                ejercicio,
                CONJUNTO,
                PREDIO,
                null,
                CondicionFiscalizada.SUBVALUADOR,
                AreaM2.de("120.00"),
                AreaM2.de("300.00"),
                "CASA_HABITACION",
                "COMERCIO",
                insoluto == null ? null : Dinero.de("30000.00"),
                insoluto == null ? null : Dinero.de("75000.00"),
                insoluto,
                multa);
    }
}
