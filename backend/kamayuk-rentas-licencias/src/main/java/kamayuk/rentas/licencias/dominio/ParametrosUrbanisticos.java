package kamayuk.rentas.licencias.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los parametros urbanisticos que un certificado consigna, copiados el dia en que se certifico
 * (#54, RF-115).
 *
 * <h2>Por que son texto y no cifras</h2>
 *
 * <p>Los fija el <b>Plan de Desarrollo Urbano</b> de cada municipalidad, y cada uno los escribe a
 * su manera: la altura maxima puede decir «3 pisos», «10.50 m» o «1.5 (a+r)»; el area libre minima,
 * «30 %» o «no exigible en RDM». Convertirlos a numero aqui obligaria a interpretar una norma local
 * que este repositorio no tiene, y la primera interpretacion equivocada saldria impresa en un
 * certificado que alguien presenta ante un notario.
 *
 * <p>No son, por tanto, una cifra normativa compilada (regla 5): son <b>lo que el operador
 * transcribio del plano de zonificacion</b> ese dia, y el certificado los guarda tal cual para
 * poder reimprimirlo identico.
 *
 * <h2>Todos opcionales, y hace falta que lo sean</h2>
 *
 * <p>Un certificado de <b>numeracion</b> acredita el numero municipal del predio y no dice nada de
 * la altura maxima; uno de <b>jurisdiccion</b> solo dice que el predio esta dentro del distrito.
 * Exigirlos a los cuatro tipos obligaria a inventarlos en tres de ellos.
 *
 * @param zonificacion la zona del indice de usos
 * @param alturaMaxima la altura maxima permitida, tal como la escribe la norma local
 * @param areaLibreMinima el area libre minima exigida
 * @param retiroMunicipal el retiro municipal exigido
 * @param coeficienteEdificacion el coeficiente de edificacion aplicable
 */
public record ParametrosUrbanisticos(
        @Nullable String zonificacion,
        @Nullable String alturaMaxima,
        @Nullable String areaLibreMinima,
        @Nullable String retiroMunicipal,
        @Nullable String coeficienteEdificacion) {

    /**
     * {@code certificado.zonificacion varchar(60)} (V1).
     *
     * <p>Texto libre, y por eso mismo sin tope hasta #422: lo que el operador transcribia de mas
     * llegaba al {@code INSERT} <b>despues</b> de dibujar el certificado, el motor lo rechazaba con
     * 22001 y el borde contestaba 500 con incidencia ERROR. Este record se construye en el borde,
     * antes de que el caso de uso toque nada, asi que el rechazo llega sin papel. No se ensancha la
     * base: se dice cuanto cabe.
     */
    public static final int ZONIFICACION_MAXIMA = 60;

    /**
     * {@code certificado.altura_maxima}, {@code area_libre_minima}, {@code retiro_municipal} y
     * {@code coeficiente_edificacion}: las cuatro {@code varchar(40)} (V1). Ver {@link
     * #ZONIFICACION_MAXIMA}: «Av.: 3.00 ml; Calle: 2.00 ml; Pasaje: 0.00 ml» son 45 (#422).
     */
    public static final int PARAMETRO_MAXIMO = 40;

    public ParametrosUrbanisticos {
        zonificacion = limpiar(zonificacion, "La zonificacion", ZONIFICACION_MAXIMA);
        alturaMaxima = limpiar(alturaMaxima, "La altura maxima", PARAMETRO_MAXIMO);
        areaLibreMinima = limpiar(areaLibreMinima, "El area libre minima", PARAMETRO_MAXIMO);
        retiroMunicipal = limpiar(retiroMunicipal, "El retiro municipal", PARAMETRO_MAXIMO);
        coeficienteEdificacion =
                limpiar(coeficienteEdificacion, "El coeficiente de edificacion", PARAMETRO_MAXIMO);
    }

    /** Ninguno declarado: lo normal en un certificado de numeracion o de jurisdiccion. */
    public static ParametrosUrbanisticos ninguno() {
        return new ParametrosUrbanisticos(null, null, null, null, null);
    }

    /** Si no se declaro ninguno. */
    public boolean estanVacios() {
        return zonificacion == null
                && alturaMaxima == null
                && areaLibreMinima == null
                && retiroMunicipal == null
                && coeficienteEdificacion == null;
    }

    private static @Nullable String limpiar(@Nullable String texto, String campo, int maximo) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(
                    campo
                            + " '"
                            + limpio
                            + "' tiene "
                            + limpio.length()
                            + " caracteres y el certificado admite "
                            + maximo);
        }
        return limpio.isEmpty() ? null : limpio;
    }
}
