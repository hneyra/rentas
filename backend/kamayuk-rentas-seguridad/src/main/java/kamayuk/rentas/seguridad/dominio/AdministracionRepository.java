package kamayuk.rentas.seguridad.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Puerto de persistencia del modelo de administracion del manual (cap. 4).
 *
 * <p>Los cinco conceptos van en un solo puerto y no en cinco porque se administran juntos: no hay
 * ningun caso de uso que toque grupos sin mirar usuarios, ni miembros sin mirar grupos. Cinco
 * interfaces de dos metodos serian cinco archivos que siempre se implementan a la vez y siempre se
 * inyectan a la vez.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): la aplica la politica RLS.
 */
public interface AdministracionRepository {

    // ---- Modulos y accesos: se siembran del catalogo, aqui solo se leen ----

    Pagina<Modulo> modulos(Paginacion paginacion);

    Pagina<Acceso> accesos(Paginacion paginacion);

    Optional<Acceso> accesoPorCodigo(String codigo);

    /** Para resolver el codigo de un acceso a partir del id que guarda un {@code Permiso}. */
    Optional<Acceso> accesoPorId(long id);

    // ---- Grupos ----

    Pagina<Grupo> grupos(Paginacion paginacion);

    Optional<Grupo> grupo(long id);

    /**
     * El grupo con ese nombre, si existe.
     *
     * <p>Existe para que la implantacion pueda ser idempotente sin recorrer paginas: el nombre ya
     * es unico por municipalidad en el esquema, asi que buscar por el es buscar por una clave.
     */
    Optional<Grupo> grupoPorNombre(String nombre);

    Grupo guardar(Grupo grupo);

    // ---- Usuarios ----

    Pagina<Usuario> usuarios(Paginacion paginacion);

    Optional<Usuario> usuario(long id);

    Optional<Usuario> usuarioPorCuenta(String cuenta);

    Usuario guardar(Usuario usuario);

    // ---- Miembros ----

    Optional<Miembro> miembro(long grupoId, long usuarioId);

    /**
     * A que grupos pertenece un usuario (#543).
     *
     * <p><b>Solo las pertenencias activas.</b> Una baja no se borra —la fila de {@code miembro}
     * sigue ahi con {@code activo} en falso, RNF-051—, pero quien salio de un grupo ya no pertenece
     * a el, y devolverlo aqui haria que la matriz de permisos atribuyera a un grupo lo que ese
     * grupo ya no da.
     *
     * <p>Lo que <b>si</b> devuelve son los grupos inhabilitados o fuera de vigencia a los que se
     * sigue perteneciendo: pertenecer y que el grupo surta efecto son dos cosas distintas, y {@link
     * Grupo} publica su estado y su vigencia para que quien lea las separe. Esconderlos dejaria a
     * quien administra sin saber por que alguien perdio sus permisos.
     */
    Pagina<Grupo> gruposDeUsuario(long usuarioId, Paginacion paginacion);

    /**
     * Quien esta en un grupo (#582).
     *
     * <p>Es la pregunta inversa de {@link #gruposDeUsuario}, y hasta #582 no se podia hacer:
     * derivarla costaba una peticion por usuario del padron de cuentas.
     *
     * <p><b>Solo las pertenencias activas</b>, por lo mismo que su gemela: la fila de una baja
     * sigue ahi porque no se borra (RNF-051), pero quien salio del grupo ya no esta en el, y
     * devolverlo aqui le atribuiria al grupo gente que ya no tiene sus permisos.
     *
     * <p>Lo que <b>si</b> devuelve son los usuarios deshabilitados que siguen afiliados: estar en
     * el grupo y poder entrar son dos cosas distintas, y {@link Usuario} publica su estado para que
     * quien lea las separe. Esconderlos dejaria sin contestar «que cuenta deshabilitada conserva
     * permisos», que es justo lo que se quiere poder ver.
     */
    Pagina<Usuario> usuariosDeGrupo(long grupoId, Paginacion paginacion);

    /** Alta o baja de la pertenencia. Nunca borra: la fila queda con {@code activo} en falso. */
    Miembro guardar(Miembro miembro);
}
