package kamayuk.rentas.indicadores.infraestructura.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.indicadores.dominio.AvanceDeRecaudacion;
import kamayuk.rentas.indicadores.dominio.Cartera;
import kamayuk.rentas.indicadores.dominio.Indicador;
import kamayuk.rentas.indicadores.dominio.LineaDeCartera;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El panel, tal como sale por HTTP (#56, RF-130).
 *
 * <h2>La forma la fija la pantalla, y ya existia</h2>
 *
 * <p>{@code frontend/apps/backoffice/src/pantallas/inicio/recaudacion.ts} valida esta respuesta
 * desde antes de que hubiera backend: {@code fechaCalculo} obligatorio, {@code kpis} con {@code
 * label}/{@code value}/{@code note} y {@code paneles} con {@code title}/{@code note}/{@code rows}.
 * Los nombres en ingles de esos cuatro campos no son un descuido del estandar de idioma: son los
 * del renderizador comun, portados del prototipo, y cambiarlos aqui romperia las 134 pantallas para
 * arreglar una. Lo que si esta en español es todo lo que este endpoint añade.
 *
 * <h2>Cada cifra con su fecha, dos veces</h2>
 *
 * <p>{@code value} es el texto que se dibuja —lo redacta el servidor, RNF-080— y {@code importe} es
 * la misma cifra como {@link ImporteActualizado}, que no admite un importe sin su fecha. La regla
 * de ArchUnit {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} lo verifica sobre esta clase: un {@code
 * Dinero} suelto aqui rompe el build (RNF-075, regla 9). Va nulo cuando la cifra no es un importe
 * —un porcentaje, un recuento— porque entonces no hay importe que fechar, y el {@code note} lo
 * dice.
 *
 * <p>{@code calculadoEn} es el instante con zona, junto a la {@code fechaCalculo} del dia
 * tributario: dos lecturas del mismo dia dan cifras distintas y sin la hora no se distinguen (AC 2
 * de #56).
 *
 * <h2>Lo cargado es un campo (#549)</h2>
 *
 * <p>{@code cargado} es lo emitido del ejercicio, y hasta #549 la unica forma de leerlo era sacarlo
 * de la frase del KPI «Avance de cobranza» con una expresion regular. La franja «Emitido contra
 * recaudado» de la pantalla de inicio se habia retirado por eso: sin el campo, la unica cifra
 * disponible era la de la maqueta, que contradecia por tres ordenes de magnitud a las filas que
 * tenia justo debajo.
 *
 * <p>Y va donde va el resto de los importes: como {@link ImporteActualizado}, nunca como un {@code
 * Dinero} suelto. Lo mismo las dos cifras nuevas de cada fila —{@code cargado} y {@code
 * pendiente}—, que ahora salen ademas del {@code sub} de texto que ya las decia.
 *
 * @param ejercicio el ejercicio del panel
 * @param fechaCalculo el dia al que corresponden las cifras
 * @param calculadoEn el instante exacto en que se leyeron
 * @param cargado lo emitido del ejercicio, con su fecha
 * @param kpis las cifras grandes
 * @param paneles los bloques de filas
 */
public record PanelResource(
        int ejercicio,
        LocalDate fechaCalculo,
        Instant calculadoEn,
        ImporteActualizado cargado,
        List<Kpi> kpis,
        List<Bloque> paneles) {

    public static PanelResource de(AvanceDeRecaudacion avance) {
        return new PanelResource(
                avance.ejercicio().valor(),
                avance.fechaCalculo(),
                avance.calculadoEn(),
                new ImporteActualizado(avance.cargado(), avance.cargadoA()),
                avance.indicadores().stream().map(Kpi::de).toList(),
                avance.carteras().stream().map(Bloque::de).toList());
    }

    /**
     * Una cifra grande.
     *
     * @param label lo que mide
     * @param value el texto que se dibuja
     * @param note la linea que lo explica
     * @param importe la misma cifra con su fecha; nulo si no es un importe
     */
    public record Kpi(
            String label, String value, String note, @Nullable ImporteActualizado importe) {

        static Kpi de(Indicador indicador) {
            return new Kpi(
                    indicador.concepto(),
                    indicador.cifra(),
                    indicador.nota(),
                    indicador.importe() == null
                            ? null
                            : new ImporteActualizado(
                                    indicador.importe(), indicador.actualizadoA()));
        }
    }

    /**
     * Un bloque de filas.
     *
     * @param title lo que agrupa
     * @param note contra que se miden sus barras
     * @param rows las filas
     */
    public record Bloque(String title, String note, List<Fila> rows) {

        static Bloque de(Cartera cartera) {
            return new Bloque(
                    cartera.titulo(),
                    cartera.nota(),
                    cartera.lineas().stream().map(Fila::de).toList());
        }
    }

    /**
     * Una fila de un bloque.
     *
     * <p>{@code pct} es lo que dibuja la barra y siempre es un numero, porque una barra sin numero
     * no se puede pintar. {@code avanceConocido} es lo que distingue el 0 que se midio del 0 que no
     * se pudo medir: con la base en cero no hay avance, y el {@code sub} lo dice con palabras. Sin
     * ese campo, «0 %» se leeria como «no se ha cobrado nada» en un tributo que ni siquiera tiene
     * cargos asentados.
     *
     * <p>{@code cargado} y {@code pendiente} son las dos cifras que el {@code sub} ya decia con
     * palabras (#549). El {@code sub} se queda —es lo que se lee— y los campos son lo que se
     * dibuja. Van <b>nulos</b> en las filas que no las tienen: el bloque «Recaudacion por mes»
     * agrupa por el mes del abono y no tiene cargado ni pendiente propios, y un cero ahi afirmaria
     * que ese mes cargo cero.
     *
     * @param label el tributo o el mes
     * @param sub contra que se mide esta fila
     * @param value el texto que se dibuja
     * @param pct la barra, de 0 a 100
     * @param avanceConocido si esa barra se pudo medir
     * @param importe la misma cifra con su fecha
     * @param cargado lo cargado de esta fila, con su fecha; nulo si la fila no agrupa cargos
     * @param pendiente el insoluto pendiente de esta fila, con su fecha; nulo si no agrupa cartera
     */
    public record Fila(
            String label,
            String sub,
            String value,
            int pct,
            boolean avanceConocido,
            @Nullable ImporteActualizado importe,
            @Nullable ImporteActualizado cargado,
            @Nullable ImporteActualizado pendiente) {

        static Fila de(LineaDeCartera linea) {
            return new Fila(
                    linea.concepto(),
                    linea.detalle(),
                    linea.cifra(),
                    linea.avance().orElse(0),
                    linea.avance().isPresent(),
                    conSuFecha(linea.importe(), linea.actualizadoA()),
                    conSuFecha(linea.cargado(), linea.actualizadoA()),
                    conSuFecha(linea.pendiente(), linea.actualizadoA()));
        }

        private static @Nullable ImporteActualizado conSuFecha(
                @Nullable Dinero importe, LocalDate actualizadoA) {
            return importe == null ? null : new ImporteActualizado(importe, actualizadoA);
        }
    }
}
