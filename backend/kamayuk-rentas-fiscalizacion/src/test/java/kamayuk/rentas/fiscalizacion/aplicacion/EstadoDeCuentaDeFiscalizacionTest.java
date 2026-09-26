package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion.EstadoDeCuenta;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion.LineaDelEstadoDeCuenta;
import kamayuk.rentas.fiscalizacion.dobles.LibroEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El estado de cuenta de fiscalizacion pregunta por lo que la fiscalizacion origino, y no por la
 * unidad y el ejercicio (#342).
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Un libro <b>no vacio</b>, que es lo que las cinco pruebas que montaban este caso de uso no
 * tenian: el vehicular ordinario 2025 del vehiculo 7 —cuatro cuotas de 120,00 dadas de alta con el
 * documento {@code ALTA-1}— en la misma unidad y el mismo ejercicio que la fiscalizacion. Con un
 * libro vacio, casar por {@code (ejercicio, unidad, tributo)} y casar por documento de origen dan
 * lo mismo; con este, el primero se lleva los 480,00 ordinarios a la pantalla del fiscalizador.
 *
 * <p>Sin base de datos: lo que se prueba es <b>que pregunta</b> el caso de uso. Que el libro de
 * verdad conteste por clave de saldo lo mide {@code ConsultaDeDeudaCuentaCorrienteTest} contra
 * PostgreSQL, y el orden de las liquidaciones —que es SQL— {@code LiquidacionJdbcTest}.
 */
@DisplayName("#342 — El estado de cuenta de fiscalizacion solo cuenta lo que la RDF origino")
class EstadoDeCuentaDeFiscalizacionTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 1);
    private static final Observacion PORQUE = Observacion.de("Se liquida la segunda visita");
    private static final long CONTRIBUYENTE = 1L;
    private static final long ACTA = 10L;
    private static final long VEHICULO = 7L;
    private static final long CONJUNTO = 91L;
    private static final String RDF = "RDF-2026-000004";

    private LiquidacionesEnMemoria liquidaciones;
    private ResolucionesEnMemoria resoluciones;
    private LibroEnMemoria libro;
    private Liquidacion liquidacion;

    @BeforeEach
    void sembrar() {
        liquidaciones = new LiquidacionesEnMemoria();
        liquidaciones.actaDe(ACTA, CONTRIBUYENTE);
        resoluciones = new ResolucionesEnMemoria(liquidaciones);

        // (a) La deuda ORDINARIA del vehiculo 7 en 2025: cuatro cuotas, 480,00, con su alta.
        libro = new LibroEnMemoria();
        for (int cuota = 1; cuota <= 4; cuota++) {
            libro.cargo("VEHICULAR", 2025, cuota, null, VEHICULO, "120.00", "ALTA-1");
        }

        // El acta de 2026 sobre el vehiculo 7, liquidada para 2025 como SUBVALUADOR.
        liquidacion =
                liquidaciones.insertar(
                        Liquidacion.primera(
                                "LIQ-2026-000001",
                                new Ejercicio(2026),
                                1,
                                ACTA,
                                new Ejercicio(2025),
                                new Ejercicio(2025),
                                TipoDeFiscalizacion.CIERTA,
                                "Vehiculo subvaluado",
                                HOY,
                                PORQUE),
                        List.of(
                                LineaDeLiquidacion.vehicularSinCifras(
                                        new Ejercicio(2025),
                                        CONJUNTO,
                                        VEHICULO,
                                        CondicionFiscalizada.SUBVALUADOR)));
    }

    @Test
    @DisplayName("sin transferir, la linea sale sin cifra aunque el libro tenga deuda de la unidad")
    void sinTransferirLaLineaSaleSinCifra() {
        EstadoDeCuenta estado = estadoDeCuenta().de(CONTRIBUYENTE, HOY);

        LineaDelEstadoDeCuenta linea = unicaLinea(estado);
        assertThat(linea.deuda())
                .as(
                        "no se transfirio nada: los 480,00 son la deuda ORDINARIA del vehiculo 7, y"
                                + " casar por (ejercicio, unidad, tributo) se los atribuia a la"
                                + " fiscalizacion")
                .isNull();
        assertThat(linea.sinAsentar()).isTrue();
        assertThat(estado.total()).as("un total con una linea sin cifra no es un total").isNull();
    }

    @Test
    @DisplayName("sin ninguna RDF, el libro ni se consulta")
    void sinRdfElLibroNoSeConsulta() {
        estadoDeCuenta().de(CONTRIBUYENTE, HOY);

        assertThat(libro.consultas())
                .as("una liquidacion sin RDF no origino nada en el libro: no hay que preguntar")
                .isZero();
    }

    @Test
    @DisplayName("con la RDF registrada, la linea da los 100,00 de su cargo y no 580,00")
    void conLaRdfLaLineaDaLoDeLaRdf() {
        transferir();
        // (b) El cargo de oficio de la RDF: sin periodo, como lo asienta TransferirARentas.
        libro.cargo("VEHICULAR", 2025, null, null, VEHICULO, "100.00", RDF);

        EstadoDeCuenta estado = estadoDeCuenta().de(CONTRIBUYENTE, HOY);

        assertThat(unicaLinea(estado).deuda())
                .as("580,00 es la ordinaria mas la de oficio sumadas en una sola cifra")
                .isEqualTo(Dinero.de("100.00"));
        assertThat(estado.total()).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("la multa que asento la misma RDF entra en su linea: ya no se filtra por tributo")
    void laMultaDeLaRdfEntra() {
        transferir();
        libro.cargo("VEHICULAR", 2025, null, null, VEHICULO, "100.00", RDF);
        libro.cargo("MULTA_TRIBUTARIA", 2025, null, null, VEHICULO, "30.00", RDF);

        assertThat(unicaLinea(estadoDeCuenta().de(CONTRIBUYENTE, HOY)).deuda())
                .as(
                        "la transferencia asienta la multa como MULTA_TRIBUTARIA, y el filtro por"
                                + " PREDIAL o VEHICULAR la dejaba fuera")
                .isEqualTo(Dinero.de("130.00"));
    }

    @Test
    @DisplayName("lo de la RDF ya cobrado sale en 0,00 —asentado—, aunque el abono sea un recibo")
    void loCobradoSaleEnCero() {
        transferir();
        libro.cargo("VEHICULAR", 2025, null, null, VEHICULO, "100.00", RDF);
        libro.abono("VEHICULAR", 2025, null, null, VEHICULO, "100.00", "RECIBO 001-0000342");

        LineaDelEstadoDeCuenta linea = unicaLinea(estadoDeCuenta().de(CONTRIBUYENTE, HOY));
        assertThat(linea.deuda())
                .as(
                        "el abono lleva el documento del recibo y cuenta igual: se agrupa por clave"
                                + " de saldo, no por asiento (#401 sigue: saldada no es nunca"
                                + " asentada)")
                .isEqualTo(Dinero.de("0.00"));
        assertThat(linea.sinAsentar()).isFalse();
    }

    @Test
    @DisplayName("una RDF sin cargos —D-02a abierta— deja la linea sin cifra, no con la ordinaria")
    void unaRdfSinCargosDejaLaLineaSinCifra() {
        transferir();

        assertThat(unicaLinea(estadoDeCuenta().de(CONTRIBUYENTE, HOY)).deuda())
                .as("la transferencia de hoy asienta cero cargos: no hay nada que atribuirle")
                .isNull();
    }

    /**
     * La reliquidacion que llega despues de la transferencia: la linea ensena la version mas
     * reciente —que no tiene RDF, porque {@code UnidadYaDeterminada} ya no la deja transferir— y la
     * deuda que origino la RDF de la primera. Casar la cifra solo con la RDF de la liquidacion que
     * se pinta la escondia.
     */
    @Test
    @DisplayName("una reliquidacion posterior a la transferencia no esconde lo que la RDF origino")
    void laReliquidacionNoEscondeLoQueLaRdfOrigino() {
        transferir();
        libro.cargo("VEHICULAR", 2025, null, null, VEHICULO, "100.00", RDF);
        liquidaciones.insertar(
                liquidacion.reliquidadaPor(
                        "LIQ-2026-000002",
                        new Ejercicio(2026),
                        2,
                        new Ejercicio(2025),
                        new Ejercicio(2025),
                        TipoDeFiscalizacion.CIERTA,
                        "Condicion corregida",
                        HOY,
                        PORQUE),
                List.of(
                        LineaDeLiquidacion.vehicularSinCifras(
                                new Ejercicio(2025),
                                CONJUNTO,
                                VEHICULO,
                                CondicionFiscalizada.OMISO)));

        LineaDelEstadoDeCuenta linea = unicaLinea(estadoDeCuenta().de(CONTRIBUYENTE, HOY));
        assertThat(linea.condicion()).as("la version mas reciente").isEqualTo("OMISO");
        assertThat(linea.numeroLiquidacion()).isEqualTo("LIQ-2026-000002");
        assertThat(linea.deuda())
                .as("lo que la RDF viva origino sobre esta obligacion sigue siendo lo determinado")
                .isEqualTo(Dinero.de("100.00"));
    }

    // ------------------------------------------------------------------

    private EstadoDeCuentaDeFiscalizacion estadoDeCuenta() {
        return new EstadoDeCuentaDeFiscalizacion(liquidaciones, resoluciones, libro);
    }

    private void transferir() {
        resoluciones.registrar(
                ResolucionDeDeterminacion.vehicular(
                        RDF,
                        500L,
                        liquidacion.identificador(),
                        CONTRIBUYENTE,
                        VEHICULO,
                        HOY,
                        "INF-342",
                        "Lo hallado en la visita",
                        "TUO del Codigo Tributario, art. 76",
                        Observacion.de("Se transfiere lo hallado")));
    }

    private static LineaDelEstadoDeCuenta unicaLinea(EstadoDeCuenta estado) {
        assertThat(estado.lineas()).hasSize(1);
        return estado.lineas().get(0);
    }
}
