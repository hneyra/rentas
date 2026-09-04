package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una obligación del expediente coactivo, <b>por separado y a una fecha</b> (#426).
 *
 * <h2>Por qué existe, si ya está {@link DeudaDelExpediente}</h2>
 *
 * <p>{@link DeudaDelExpediente} es la <b>suma</b>: las cinco cifras del procedimiento entero, que
 * es lo que la REC-2 imprime y lo que la grilla de expedientes pinta. Fraccionar en coactiva no se
 * hace sobre una suma: {@code PeticionDeConvenioCoactivo.obligaciones[]} pide {@code tributo},
 * {@code ejercicio} y {@code predioId}/{@code vehiculoId} <b>fila a fila</b>, y sin una lectura con
 * esa granularidad la pantalla no tiene de dónde sacarlos —exactamente como estaba {@code
 * baja_deuda} antes de #332, que los toma de {@code consulta_deuda}—.
 *
 * <p>No es una segunda verdad sobre la misma cifra: sale de la <b>misma</b> composición y a la
 * misma fecha que {@code DeudaDelExpediente}, que se calcula sumando precisamente estas filas. Dos
 * lecturas que sumaran por su cuenta acabarían sumando distinto (RNF-083).
 *
 * @param tributo el tributo de la obligación, como lo escribe el libro
 * @param ejercicio el ejercicio
 * @param predioId la unidad, si la obligación es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligación es vehicular
 * @param insoluto el tributo debido, sin reajuste ni interés
 * @param reajuste el ajuste de cuotas por el índice vigente
 * @param interes el interés moratorio
 * @param gasto los gastos administrativos y de cobranza asentados
 * @param esCosta si esta obligación es una <b>costa del procedimiento</b> (#42) y no deuda materia
 *     de cobranza. Viaja marcada y no se esconde: la costa se cobra igual, pero no se acoge a un
 *     fraccionamiento como una cuota más del predial, y quien lee la grilla tiene que poder
 *     distinguirla
 * @param actualizadaA el día al que corresponden las cuatro cifras (regla 9, RNF-075)
 */
public record ObligacionDelExpediente(
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto,
        boolean esCosta,
        LocalDate actualizadaA) {

    public ObligacionDelExpediente {
        Objects.requireNonNull(tributo, "La obligacion necesita su tributo");
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(
                actualizadaA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }

    /** La suma de las cuatro partes, nunca una quinta cifra guardada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
