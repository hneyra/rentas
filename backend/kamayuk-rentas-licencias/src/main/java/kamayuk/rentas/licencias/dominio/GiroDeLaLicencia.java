package kamayuk.rentas.licencias.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Un giro autorizado en una licencia (#44, RF-110).
 *
 * <p>Una licencia autoriza <b>varios</b> giros y exactamente <b>uno principal</b>: es la actividad
 * principal la que decide el nivel de riesgo de la ITSE y la compatibilidad con la zonificacion.
 * Que sea uno solo lo garantiza {@code licencia_giro_principal_uq} (V37), un indice unico parcial y
 * no un {@code if}: dos peticiones simultaneas pasan las dos por cualquier comprobacion escrita en
 * Java.
 *
 * <p>Lleva el codigo y la descripcion del giro <b>ademas</b> de su identificador porque quien pinta
 * la ficha de la licencia los necesita, y traerlos con la propia consulta evita una lectura por
 * giro. No son copia congelada: se leen de {@code ciiu} en cada consulta.
 *
 * @param ciiuId el giro del catalogo
 * @param codigo su codigo CIIU
 * @param descripcion la actividad
 * @param principal si es la actividad principal de la licencia
 * @param activo si sigue autorizado; quitarlo es darlo de baja, nunca borrarlo (V7)
 */
public record GiroDeLaLicencia(
        long ciiuId,
        @Nullable String codigo,
        @Nullable String descripcion,
        boolean principal,
        boolean activo) {

    public GiroDeLaLicencia {
        if (ciiuId <= 0) {
            throw new IllegalArgumentException("Un giro autorizado apunta a un CIIU concreto");
        }
    }
}
