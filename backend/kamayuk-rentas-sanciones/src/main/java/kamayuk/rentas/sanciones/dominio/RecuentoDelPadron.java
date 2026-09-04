package kamayuk.rentas.sanciones.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Cuantas papeletas cumplen un criterio y cuanto suman, sin traerse ninguna (#549).
 *
 * <p>Lo que devuelve {@link PadronDePapeletasRepository#contar}, y la razon de que exista: la
 * pantalla de aterrizaje necesita el recuento del padron y <b>no</b> sus filas. Con {@code
 * buscar(...).totalElementos()} la cifra saldria igual y el motor tendria que componer ademas una
 * pagina de papeletas que nadie va a dibujar.
 *
 * @param cuantas el recuento
 * @param importe la suma de {@code importe_a_pagar} de esas mismas filas; cero si no hay ninguna
 */
public record RecuentoDelPadron(long cuantas, Dinero importe) {

    public RecuentoDelPadron {
        if (cuantas < 0) {
            throw new IllegalArgumentException("Un recuento no es negativo: " + cuantas);
        }
        Objects.requireNonNull(importe, "La suma de nada es cero, no ausente");
    }
}
