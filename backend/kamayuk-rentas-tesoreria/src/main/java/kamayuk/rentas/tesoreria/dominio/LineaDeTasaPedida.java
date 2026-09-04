package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * Lo que el cajero marca en caja de tasas: <b>que</b> concepto del TUPA y <b>cuantas</b> veces.
 *
 * <p>El precio no viaja aqui. Sale de la tabla {@code tasa}, que es dato registrado con su vigencia
 * y su documento fuente (regla 5): un importe que llegara desde la peticion seria una tarifa
 * decidida por el cliente, y un importe compilado en el codigo seria una tarifa que solo se cambia
 * desplegando.
 *
 * @param codigoDeTasa el codigo del concepto del TUPA
 * @param cantidad cuantas veces se cobra; al menos 1
 */
public record LineaDeTasaPedida(String codigoDeTasa, int cantidad) {

    public LineaDeTasaPedida {
        Objects.requireNonNull(codigoDeTasa, "La linea necesita el codigo de la tasa");
        codigoDeTasa = codigoDeTasa.strip().toUpperCase(Locale.ROOT);
        if (codigoDeTasa.isEmpty()) {
            throw new IllegalArgumentException("El codigo de la tasa no puede estar vacio");
        }
        if (cantidad <= 0) {
            throw new IllegalArgumentException(
                    "La cantidad de un concepto del TUPA es al menos 1; llego " + cantidad);
        }
    }
}
