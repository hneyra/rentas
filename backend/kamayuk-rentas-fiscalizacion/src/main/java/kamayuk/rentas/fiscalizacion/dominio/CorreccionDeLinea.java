package kamayuk.rentas.fiscalizacion.dominio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que una reliquidación corrige de una línea (#49, AC 2). Lo que llega {@code null} se conserva
 * de la versión anterior: una corrección parcial no borra lo que no nombra.
 *
 * <p>Hasta #340 vivía dentro de {@code ReliquidarFiscalizacion}, y la corrección la aplicaba el
 * caso de uso reinventando los lados de la comparación a partir de los nulos de la línea. Ahora la
 * aplica la propia línea —{@link LineaDeLiquidacion#corregidaCon}—, y por eso esto vive en el
 * dominio: el dominio no puede nombrar un tipo de la capa de aplicación.
 *
 * <p>Un uso en blanco es un uso ausente, igual que en {@link LineaDeLiquidacion}: un formulario que
 * manda una fila por ejercicio con cadenas vacías no corrige nada, y así lo lee {@link
 * #camposQueCorrige()}.
 *
 * @param ejercicio qué línea se corrige
 * @param areaDeclarada la superficie declarada corregida
 * @param areaHallada la superficie hallada corregida
 * @param usoDeclarado el uso declarado corregido
 * @param usoHallado el uso hallado corregido
 */
public record CorreccionDeLinea(
        Ejercicio ejercicio,
        @Nullable AreaM2 areaDeclarada,
        @Nullable AreaM2 areaHallada,
        @Nullable String usoDeclarado,
        @Nullable String usoHallado) {

    public CorreccionDeLinea {
        Objects.requireNonNull(ejercicio, "Una correccion dice que ejercicio corrige");
        usoDeclarado = enBlancoAnulo(usoDeclarado);
        usoHallado = enBlancoAnulo(usoHallado);
    }

    /** Si trae algo de lo declarado: es lo que convierte en declarante a quien no lo era. */
    public boolean corrigeLoDeclarado() {
        return areaDeclarada != null || usoDeclarado != null;
    }

    /** Si trae algo de lo hallado: lo que un predio no ubicado no tiene que corregir. */
    public boolean corrigeLoHallado() {
        return areaHallada != null || usoHallado != null;
    }

    /**
     * Los nombres de los campos que trae, en el orden de la petición, para nombrarlos al rechazar.
     */
    public List<String> camposQueCorrige() {
        List<String> campos = new ArrayList<>();
        if (areaDeclarada != null) {
            campos.add("areaDeclarada");
        }
        if (areaHallada != null) {
            campos.add("areaHallada");
        }
        if (usoDeclarado != null) {
            campos.add("usoDeclarado");
        }
        if (usoHallado != null) {
            campos.add("usoHallado");
        }
        return List.copyOf(campos);
    }

    private static @Nullable String enBlancoAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
