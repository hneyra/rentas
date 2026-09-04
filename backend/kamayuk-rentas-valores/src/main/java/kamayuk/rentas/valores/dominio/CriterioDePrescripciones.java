package kamayuk.rentas.valores.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros de la relacion de prescripciones declaradas (#674, RF-094), ya resueltos.
 *
 * <p>{@code contribuyenteId} llega como identificador y no como {@code codContribuyente}: quien
 * arma este criterio ya lo resolvio contra {@code DirectorioDeContribuyentes}, igual que {@link
 * CriterioDeValor} (ARQ-01 §4 regla 2).
 *
 * <p><b>{@code ejercicio} acota por el rango solicitado, no por lo que prescribio</b>, y eso es
 * deliberado. Una declaracion pide un rango y se resuelve ejercicio por ejercicio: filtrar por «los
 * que prescribieron» esconderia las que salieron {@link ResultadoDeLaSolicitud#NO_PROCEDE}, que son
 * justamente las que dicen que ese ejercicio <b>sigue siendo exigible</b> —y quien audita necesita
 * las dos respuestas, no solo la que le quita deuda a alguien—. Cuales prescribieron lo dice cada
 * fila en {@link PrescripcionEnLista#ejerciciosPrescritos}.
 *
 * <p>Todos los campos son opcionales: sin ninguno, la relacion es «todas las declaraciones de esta
 * municipalidad».
 */
public record CriterioDePrescripciones(
        @Nullable Long contribuyenteId,
        @Nullable String tributo,
        @Nullable Integer ejercicio,
        @Nullable ResultadoDeLaSolicitud resultado) {}
