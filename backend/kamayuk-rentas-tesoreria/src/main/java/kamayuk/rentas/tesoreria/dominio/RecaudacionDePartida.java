package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Lo recaudado por area generadora, partida presupuestal y tributo (#36, RF-089).
 *
 * <h2>El area y la partida faltan en lo tributario, y no se inventan</h2>
 *
 * <p>Este es el hueco de datos de RF-089 y conviene leerlo entero antes de «arreglarlo».
 *
 * <p>Una linea de <b>caja de tasas</b> apunta a su {@code tasa}, y la tasa si tiene las dos cosas:
 * {@code tasa.area_id} —la unidad organica que presta el servicio— y {@code
 * tasa.partida_presupuestal} (V3). Para esas lineas, {@link #areaCodigo} y {@link
 * #partidaPresupuestal} vienen llenos.
 *
 * <p>Una linea <b>tributaria</b> —predial, arbitrios, vehicular— no tiene ninguna de las dos.
 * {@code recibo_detalle} guarda el tributo, el ejercicio y la unidad, y no hay en todo el esquema
 * ninguna tabla que diga a que partida presupuestal se imputa el impuesto predial. Las dos salen
 * nulas, y el reporte lo dice: no se rellenan con la partida de la caja, ni con una constante, ni
 * con «VARIOS». Una partida inventada acaba en un reporte a la gerencia de administracion, y de ahi
 * en una conciliacion presupuestal que no cuadra con la contabilidad.
 *
 * <p><b>El area de la caja no vale como sustituto.</b> {@code caja.area_id} dice donde se cobro
 * —que ventanilla—, no quien genero el ingreso, y la pantalla pide «unidad organica generadora».
 * Usar una por otra daria una cifra plausible atribuida a quien no corresponde.
 *
 * @param areaCodigo el codigo del area generadora; nulo en lo tributario
 * @param areaNombre su nombre; nulo en lo tributario
 * @param partidaPresupuestal la partida a la que se imputa; nula en lo tributario
 * @param tributo el tributo, o el codigo de la tasa
 * @param cobrado lo que las lineas de ese grupo sumaron, anuladas incluidas
 * @param anulado lo que de eso pertenecia a recibos que se anularon
 */
public record RecaudacionDePartida(
        @Nullable String areaCodigo,
        @Nullable String areaNombre,
        @Nullable String partidaPresupuestal,
        String tributo,
        Dinero cobrado,
        Dinero anulado) {

    public RecaudacionDePartida {
        Objects.requireNonNull(tributo, "La fila es de un tributo o de una tasa");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(cobrado, "La fila trae lo cobrado");
        Objects.requireNonNull(anulado, "La fila trae lo anulado");
        if (cobrado.esNegativo() || anulado.esNegativo()) {
            throw new IllegalArgumentException("La recaudacion no se cuenta en negativo");
        }
    }

    /** Lo que de verdad quedo recaudado en este grupo. */
    public Dinero neto() {
        return cobrado.menos(anulado);
    }

    /**
     * Si esta fila puede imputarse a una partida presupuestal.
     *
     * <p>Falso en todo lo tributario, y eso es un hueco de datos —no un cero—: ver la cabecera de
     * esta clase.
     */
    public boolean tienePartida() {
        return partidaPresupuestal != null;
    }
}
