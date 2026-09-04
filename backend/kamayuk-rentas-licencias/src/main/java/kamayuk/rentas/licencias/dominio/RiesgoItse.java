package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * El nivel de riesgo que el giro determina para la inspeccion tecnica de seguridad (#44, RF-112).
 *
 * <p>Es <b>dato del giro</b> y no una cifra normativa: lo que la norma fija es que del nivel de
 * riesgo depende si la ITSE es previa o posterior, y esa consecuencia no se calcula aqui. Lo que la
 * municipalidad declara —y por eso se registra, giro por giro— es en que nivel cae cada actividad.
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

    public static RiesgoItse porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
