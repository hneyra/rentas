package kamayuk.rentas.rentas.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;
import java.util.List;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.parametros.InsumosDeLaAgregacion;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-011, encadenada con RT-013 (#30, AC1): demuestra con las clases reales —no una simulacion— el
 * punto critico de NEG-05 §1: agregar la base de todos los predios del contribuyente antes de
 * aplicar los tramos da un impuesto mayor que sumar el impuesto de cada predio por separado.
 */
@DisplayName("RT-011 + RT-013: por contribuyente, no por predio (#30 AC1)")
class RT011BaseImponibleDelContribuyenteTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final PoliticasDeRedondeo REDONDEO =
            PoliticasDeRedondeo.construir()
                    .en(
                            PuntoDeRedondeo.IMPUESTO_POR_TRAMO,
                            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                    .construir();

    /** Cuadro ficticio: 0.2 % hasta 1000, 0.6 % hasta 3000, 1.0 % en adelante. */
    private static final List<Tramo> CUADRO_FICTICIO =
            List.of(
                    Tramo.hasta(Dinero.de(1000), Alicuota.de("0.2")),
                    Tramo.hasta(Dinero.de(3000), Alicuota.de("0.6")),
                    Tramo.sinTope(Alicuota.de("1.0")));

    private final RT011BaseImponibleDelContribuyente rt011 =
            new RT011BaseImponibleDelContribuyente();

    @Test
    @DisplayName("identificador, vigencia y conceptos declarados")
    void identificaSusConceptos() {
        assertThat(rt011.identificador().valor()).isEqualTo("RT-011");
        assertThat(rt011.vigencia().rigeEn(EJERCICIO)).isTrue();
        assertThat(rt011.deCadaPartida())
                .isEqualTo(RT011BaseImponibleDelContribuyente.BASE_IMPONIBLE_PREDIO);
        assertThat(rt011.produce())
                .isEqualTo(RT011BaseImponibleDelContribuyente.BASE_IMPONIBLE_CONTRIBUYENTE);
    }

    @Test
    @DisplayName("agregar() suma la base ya ponderada de cada predio, en el orden en que llegan")
    void agregarSumaLosAportes() {
        InsumosDeLaAgregacion insumos = insumosDelEjercicio();

        Dinero baseContribuyente =
                rt011.agregar(List.of(Dinero.de(1500), Dinero.de(1500)), insumos);

        assertThat(baseContribuyente).isEqualTo(Dinero.de(3000));
    }

    @Test
    @DisplayName(
            "AC1: un contribuyente con tres predios pequenos paga mas agregado que la suma de"
                    + " tres determinaciones por predio (NEG-05 §1)")
    void unContribuyenteConVariosPrediosNoSeCalculaPredioPorPredio() {
        InsumosDeLaAgregacion insumos = insumosDelEjercicio();

        // Tres predios de 1000 cada uno: ninguno, por si solo, sale del primer tramo (0.2 %).
        List<Dinero> aportesPorPredio = List.of(Dinero.de(1000), Dinero.de(1000), Dinero.de(1000));

        // --- Lo correcto: NEG-05 §1, RT-011 agrega antes de aplicar los tramos ---
        Dinero baseDelContribuyente = rt011.agregar(aportesPorPredio, insumos);
        Dinero impuestoCorrecto =
                TramosProgresivosAcumulativos.calcular(
                        baseDelContribuyente, CUADRO_FICTICIO, REDONDEO);

        // --- El error que NEG-05 §1 advierte: tramos aplicados predio por predio ---
        Dinero impuestoSistematicamenteABaja = Dinero.CERO;
        for (Dinero aportePredio : aportesPorPredio) {
            impuestoSistematicamenteABaja =
                    impuestoSistematicamenteABaja.mas(
                            TramosProgresivosAcumulativos.calcular(
                                    aportePredio, CUADRO_FICTICIO, REDONDEO));
        }

        // Base agregada: 3000. Tramos: 1000*0.2% + 2000*0.6% = 2 + 12 = 14.00
        assertThat(baseDelContribuyente).isEqualTo(Dinero.de(3000));
        assertThat(impuestoCorrecto).isEqualTo(Dinero.de("14.00"));

        // Por predio: cada uno de los tres cae entero en el primer tramo: 1000*0.2% = 2.00 c/u
        assertThat(impuestoSistematicamenteABaja).isEqualTo(Dinero.de("6.00"));

        // El error es sistematico A LA BAJA: calcular por predio subestima el impuesto.
        assertThat(impuestoCorrecto.esMayorQue(impuestoSistematicamenteABaja)).isTrue();
    }

    private static InsumosDeLaAgregacion insumosDelEjercicio() {
        ParametrosSellados sellados = ParametrosSellados.de(EJERCICIO, 1).construir();
        return new InsumosDeLaAgregacion(EJERCICIO, sellados, REDONDEO);
    }
}
