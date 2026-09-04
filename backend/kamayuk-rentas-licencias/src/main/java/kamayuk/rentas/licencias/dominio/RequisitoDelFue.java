package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un documento adjunto del FUE (#48, RF-113).
 *
 * <p><b>Que requisitos exige cada modalidad no esta aqui</b>, y es deliberado: eso lo fija el TUPA
 * de cada municipalidad —ordenanza local, D-02b—, y una lista compilada obligaria a recompilar por
 * cada instalacion. Lo que esta clase guarda es el nombre con que el TUPA lo llama y si se
 * presento.
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente al que pertenece
 * @param version la version de la seccion de documentos
 * @param requisito el nombre del documento, tal como el TUPA lo llama
 * @param presentado si el administrado lo adjunto
 * @param folios cuantos folios; opcional
 */
public record RequisitoDelFue(
        @Nullable Long id,
        long fueId,
        int version,
        String requisito,
        boolean presentado,
        @Nullable Integer folios) {

    /** {@code edificacion_requisito.requisito varchar(80)} (V43). */
    public static final int REQUISITO_MAXIMO = 80;

    public RequisitoDelFue {
        Objects.requireNonNull(requisito, "El documento adjunto tiene nombre");
        requisito = requisito.strip().toUpperCase(java.util.Locale.ROOT);

        if (requisito.isEmpty()) {
            throw new IllegalArgumentException("El nombre del documento no puede estar vacio");
        }
        if (requisito.length() > REQUISITO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre del documento excede los "
                            + REQUISITO_MAXIMO
                            + " caracteres de edificacion_requisito.requisito");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "La primera version de una seccion es la 1; llego " + version);
        }
        if (folios != null && folios <= 0) {
            throw new IllegalArgumentException("Un documento de cero folios no esta presentado");
        }
    }
}
