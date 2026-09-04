package kamayuk.rentas.documentos;

/**
 * Donde se firmara digitalmente un documento, cuando D-05 decida como.
 *
 * <p><b>Existe vacio a proposito.</b> El regimen de firma de valores, resoluciones y constancias
 * esta abierto —que certificado, quien custodia la clave, si la firma va incrustada en el PDF o en
 * un sobre aparte—, y ninguna de esas respuestas se puede inventar. Lo que si se sabe es
 * <b>donde</b> entra: entre generar los bytes y entregarlos.
 *
 * <p>Tenerlo declarado cuesta esta clase; no tenerlo costaria repasar cada sitio que genera un
 * documento el dia que se cierre D-05, y alguno se quedaria sin firmar sin que nada avisara.
 *
 * <p>La implementacion por omision {@link #SIN_FIRMA} devuelve los bytes tal cual. No lanza: un
 * sistema que no puede emitir <i>ningun</i> documento hasta que se cierre una decision de gobierno
 * no es mas seguro, es inutilizable.
 */
@FunctionalInterface
public interface PuntoDeFirma {

    /** Mientras D-05 siga abierta. Devuelve el documento sin tocar. */
    PuntoDeFirma SIN_FIRMA = (documento, formato) -> documento;

    byte[] firmar(byte[] documento, FormatoDeDocumento formato);
}
