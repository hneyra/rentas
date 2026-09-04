package kamayuk.rentas.valores.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Que obligacion, entre todas las que tiene un contribuyente, entra en un valor.
 *
 * <p>Quien pide el valor —la pantalla de generacion individual— ya sabe que predio o vehiculo le
 * interesa: este selector es lo que {@code RegistrarValor} cruza contra {@code
 * ConsultaDeDeudaPublica.deTodoElContribuyente} para decidir que congelar. No es {@link
 * ValorDetalle}: no trae importe, porque el importe lo decide la consulta de deuda, no quien pide
 * el valor (regla 5: ninguna cifra tributaria nace en una peticion).
 *
 * @param tributo el tributo de la obligacion
 * @param ejercicio el ejercicio de la obligacion
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 */
public record SelectorDeObligacion(
        String tributo, Ejercicio ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

    public SelectorDeObligacion {
        Objects.requireNonNull(tributo, "El selector necesita el tributo de la obligacion");
        tributo = tributo.strip().toUpperCase(java.util.Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo del selector no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "El selector necesita el ejercicio de la obligacion");
        if (predioId != null && vehiculoId != null) {
            throw new IllegalArgumentException(
                    "Una obligacion es predial o vehicular, nunca las dos");
        }
    }
}
