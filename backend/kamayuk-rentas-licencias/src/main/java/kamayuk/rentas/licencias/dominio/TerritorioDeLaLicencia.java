package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
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
 */
public record TerritorioDeLaLicencia(
        @Nullable String zonaDelTerritorio,
        @Nullable String ordenanzaDeLaZona,
        OrigenDeLaZona origen,
        @Nullable String comprobacion) {

    /** {@code licencia_funcionamiento.zona_del_territorio varchar(20)} (V14). */
    public static final int ZONA_MAXIMA = 20;

    /** {@code licencia_funcionamiento.comprobacion_territorio varchar(400)} (V14). */
    public static final int COMPROBACION_MAXIMA = 400;

    public TerritorioDeLaLicencia {
        Objects.requireNonNull(origen, "Hay que decir que zona sostiene el acto");
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
        return new TerritorioDeLaLicencia(null, null, OrigenDeLaZona.NO_COMPROBADA, porQue);
    }

    /** Compone lo que se guarda a partir de lo que el territorio contesto. */
    public static TerritorioDeLaLicencia de(ComprobacionDelTerritorio comprobacion) {
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
                comprobacion.motivo());
    }
}
