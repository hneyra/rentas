package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un giro del catalogo CIIU (#44, RF-112).
 *
 * <h2>Dato registrado, no siembra</h2>
 *
 * <p>La Clasificacion Industrial Internacional Uniforme la publica Naciones Unidas y la adapta el
 * INEI, asi que la tentacion es sembrarla como se siembran los catalogos territoriales. <b>No se
 * hace</b>, por dos motivos que van en la misma direccion:
 *
 * <ul>
 *   <li>La tabla es <b>por municipalidad</b> desde V4 ({@code municipalidad_id NOT NULL}, con su
 *       politica RLS), porque RF-112 exige que el usuario pueda extenderla —de ahi {@code
 *       extendido}—. Un catalogo compartido y extensible a la vez tendria que decidir que pasa
 *       cuando dos municipalidades extienden el mismo codigo con descripciones distintas.
 *   <li>La transcripcion de la CIIU rev. 4 es un trabajo de <b>datos normativos sin fuente
 *       verificada en este repositorio</b>, y el precedente del proyecto para eso es no
 *       inventarlos. Un catalogo de giros incompleto o mal transcrito produce licencias con el giro
 *       equivocado, y el giro decide la compatibilidad con la zonificacion y el nivel de riesgo de
 *       la ITSE.
 * </ul>
 *
 * <p>Asi que el sistema publica el <b>alta</b> —que es lo que RF-112 pide— y la carga inicial queda
 * como tarea de datos, igual que la de los valores unitarios.
 *
 * @param id nulo mientras no se haya guardado
 * @param codigo el codigo CIIU, normalizado a mayusculas
 * @param descripcion la actividad, como aparece en el papel
 * @param seccion la letra de seccion (A..U); nula si no se declaro
 * @param riesgoItse el nivel de riesgo de la ITSE; nulo si la municipalidad no lo clasifico
 * @param zonificacionCompatible las zonas del indice de usos donde el giro cabe; texto libre porque
 *     es ordenanza local (D-02b)
 * @param requiereSectorial si el giro necesita autorizacion de un sector distinto del municipal
 * @param extendido lo agrego la municipalidad, no venia en la clasificacion publicada
 * @param activo si se puede seguir eligiendo; retirarlo es darlo de baja, nunca borrarlo
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro
 * @param observacion por que se registro (regla 10)
 */
public record Ciiu(
        @Nullable Long id,
        String codigo,
        String descripcion,
        @Nullable String seccion,
        @Nullable RiesgoItse riesgoItse,
        @Nullable String zonificacionCompatible,
        boolean requiereSectorial,
        boolean extendido,
        boolean activo,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code ciiu.codigo varchar(10)} (V4). */
    public static final int CODIGO_MAXIMO = 10;

    /** {@code ciiu.descripcion varchar(300)} (V4). */
    public static final int DESCRIPCION_MAXIMO = 300;

    public Ciiu {
        Objects.requireNonNull(codigo, "Un giro necesita su codigo CIIU");
        Objects.requireNonNull(descripcion, "Un giro necesita su descripcion");
        Objects.requireNonNull(registradoEn, "El giro dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        descripcion = descripcion.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo CIIU va de 1 a " + CODIGO_MAXIMO + " caracteres: '" + codigo + "'");
        }
        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMO) {
            throw new IllegalArgumentException(
                    "La descripcion del giro va de 1 a " + DESCRIPCION_MAXIMO + " caracteres");
        }
        seccion = normalizarSeccion(seccion);
        if (zonificacionCompatible != null) {
            zonificacionCompatible = zonificacionCompatible.strip();
            if (zonificacionCompatible.isEmpty()) {
                zonificacionCompatible = null;
            }
        }
    }

    private static @Nullable String normalizarSeccion(@Nullable String seccion) {
        if (seccion == null) {
            return null;
        }
        String limpia = seccion.strip().toUpperCase(Locale.ROOT);
        if (limpia.isEmpty()) {
            return null;
        }
        if (limpia.length() != 1 || limpia.charAt(0) < 'A' || limpia.charAt(0) > 'U') {
            throw new IllegalArgumentException(
                    "La seccion CIIU es una letra de la A a la U: '" + seccion + "'");
        }
        return limpia;
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Un giro sin guardar no se puede autorizar");
        }
        return guardado;
    }
}
