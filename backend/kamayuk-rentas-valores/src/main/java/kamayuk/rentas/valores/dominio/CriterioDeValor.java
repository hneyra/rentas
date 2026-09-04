package kamayuk.rentas.valores.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros de {@code valores_busqueda} (RF-092), ya resueltos: {@code contribuyenteId} llega
 * como identificador, no como {@code codContribuyente} — quien arma este criterio ya lo resolvio
 * con {@code DirectorioDeContribuyentes} (ARQ-01 §4 regla 2: este contexto no conoce a {@code
 * contribuyentes} mas alla de esa unica consulta).
 *
 * <p>Todos los campos son opcionales: sin ninguno, la busqueda es "todos los valores de la
 * municipalidad", que {@code ValorRepository} pagina igual que cualquier otra.
 */
public record CriterioDeValor(
        @Nullable String numero,
        @Nullable Long contribuyenteId,
        @Nullable TipoValor tipo,
        @Nullable Integer ejercicio) {}
