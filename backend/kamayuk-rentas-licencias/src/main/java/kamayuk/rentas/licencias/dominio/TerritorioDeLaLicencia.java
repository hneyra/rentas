package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que de la comprobacion del territorio queda GUARDADO en la licencia (#43, AC-3).
 *
 * <h2>Por que se guarda al lado de la zona declarada y no en su lugar</h2>
 *
 * <p>{@code licencia_funcionamiento.zonificacion} es la zona <b>declarada</b>: la teclea quien
 * atiende y sale impresa en el papel que el titular cuelga en el establecimiento. La del territorio
 * la contesta {@code catastro} cortando el lote contra el plan vigente. Cuando las dos existen y
 * difieren, <b>las dos se guardan</b> y {@link #origen()} dice cual sostiene el acto: sustituir una
 * por otra en silencio cambia el fundamento de un acto administrativo sin dejar rastro, y ese acto
 * se impugna.
 *
 * <h2>Y por que se guarda tambien cuando no se pudo comprobar</h2>
 *
 * <p>Porque «se autorizo sin comprobar» y «se comprobo y salio bien» tienen que ser distinguibles
 * dentro de dos anos. Hoy no hay ni un poligono cargado en ninguna instalacion, asi que el caso
 * normal va a ser el primero durante bastante tiempo — y precisamente por eso tiene que verse.
 *
 * <p><b>Lo que NO se guarda entero es el riesgo y el ITSE</b>, y queda dicho: de las tres consultas
 * viaja aqui la zona con su ordenanza y un resumen en texto de las tres. El riesgo no mitigable no
 * necesita columna porque <b>no deja emitir</b> —una licencia con riesgo no mitigable comprobado no
 * llega a existir—, y los certificados ITSE vigentes son de {@code catastro} y se piden alli con su
 * fecha, que es donde no se quedan viejos.
 *
 * @param zonaDelTerritorio el codigo que {@code catastro} contesto; {@code null} si no se pudo
 * @param ordenanzaDeLaZona la ordenanza que aprobo el plan; sin ella una denegacion por zona no se
 *     puede notificar
 * @param origen cual de las dos zonas sostiene el acto
 * @param comprobacion que contestaron las tres consultas, y cual no contesto
 * @param autorizacion por que se emitio aunque el territorio no lo respaldara, con las palabras de
 *     quien lo asumio (#418); {@code null} si el territorio lo respaldaba o no se pregunto
 */
public record TerritorioDeLaLicencia(
        @Nullable String zonaDelTerritorio,
        @Nullable String ordenanzaDeLaZona,
        OrigenDeLaZona origen,
        @Nullable String comprobacion,
        @Nullable String autorizacion) {

    /** {@code licencia_funcionamiento.zona_del_territorio varchar(20)} (V14). */
    public static final int ZONA_MAXIMA = 20;

    /** {@code licencia_funcionamiento.comprobacion_territorio varchar(400)} (V14). */
    public static final int COMPROBACION_MAXIMA = 400;

    /** {@code licencia_funcionamiento.autorizacion_territorio varchar(500)} (V36). */
    public static final int AUTORIZACION_MAXIMA = 500;

    public TerritorioDeLaLicencia {
        Objects.requireNonNull(origen, "Hay que decir que zona sostiene el acto");
        if (autorizacion != null && autorizacion.length() > AUTORIZACION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La autorizacion va hasta "
                            + AUTORIZACION_MAXIMA
                            + " caracteres: recortarla cambiaria lo que alguien firmo");
        }
        // La misma guarda que `licencia_zona_del_territorio_ck` en la base, y aqui tambien: un
        // acto que dice sostenerse en el territorio sin traer el codigo con que se comprobo
        // afirma una comprobacion que no deja ver contra que se hizo.
        if (origen == OrigenDeLaZona.TERRITORIO && zonaDelTerritorio == null) {
            throw new IllegalArgumentException(
                    "La licencia dice sostenerse en la zona del territorio y no trae ninguna: sin"
                            + " el codigo con que se comprobo, «se comprobo» no significa nada");
        }
        if (zonaDelTerritorio != null && zonaDelTerritorio.length() > ZONA_MAXIMA) {
            throw new IllegalArgumentException(
                    "El codigo de zona va hasta " + ZONA_MAXIMA + " caracteres");
        }
        if (comprobacion != null && comprobacion.length() > COMPROBACION_MAXIMA) {
            comprobacion = comprobacion.substring(0, COMPROBACION_MAXIMA);
        }
    }

    /**
     * Lo que son las licencias anteriores a V14 y las que no tienen predio: nadie comprobo nada.
     *
     * <p>No es un valor por omision de comodidad: es literalmente lo que esas filas son, y por eso
     * la migracion tampoco las rellena hacia atras.
     */
    public static TerritorioDeLaLicencia sinComprobar(@Nullable String porQue) {
        return new TerritorioDeLaLicencia(null, null, OrigenDeLaZona.NO_COMPROBADA, porQue, null);
    }

    /**
     * Compone lo que se guarda a partir de lo que el territorio contesto y de quien lo asumio.
     *
     * <p>La autorizacion se guarda <b>solo si fue ella la que sostuvo el acto</b> ({@link
     * ComprobacionDelTerritorio#exigeAutorizacion()}). Hasta #418 se validaba como observacion y se
     * tiraba: {@code zona_origen = DECLARADA} afirmaba que «una persona lo autorizo por escrito» y
     * ese escrito no estaba en ninguna tabla. Y al reves, una autorizacion tecleada sobre un predio
     * en regla no se guarda: la licencia diria «emitida por excepcion» sin haberlo sido.
     *
     * @param comprobacion lo que contestaron las tres consultas
     * @param autorizacion la autorizacion expresa de la solicitud, si la trae
     */
    public static TerritorioDeLaLicencia de(
            ComprobacionDelTerritorio comprobacion, @Nullable Observacion autorizacion) {
        Objects.requireNonNull(comprobacion, "Sin comprobacion no hay nada que guardar");
        OrigenDeLaZona origen =
                comprobacion.zona() == RespuestaDelTerritorio.RESPONDIO
                        ? OrigenDeLaZona.TERRITORIO
                        : comprobacion.zona() == RespuestaDelTerritorio.NO_SE_PREGUNTO
                                ? OrigenDeLaZona.NO_COMPROBADA
                                : OrigenDeLaZona.DECLARADA;
        return new TerritorioDeLaLicencia(
                comprobacion.zonaDelTerritorio(),
                comprobacion.ordenanzaDeLaZona(),
                origen,
                comprobacion.motivo(),
                comprobacion.exigeAutorizacion() && autorizacion != null
                        ? autorizacion.texto()
                        : null);
    }

    /** Si el acto se sostiene en una autorizacion expresa y no en el territorio (#418). */
    public boolean porExcepcion() {
        return autorizacion != null;
    }
}
