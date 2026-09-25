package kamayuk.rentas.cuentacorriente;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * La clave con la que otro contexto cruza sus obligaciones con las del libro: tributo, ejercicio y
 * unidad (#407, #426).
 *
 * <p>Son los cuatro campos con que {@code cuentacorriente} agrupa sus asientos en una fila de
 * {@link ConsultaDeDeudaPublica}, sin el titular —que quien cruza ya tiene fijado— y sin el periodo
 * —que la fila agrega—. El tributo se normaliza aqui, una vez, a mayusculas y sin blancos: es lo
 * que hacen {@code ClaveDeObligacion} y {@link SeleccionDeObligacion} al construirse, y una clave
 * que distinguiera «predial» de «PREDIAL» cruzaria una obligacion consigo misma como dos.
 *
 * <p><b>Por que es un tipo y no cuatro campos comparados a mano.</b> Hasta #407 habia tres copias
 * del cruce: {@code ConsultaDeExpedientes} con su propio record normalizado a mayusculas (el que
 * corrigio #426), {@code RegistrarValor.buscar} con {@code equalsIgnoreCase}, y la importacion a
 * coactiva con otro filtro de cuatro campos —y una deduplicacion que, sobre el record crudo,
 * distinguia mayusculas y ya no coincidia con la busqueda del mismo archivo—. Tres copias de una
 * clave son tres sitios donde la proxima columna se olvida en uno.
 *
 * <p><b>Por que no es {@link SeleccionDeObligacion}.</b> Aquella es lo que alguien <i>marca</i>, y
 * rechaza al construirse una obligacion de un predio <b>y</b> de un vehiculo: una seleccion mal
 * formada es un error de quien la manda. Una clave tiene que poder nombrar cualquier fila que el
 * libro devuelva —{@code cuenta_corriente_asiento} no tiene esa restriccion—, porque un cruce que
 * lanzara sobre una fila rara convertiria en 500 la deuda entera del expediente.
 *
 * @param tributo el tributo, en mayusculas y sin blancos alrededor
 * @param ejercicio el ejercicio de la obligacion
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 */
public record ClaveDeObligacionPublica(
        String tributo, Ejercicio ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

    public ClaveDeObligacionPublica {
        Objects.requireNonNull(tributo, "La clave necesita el tributo de la obligacion");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo de la clave no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La clave necesita el ejercicio de la obligacion");
    }
}
