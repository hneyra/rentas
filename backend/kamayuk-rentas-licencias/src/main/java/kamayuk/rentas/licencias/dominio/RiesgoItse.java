package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * El nivel de riesgo que el giro determina para la inspeccion tecnica de seguridad (#44, RF-112).
 *
 * <p>Es <b>dato del giro</b> y no una cifra normativa: lo que la norma fija es que del nivel de
 * riesgo depende si la ITSE es previa o posterior, y esa consecuencia la dice {@link
 * #exigeItsePrevia()}. Lo que la municipalidad declara —y por eso se registra, giro por giro— es en
 * que nivel cae cada actividad.
 *
 * <p>Va <b>nulo</b> mientras no se declare, y {@code ciiu.riesgo_itse} lo admite: un valor por
 * omision decidiria por descuido el momento de la inspeccion de todos los giros que nadie
 * clasifico.
 */
public enum RiesgoItse {
    BAJO,
    MEDIO,
    ALTO,
    MUY_ALTO;

    /**
     * Si la licencia de un giro de este nivel se tramita con la ITSE <b>previa</b> (#416).
     *
     * <p>ALTO y MUY ALTO si; BAJO y MEDIO no, porque su inspeccion es posterior a la licencia. Es
     * lo que el TUPA del corpus lista —«Con ITSE previa» para las edificaciones de riesgo ALTO y
     * MUY ALTO ({@code normativa}, {@code
     * derecho-tramite-catacaos-2023-rentas-licencias-y-publicidad.md:78-79})— y no una cifra: es
     * una clasificacion, asi que no viaja en el conjunto sellado (regla 5 habla de importes y
     * tramos). Quien la lee es {@code ComprobacionDelTerritorio.faltaLaItsePrevia()}.
     */
    public boolean exigeItsePrevia() {
        return this == ALTO || this == MUY_ALTO;
    }

    public static RiesgoItse porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
