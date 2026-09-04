package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El estado de cuenta de fiscalización tal como sale por HTTP ({@code fisc_estado_cuenta}, RF-056).
 *
 * <h2>Toda cifra viaja con su fecha</h2>
 *
 * <p>Regla 9 y RNF-075. La deuda de cada línea es un {@link ImporteActualizado}, que lleva el
 * importe y el día al que corresponde en el mismo objeto: no existe «la deuda», existe la deuda
 * actualizada a una fecha. Y cuando el libro todavía no tiene nada de esa obligación —lo que hoy es
 * siempre, porque la transferencia a rentas es #52 y el importe es #198— la línea sale <b>sin</b>
 * importe en vez de con un cero: un cero se lee como «no debe nada».
 *
 * <p>{@code total} es {@code null} si alguna línea no tiene cifra. Un total parcial presentado como
 * total es peor que ningún total, porque nadie lo distingue del completo.
 *
 * <h2>Quien nunca fue fiscalizado no debe cero: no debe nada que esta pantalla sepa</h2>
 *
 * <p>Hasta #546 los dos casos salían iguales —{@code "total":{"importe":"0"}}—, y son cosas
 * distintas: a quien nunca se le abrió una liquidación no se le ha determinado nada, y a quien sí y
 * ya pagó se le determinó y su saldo es cero. El cero de la lista vacía es el que este propio
 * javadoc dice de sí mismo que hay que evitar.
 *
 * <p>Lo que los separa es {@code fiscalizado}, y no el {@code total}: {@code total} ya viaja en
 * {@code null} —hoy, siempre— cuando alguna línea no tiene cifra, así que un nulo solo no dice cuál
 * de las dos cosas pasa. Con la marca, {@code fiscalizado: false} con {@code lineas: []} es «no hay
 * procedimiento», y {@code fiscalizado: true} con {@code total} en cero es «lo hubo y no queda
 * saldo».
 *
 * @param codContribuyente el código del fiscalizado
 * @param fechaDeConsulta el día al que están todas las cifras
 * @param fiscalizado si alguna vez se le abrió una liquidación de fiscalización
 * @param lineas una por obligación fiscalizada
 * @param total la suma, si se le fiscalizó y todas las líneas tienen cifra
 */
public record EstadoDeCuentaResource(
        String codContribuyente,
        String fechaDeConsulta,
        boolean fiscalizado,
        List<LineaResource> lineas,
        @Nullable ImporteActualizado total) {

    public static EstadoDeCuentaResource de(
            EstadoDeCuentaDeFiscalizacion.EstadoDeCuenta estado, String codContribuyente) {
        List<LineaResource> lineas = new ArrayList<>();
        for (EstadoDeCuentaDeFiscalizacion.LineaDelEstadoDeCuenta linea : estado.lineas()) {
            lineas.add(LineaResource.de(linea));
        }
        return new EstadoDeCuentaResource(
                codContribuyente,
                estado.aLaFecha().toString(),
                estado.fiscalizado(),
                List.copyOf(lineas),
                estado.total() == null
                        ? null
                        : new ImporteActualizado(estado.total(), estado.aLaFecha()));
    }

    /**
     * Una obligación originada en fiscalización.
     *
     * @param deuda el número de la liquidación de la que viene
     * @param ano el ejercicio fiscalizado
     * @param nomTrib el tributo
     * @param unidad el predio o el vehículo
     * @param estad la condición del contraste
     * @param importe cuánto se debe y a qué fecha; {@code null} mientras el libro no tenga nada
     */
    public record LineaResource(
            String deuda,
            int ano,
            String nomTrib,
            @Nullable Long unidad,
            String estad,
            @Nullable ImporteActualizado importe) {

        static LineaResource de(EstadoDeCuentaDeFiscalizacion.LineaDelEstadoDeCuenta linea) {
            return new LineaResource(
                    linea.numeroLiquidacion(),
                    linea.ejercicio().valor(),
                    linea.tributo(),
                    linea.predioId() != null ? linea.predioId() : linea.vehiculoId(),
                    linea.condicion(),
                    linea.deuda() == null
                            ? null
                            : new ImporteActualizado(linea.deuda(), linea.aLaFecha()));
        }
    }
}
