package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Las versiones de la ficha catastral.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). <b>Ningun metodo edita los datos de una
 * version</b>: lo unico que se actualiza es su {@code vigencia_hasta}, y eso es cerrarla, no
 * cambiarla. Modificar una ficha es crear otra version.
 */
public interface FichaCatastralRepository {

    /** La version que rige en esa fecha. Reconstruir el pasado se hace con esto (regla 9). */
    Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha);

    /** Todas las versiones, de la mas reciente a la mas antigua. Nunca se pierde ninguna. */
    List<FichaCatastral> historial(long predioId, TipoFicha tipo);

    /** La ultima version registrada, este vigente o no. Sirve para saber que numero toca. */
    Optional<FichaCatastral> ultimaVersion(long predioId, TipoFicha tipo);

    /**
     * Una version concreta por su identificador, este vigente o no (#49).
     *
     * <p>Existe porque otros contextos <b>guardan</b> el identificador de la version que los
     * sustenta —{@code declaracion_jurada.ficha_catastral_id} desde #28, {@code
     * acta_fiscalizacion.ficha_id} desde #45— y despues necesitan volver a ella. Resolverla por
     * predio y fecha no serviria: la fecha que tienen es la de su propio acto, y entre esa fecha y
     * hoy la ficha pudo versionarse varias veces.
     */
    Optional<FichaCatastral> porId(long fichaId);

    /**
     * Inserta una version nueva con sus construcciones e instalaciones.
     *
     * <p>No hay un {@code actualizar}: una version registrada no se edita.
     */
    FichaCatastral insertar(FichaCatastral ficha);

    /** Cierra una version. Lo unico que toca es la vigencia. */
    FichaCatastral cerrar(FichaCatastral ficha);

    List<Construccion> construccionesDe(long fichaId);

    List<OtraInstalacion> instalacionesDe(long fichaId);

    /**
     * Lo propio del tipo de la ficha: actividades, bienes comunes con su reparto, grupos de tierra
     * con sus colindantes. Vacio para la ficha {@code UNICA}, cuyo detalle son las construcciones.
     */
    Optional<DetalleDeLaFicha> detalleDe(long fichaId, TipoFicha tipo);

    /**
     * La consulta transversal de la grilla (RF-006), paginada.
     *
     * <p>{@code titulares} llega ya resuelto por el caso de uso cuando el filtro pide un
     * contribuyente: este repositorio <b>no consulta el padron</b>. Vacio significa «no filtres por
     * titular»; una lista con elementos, «solo estos». Que la diferencia entre «no filtres» y «el
     * filtro no encontro a nadie» se decida aqui seria el sitio equivocado: devolveria el padron
     * entero justo cuando el usuario escribio un nombre que no existe.
     */
    Pagina<FichaEncontrada> consultar(
            FiltroDeFichas filtro, List<Long> titulares, LocalDate fecha, Paginacion paginacion);

    /** El historico con autor, fecha y motivo de cada version (RF-006). */
    List<VersionDeLaFicha> versionesDe(long predioId, TipoFicha tipo);
}
