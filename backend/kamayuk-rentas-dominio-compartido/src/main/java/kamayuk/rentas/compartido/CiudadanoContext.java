package kamayuk.rentas.compartido;

import java.util.Optional;
import kamayuk.rentas.dominio.DocumentoIdentidad;

/**
 * Sujeto de la peticion cuando quien pregunta es el <b>ciudadano</b> y no un funcionario
 * (ADR-0020).
 *
 * <p>Gemelo deliberado de {@link TenantContext}, y con la misma regla: el documento entra <b>una
 * vez</b>, en el borde de la aplicacion, desde un claim del token ya validado, y ninguna capa
 * intermedia lo recibe ni lo pasa. Si el desarrollador no lo maneja, no puede olvidarlo (regla 2).
 *
 * <p>Lo que este contexto sustituye es exactamente lo que D-07 no sabia resolver. El portal del
 * contribuyente <b>no</b> lleva municipalidad: no pertenece a ninguna. Lo que lleva es su
 * documento, y lo lleva <b>firmado</b>: no es un parametro que el cliente elige —eso era {@code GET
 * /portal/deuda?doc=44218937}, el endpoint de enumeracion que ADR-0020 retira— sino un claim que la
 * cadena de seguridad valido criptograficamente contra el emisor del realm del ciudadano.
 *
 * <p><b>Los dos contextos no conviven en una peticion.</b> Bajo {@code /api/v1/portal/**} corre
 * {@code DocumentoCiudadanoContextFilter} y no {@code TenantContextFilter}; en el resto de la API,
 * al reves. El {@link TenantContext} de una peticion del portal lo mueve, municipalidad por
 * municipalidad, {@code RecorridoPorMunicipalidades}, que es el unico componente del perfil {@code
 * web} autorizado a hacerlo —y lo comprueba una regla de ArchUnit—.
 *
 * <p>Nombre en ingles el sufijo {@code Context}, como sus dos gemelos: es una utilidad tecnica y no
 * vocabulario tributario (ARQ-04 §3).
 */
public final class CiudadanoContext {

    private static final ThreadLocal<DocumentoIdentidad> ACTUAL = new ThreadLocal<>();

    private CiudadanoContext() {}

    /** Fija el sujeto de la peticion. Un unico llamador legitimo: el borde de la aplicacion. */
    public static void fijar(DocumentoIdentidad documento) {
        if (documento == null) {
            throw new IllegalArgumentException("El sujeto del ciudadano no admite un valor nulo");
        }
        ACTUAL.set(documento);
    }

    /**
     * El documento de quien pregunta.
     *
     * @throws IllegalStateException si no hay ninguno. Falla a proposito, igual que {@link
     *     TenantContext#actual()}: una consulta del portal sin sujeto no es una consulta
     *     incompleta, es una consulta por cualquiera, que es lo que este contexto existe para
     *     impedir
     */
    public static DocumentoIdentidad actual() {
        DocumentoIdentidad documento = ACTUAL.get();
        if (documento == null) {
            throw new IllegalStateException(
                    "No hay sujeto de ciudadano fijado. Toda consulta del portal ocurre sobre el"
                            + " documento que trae el token, establecido en el borde de la"
                            + " aplicacion");
        }
        return documento;
    }

    /**
     * El documento, si lo hay.
     *
     * <p>Lo usa el guardia de acceso: {@code RequiereAcceso.CIUDADANO} se admite <b>solo</b> si la
     * peticion llego por la cadena del ciudadano, y estar aqui es lo que lo demuestra.
     */
    public static Optional<DocumentoIdentidad> actualSiHay() {
        return Optional.ofNullable(ACTUAL.get());
    }

    /** Se llama siempre al cerrar la peticion, incluso si hubo error. */
    public static void limpiar() {
        ACTUAL.remove();
    }
}
