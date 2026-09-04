package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * El catalogo CIIU de giros (V4, V37, RF-112). Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p>Aqui si hay {@code editar}, al reves que en {@link LicenciaRepository}: el catalogo no es un
 * acto administrativo notificado. Una descripcion mal escrita se corrige, y retirar un giro es
 * ponerle {@code activo = false} —nunca borrarlo, porque hay licencias que lo citan—.
 */
public interface CiiuRepository {

    /**
     * Da de alta un giro.
     *
     * @throws CodigoDuplicado si ese codigo ya esta en el catalogo. Lo decide {@code
     *     ciiu_codigo_uq}, no un {@code SELECT} previo
     */
    Ciiu registrar(Ciiu giro);

    /** El giro con ese codigo. */
    Optional<Ciiu> porCodigo(String codigo);

    /** Los giros con esos identificadores, para resolver los de una licencia de una sola vez. */
    List<Ciiu> porIds(Set<Long> ids);

    /** El catalogo, paginado. */
    Pagina<Ciiu> buscar(CriterioDeCiiu criterio, Paginacion paginacion);

    /** Ese codigo CIIU ya esta en el catalogo de esta municipalidad. */
    final class CodigoDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CodigoDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
