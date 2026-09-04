package kamayuk.rentas.coactiva.dominio;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que produjo una importacion de valores a coactiva (#40, RF-100).
 *
 * <p>Mismo patron que el informe de los importadores de catastro: se procesa fila a fila, se
 * rechaza la fila y no el lote, y el informe dice <b>que</b> entro y <b>por que</b> no entro el
 * resto. Fallar la peticion entera porque uno de los siete valores tenia el plazo corriendo
 * obligaria a quien opera a descubrir cual, a mano, quitandolos de uno en uno.
 *
 * <p><b>Un informe sin ningun valor importado no abre expediente.</b> {@link #expediente} es nulo
 * en ese caso, y no es un descuido: un expediente coactivo vacio es un procedimiento sin deuda que
 * seguir, y el numero que consumiria dejaria un hueco en el correlativo del ejercicio que nadie
 * podria explicar.
 *
 * @param expediente el expediente abierto; nulo si no se admitio ningun valor
 * @param importados los valores que entraron
 * @param rechazados los que no, cada uno con su motivo
 */
public record InformeDeImportacion(
        @Nullable ExpedienteCoactivo expediente,
        List<ValorDelExpediente> importados,
        List<ValorRechazado> rechazados) {

    public InformeDeImportacion {
        importados = List.copyOf(Objects.requireNonNull(importados));
        rechazados = List.copyOf(Objects.requireNonNull(rechazados));
        if (expediente == null && !importados.isEmpty()) {
            throw new IllegalArgumentException(
                    "Si algun valor entro, entro en un expediente: no hay valores importados sin"
                            + " carpeta que los agrupe");
        }
        if (expediente != null && importados.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un expediente coactivo vacio es un procedimiento sin deuda que seguir, y su"
                            + " numero seria un hueco en el correlativo del ejercicio");
        }
    }

    /** Nada entro: todos los valores pedidos se rechazaron. */
    public static InformeDeImportacion sinNadaQueImportar(List<ValorRechazado> rechazados) {
        return new InformeDeImportacion(null, List.of(), rechazados);
    }

    /** El expediente abierto, exigiendo que algo se importara. */
    public ExpedienteCoactivo expedienteAbierto() {
        return Objects.requireNonNull(
                expediente, "No se abrio expediente: ningun valor fue admitido");
    }

    public boolean abrioExpediente() {
        return expediente != null;
    }
}
