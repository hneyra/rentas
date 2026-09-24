package kamayuk.rentas.nucleo.dominio.vehicular;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * La base imponible del impuesto vehicular y de donde salio (TUO LTM art. 32; #330).
 *
 * <p>«La base imponible esta constituida por el valor original de adquisicion, importacion o de
 * ingreso al patrimonio, el que en ningun caso sera menor a la tabla referencial». Hasta #330 esta
 * regla no vivia en ninguna parte: la capa de aplicacion pasaba el valor de la tabla a una funcion
 * cuyo parametro se llamaba {@code valorReferencial}, y el valor de adquisicion —la columna
 * existia— no lo leia nadie. Un vehiculo comprado por encima de la tabla pagaba sobre la tabla.
 *
 * <p>Funcion pura (regla 6): los dos operandos entran como argumento; de donde sale cada uno lo
 * deciden quien llama ({@link AdquisicionDelPropietario}) y la tabla del conjunto sellado.
 *
 * @param valor la base con que se calcula el impuesto
 * @param origen de cual de los dos operandos salio
 */
public record BaseImponibleVehicular(Dinero valor, OrigenDeLaBase origen) {

    public BaseImponibleVehicular {
        Objects.requireNonNull(valor, "La base imponible tiene valor");
        Objects.requireNonNull(origen, "La base imponible dice de donde salio");
    }

    /**
     * El mayor entre el valor de adquisicion y la tabla; con la adquisicion ausente, la tabla, y
     * dicho asi.
     *
     * @param adquisicion el valor de adquisicion del propietario del ejercicio; nulo si no se
     *     capturo
     * @param tabla el valor referencial del conjunto sellado del ejercicio
     */
    public static BaseImponibleVehicular segunArticulo32(
            @Nullable Dinero adquisicion, Dinero tabla) {
        Objects.requireNonNull(tabla, "El art. 32 necesita el valor de la tabla");
        if (adquisicion == null) {
            return new BaseImponibleVehicular(tabla, OrigenDeLaBase.TABLA_SIN_ADQUISICION);
        }
        return adquisicion.esMayorQue(tabla)
                ? new BaseImponibleVehicular(adquisicion, OrigenDeLaBase.ADQUISICION)
                : new BaseImponibleVehicular(tabla, OrigenDeLaBase.TABLA);
    }
}
