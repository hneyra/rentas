package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que otro contexto necesita saber de una obligacion con deuda, via {@link
 * ConsultaDeDeudaPublica}.
 *
 * <p>Trae el desglose completo —insoluto, reajuste, interes, gasto—, el mismo que {@code
 * DeudaActualizada}: un consumidor que solo necesita el total lo pide con {@link #total()}, pero
 * uno que tiene que <b>formalizar</b> la deuda en un documento —{@code valores}, #37— necesita las
 * cuatro partes por separado, porque eso es lo que exige poder explicar la cifra sin volver a
 * consultar el libro. Traer solo el total habria obligado a {@code valores} a inventarse su propio
 * desglose, o a guardar un importe que no se puede desglosar despues.
 *
 * <h2>La fase viaja, como texto (#403)</h2>
 *
 * <p>{@link #fase()} es la que {@code ConsultarDeuda} ya calculaba para {@code ObligacionConDeuda}
 * —la mas avanzada entre los periodos de la obligacion— y que este record descartaba al cruzar la
 * frontera. Sin ella, un consumidor que tiene que distinguir lo exigible de lo acogido no tiene con
 * que hacerlo: acogerse a un convenio <b>no cambia el total</b> —el par {@code FRACCIONAMIENTO} no
 * es ninguna de las cuatro partes—, asi que la deuda acogida y la exigible son la misma cifra y
 * solo la fase las separa. {@code coactiva} sumaba las dos como exigibles y dejaba dictar una REC-2
 * sobre deuda fraccionada.
 *
 * <p>Viaja como <b>texto</b> y no como {@code dominio.Fase}: es la misma decision que {@code
 * DeudaAcogida.faseOrigen} y {@code MovimientoDeFase}, porque el enum es del dominio de este
 * contexto y la frontera no lo exporta. Lo que si exporta es la pregunta que todos los consumidores
 * se hacen —{@link #acogidaAConvenio()}—, para que el literal {@code "CONVENIO"} viva en el dueño
 * de la fase y no copiado en cada modulo que la lee.
 *
 * @param tributo el tributo de la obligacion
 * @param ejercicio el ejercicio
 * @param predioId la unidad, si la obligacion es predial
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param fecha la fecha de corte con la que se calculo el desglose (regla 9, RNF-075)
 * @param insoluto el tributo determinado, sin reajuste ni interes
 * @param reajuste el ajuste de cuotas por el indice vigente
 * @param interes el interes moratorio
 * @param gasto los gastos administrativos y de cobranza asentados
 * @param fase en que etapa de la cobranza esta: {@code ORDINARIA}, {@code VALOR}, {@code COACTIVA}
 *     o {@code CONVENIO}, la mas avanzada entre sus periodos (#403)
 */
public record ObligacionPublica(
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        LocalDate fecha,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto,
        String fase) {

    /** La fase de la deuda acogida a un convenio, tal como {@code dominio.Fase} la nombra. */
    public static final String FASE_DE_CONVENIO = "CONVENIO";

    public ObligacionPublica {
        Objects.requireNonNull(tributo, "La obligacion necesita su tributo");
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
        Objects.requireNonNull(fecha, "Toda cifra de deuda indica su fecha de calculo (RNF-075)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(fase, "La obligacion dice en que fase esta (#403)");
        if (fase.isBlank()) {
            throw new IllegalArgumentException(
                    "La fase de la obligacion no puede ir en blanco: es lo que separa lo exigible"
                            + " de lo acogido (#403)");
        }
    }

    /**
     * Si la deuda esta acogida a un convenio (#403).
     *
     * <p>Acogida no es pagada: sigue en el libro con el mismo total, y el convenio la cobra en
     * cuotas. Lo que deja de ser es <b>exigible</b> por otra via mientras el convenio viva —un
     * embargo sobre deuda fraccionada cobraria dos veces lo mismo—. Si el convenio se quiebra, la
     * deuda vuelve a su fase de origen y esta pregunta vuelve a contestar que no.
     */
    public boolean acogidaAConvenio() {
        return FASE_DE_CONVENIO.equals(fase);
    }

    /** La suma de las cuatro partes, nunca una quinta cifra calculada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }

    /** Con que clave se cruza esta fila con las obligaciones de otro contexto (#407). */
    public ClaveDeObligacionPublica clave() {
        return new ClaveDeObligacionPublica(tributo, ejercicio, predioId, vehiculoId);
    }
}
