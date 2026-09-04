package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code cuenta_corriente} (RF-040): el estado de cuenta de un contribuyente, con
 * filtros opcionales de ejercicio, tributo y fase.
 *
 * <p>Se busca por el <b>codigo</b> del contribuyente y no por su identificador: es lo que trae la
 * ruta de la pantalla —{@code GET /consultas/cuenta-corriente/{codigo}}— y lo que teclea quien
 * atiende en ventanilla. {@code cuentacorriente} no depende del contexto {@code contribuyentes}
 * para resolverlo: la consulta hace el cruce por SQL, contra la tabla con la que ya tiene clave
 * foranea (V2), sin conocer ningun tipo de ese contexto.
 */
public record CriterioDeConsulta(
        String codigoContribuyente,
        @Nullable Ejercicio ejercicio,
        @Nullable String tributo,
        @Nullable Fase fase) {

    public CriterioDeConsulta {
        Objects.requireNonNull(codigoContribuyente, "El estado de cuenta es de un contribuyente");
        codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
        if (codigoContribuyente.isEmpty()) {
            throw new IllegalArgumentException("El codigo de contribuyente no puede estar vacio");
        }
        if (tributo != null) {
            tributo = tributo.strip().toUpperCase(Locale.ROOT);
            if (tributo.isEmpty()) {
                tributo = null;
            }
        }
    }

    /** Sin mas filtro que el contribuyente: su libro completo, paginado. */
    public static CriterioDeConsulta delContribuyente(String codigo) {
        return new CriterioDeConsulta(codigo, null, null, null);
    }
}
