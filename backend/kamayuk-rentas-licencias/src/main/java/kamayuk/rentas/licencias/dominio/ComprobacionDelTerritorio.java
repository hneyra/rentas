package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que el territorio contesto sobre el predio de un establecimiento, antes de autorizarlo (#43).
 *
 * <h2>Que se guarda, y por que las dos zonas</h2>
 *
 * <p>La solicitud trae una zona <b>declarada</b> —la teclea quien atiende— y {@code catastro}
 * contesta la del <b>territorio</b>, cortando el lote contra el plan vigente. Cuando las dos
 * existen y difieren, las dos se guardan y la licencia dice cual uso: sustituir una por otra en
 * silencio cambia el fundamento de un acto administrativo sin que quede rastro, y ese acto se
 * impugna (AC-3).
 *
 * <p><b>{@code aLaFecha} es la mitad de la respuesta</b> (regla 9). Un plan se sustituye por otro y
 * una carta de peligro caduca, asi que lo que se afirma no es «este predio es RDM» sino «este
 * predio era RDM el dia de la emision». Sin la fecha dentro, quien revise la licencia dentro de dos
 * anos no puede decir contra que se comprobo.
 *
 * @param aLaFecha el dia con que se pregunto y con que {@code catastro} resolvio
 * @param zona que contesto la consulta de zonificacion
 * @param zonaDelTerritorio el codigo de zona, cuando {@link #zona()} es {@link
 *     RespuestaDelTerritorio#RESPONDIO}
 * @param ordenanzaDeLaZona la ordenanza que aprobo el plan; sin ella una denegacion por zona no se
 *     puede notificar, porque no cita la norma que la sustenta
 * @param riesgo que contesto la consulta de riesgo del suelo
 * @param hayRiesgoNoMitigable lo que {@code catastro} publica DERIVADO y arriba; solo significa
 *     algo cuando {@link #riesgo()} es {@code RESPONDIO}
 * @param itse que contesto la consulta de certificados
 * @param certificadosVigentes cuantos ITSE estaban vigentes ese dia; solo significa algo cuando
 *     {@link #itse()} es {@code RESPONDIO}
 * @param compatibilidad el veredicto de {@link CompatibilidadConLaZona} para el giro principal
 * @param motivo lo que hay que decirle a quien opera cuando algo no se pudo comprobar
 */
public record ComprobacionDelTerritorio(
        LocalDate aLaFecha,
        RespuestaDelTerritorio zona,
        @Nullable String zonaDelTerritorio,
        @Nullable String ordenanzaDeLaZona,
        RespuestaDelTerritorio riesgo,
        boolean hayRiesgoNoMitigable,
        RespuestaDelTerritorio itse,
        int certificadosVigentes,
        CompatibilidadConLaZona compatibilidad,
        @Nullable String motivo) {

    /** {@code licencia_funcionamiento.comprobacion_territorio varchar(400)} (V14). */
    public static final int MOTIVO_MAXIMO = 400;

    public ComprobacionDelTerritorio {
        Objects.requireNonNull(aLaFecha, "La comprobacion dice a que dia se hizo (regla 9)");
        Objects.requireNonNull(zona, "Hay que decir que contesto la zonificacion");
        Objects.requireNonNull(riesgo, "Hay que decir que contesto el riesgo");
        Objects.requireNonNull(itse, "Hay que decir que contesto el ITSE");
        Objects.requireNonNull(compatibilidad, "Hay que decir si el giro cabe en la zona");

        // La invariante que impide leer una ausencia como una respuesta favorable: si la consulta
        // no RESPONDIO, no hay hecho que afirmar, y un `false` ahi seria «no hay riesgo» dicho por
        // el tipo. Es exactamente lo que AC-4 prohibe.
        if (zona != RespuestaDelTerritorio.RESPONDIO && zonaDelTerritorio != null) {
            throw new IllegalArgumentException(
                    "La consulta de zonificacion no respondio y aun asi trae zona «"
                            + zonaDelTerritorio
                            + "»: una zona que nadie contesto no puede sostener una licencia");
        }
        if (riesgo != RespuestaDelTerritorio.RESPONDIO && hayRiesgoNoMitigable) {
            throw new IllegalArgumentException(
                    "La consulta de riesgo no respondio y aun asi afirma que hay riesgo no"
                            + " mitigable: lo que no se pudo preguntar no se sabe");
        }
        if (itse != RespuestaDelTerritorio.RESPONDIO && certificadosVigentes != 0) {
            throw new IllegalArgumentException(
                    "La consulta del ITSE no respondio y aun asi cuenta "
                            + certificadosVigentes
                            + " certificado(s): lo que no se pudo preguntar no se sabe");
        }
        if (motivo != null && motivo.length() > MOTIVO_MAXIMO) {
            motivo = motivo.substring(0, MOTIVO_MAXIMO);
        }
    }

    /** Una comprobacion que no se hizo, porque la solicitud no trae predio. */
    public static ComprobacionDelTerritorio sinPredio(LocalDate aLaFecha) {
        return new ComprobacionDelTerritorio(
                aLaFecha,
                RespuestaDelTerritorio.NO_SE_PREGUNTO,
                null,
                null,
                RespuestaDelTerritorio.NO_SE_PREGUNTO,
                false,
                RespuestaDelTerritorio.NO_SE_PREGUNTO,
                0,
                CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR,
                "La solicitud no declara predio, asi que no hay territorio que consultar. Hay"
                        + " giros sin predio empadronado");
    }

    /**
     * Si las tres consultas contestaron y ninguna se opone: no hay riesgo no mitigable y el giro
     * cabe en la zona.
     *
     * <p>Lo que NO es: «no salio nada malo». Una consulta que no contesto no cuenta como favorable,
     * y por eso se exige {@link RespuestaDelTerritorio#RESPONDIO} en las dos que deciden.
     */
    public boolean todoComprobadoYFavorable() {
        return riesgo == RespuestaDelTerritorio.RESPONDIO
                && !hayRiesgoNoMitigable
                && zona == RespuestaDelTerritorio.RESPONDIO
                && compatibilidad == CompatibilidadConLaZona.COMPATIBLE;
    }

    /** El hecho que niega la licencia sin salida posible: medido, adverso y no opinable. */
    public boolean riesgoNoMitigableComprobado() {
        return riesgo == RespuestaDelTerritorio.RESPONDIO && hayRiesgoNoMitigable;
    }
}
