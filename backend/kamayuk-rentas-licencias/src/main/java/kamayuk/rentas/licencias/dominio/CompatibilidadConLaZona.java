package kamayuk.rentas.licencias.dominio;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Si el giro cabe en la zona en que el territorio dice que esta el predio (#43, AC-2).
 *
 * <h2>Funcion pura, y por eso vive en el dominio</h2>
 *
 * <p>Recibe el codigo de zona que {@code catastro} contesto y el texto de {@code
 * ciiu.zonificacion_compatible}, y devuelve un veredicto. Sin base de datos, sin reloj y sin
 * configuracion global (regla 6): la misma pareja tiene que dar el mismo veredicto dentro de diez
 * anos, porque de el cuelga la motivacion de un acto administrativo que se puede impugnar.
 *
 * <h2>Por que hay TRES desenlaces y no dos</h2>
 *
 * <p>{@code zonificacion_compatible} es <b>texto libre</b> —lo dice el javadoc de {@link Ciiu}, y
 * es asi porque el indice de usos es ordenanza local (D-02b)—. Que la municipalidad no lo haya
 * rellenado para un giro no significa que el giro no quepa en ninguna zona: significa que nadie lo
 * clasifico. Colapsar eso en {@link #INCOMPATIBLE} negaria licencias que la ordenanza permite, y
 * colapsarlo en {@link #COMPATIBLE} las autorizaria todas — las dos equivocaciones son de un acto
 * administrativo y ninguna avisa. Por eso existe {@link #NO_SE_PUEDE_DECIDIR}, que quien emite
 * tiene que resolver a mano y por escrito.
 *
 * <h2>Como se comparan</h2>
 *
 * <p>El texto se parte por comas, puntos y comas, barras y espacios, y se compara <b>codigo a
 * codigo</b> en mayusculas. No se compara por prefijo ni por «contiene»: {@code RDM} esta contenido
 * en {@code RDMA} y en {@code CZ-RDM}, y una licencia autorizada porque una zona es subcadena de
 * otra es exactamente el tipo de acierto por casualidad que este proyecto no admite. El vocabulario
 * de los dos lados <b>no esta normalizado todavia</b> y esta guarda no lo normaliza: lo que hace es
 * no adivinar cuando no coincide.
 */
public enum CompatibilidadConLaZona {

    /** La zona del territorio esta entre las que el giro declara. */
    COMPATIBLE,

    /** La zona del territorio NO esta entre las que el giro declara, y el giro declara alguna. */
    INCOMPATIBLE,

    /**
     * No hay con que decidir: el giro no declara zonas compatibles, o {@code catastro} no contesto
     * una zona. No es «compatible» ni «incompatible», y tratarlo como cualquiera de las dos es
     * decidir en silencio.
     */
    NO_SE_PUEDE_DECIDIR;

    /**
     * El veredicto para esa zona y ese giro.
     *
     * @param zonaDelTerritorio el codigo que {@code catastro} contesto; {@code null} si no contesto
     * @param zonasCompatibles el texto libre de {@code ciiu.zonificacion_compatible}
     */
    public static CompatibilidadConLaZona evaluar(
            @Nullable String zonaDelTerritorio, @Nullable String zonasCompatibles) {
        Set<String> declaradas = codigosDe(zonasCompatibles);
        if (zonaDelTerritorio == null || zonaDelTerritorio.isBlank() || declaradas.isEmpty()) {
            return NO_SE_PUEDE_DECIDIR;
        }
        return declaradas.contains(zonaDelTerritorio.strip().toUpperCase(Locale.ROOT))
                ? COMPATIBLE
                : INCOMPATIBLE;
    }

    /** Los codigos de zona que el texto libre nombra, normalizados. */
    public static Set<String> codigosDe(@Nullable String zonasCompatibles) {
        Set<String> codigos = new LinkedHashSet<>();
        if (zonasCompatibles == null) {
            return codigos;
        }
        for (String trozo : zonasCompatibles.toUpperCase(Locale.ROOT).split("[,;/\\s]+")) {
            String limpio = trozo.strip();
            if (!limpio.isEmpty()) {
                codigos.add(limpio);
            }
        }
        return codigos;
    }
}
