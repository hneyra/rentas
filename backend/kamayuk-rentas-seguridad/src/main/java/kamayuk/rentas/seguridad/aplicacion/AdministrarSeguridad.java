package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.AdministracionRepository;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Miembro;
import kamayuk.rentas.seguridad.dominio.Modulo;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los casos de uso del modelo de administracion del manual (cap. 4).
 *
 * <p><b>Toda escritura deja auditoria con observacion</b> (ADR-0008 §5). Es la parte del sistema
 * donde mas importa: quien administra la seguridad es quien mas facilmente podria alterar su propia
 * pista, y la unica defensa es que cada cambio quede escrito con su porque antes de que la
 * transaccion se confirme.
 *
 * <p>Las lecturas van en el mismo servicio y marcadas {@code readOnly}: no abren transaccion de
 * escritura y por tanto no exigen observacion, que es lo que la regla 10 distingue.
 *
 * <h2>Las ocho escrituras, ya publicadas (#543 las censo, #572 las publica)</h2>
 *
 * <p>{@link #registrarGrupo}, {@link #inhabilitarGrupo}, {@link #habilitarGrupo}, {@link
 * #fijarVigenciaDeGrupo}, {@link #registrarUsuario}, {@link #inhabilitarUsuario}, {@link
 * #habilitarUsuario} y {@link #fijarVigenciaDeUsuario} existian sin ninguna ruta que llegara a
 * ellas. Las cuatro de grupo solo esperaban su controlador; las cuatro de usuario esperaban una
 * <b>decision</b>, porque un usuario son <b>dos mitades</b> —la fila de {@code usuario} y la cuenta
 * del proveedor de identidad (ADR-0005)— y la segunda se administra declarativamente.
 *
 * <p>La decision esta en <b>ADR-0012 §5</b>, y lo que la sostiene es una medida: la cuenta de
 * Keycloak la crea {@code reconciliar-identidades.sh} para <b>todos</b> los usuarios declarados, y
 * la fila de {@code usuario} la creaba {@code ImplantarMunicipalidad} para <b>uno solo</b>, el
 * administrador. Nada mas la creaba. De modo que declarar un segundo usuario en el archivo dejaba
 * una cuenta que autentica y a la que el guardia niega todo, <b>sin forma de arreglarlo</b>. El
 * alta por pantalla no introduce ese estado: le da dueño a la mitad que no lo tenia.
 *
 * <p>Cada mitad conserva su dueño, entonces: el archivo declarativo crea la cuenta y esta pantalla
 * crea la fila. La aplicacion no habla con Keycloak —no tiene con que ni debe tenerlo (ADR-0011
 * §3)— y por eso {@link #registrarUsuario} <b>no promete la otra mitad</b>: escribe una fila, en
 * una transaccion, y quien la lee tiene que saber que la cuenta se declara aparte.
 *
 * <p>{@code AdministrarPermisos.fijarParaUsuario} <b>ya no</b>: estuvo en la misma situacion por un
 * motivo propio —es la excepcion de usuario, y escribirla sin poder <b>leerla</b> antes era
 * administrar a ciegas—, y ese motivo se agoto cuando #543 publico la lectura. La escritura es
 * {@code PUT /seguridad/usuarios/&#123;id&#125;/permisos} desde #585.
 */
@Service
public class AdministrarSeguridad {

    private final AdministracionRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AdministrarSeguridad(
            AdministracionRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public Pagina<Modulo> modulos(Paginacion paginacion) {
        return repositorio.modulos(paginacion);
    }

    @Transactional(readOnly = true)
    public Pagina<Acceso> accesos(Paginacion paginacion) {
        return repositorio.accesos(paginacion);
    }

    @Transactional(readOnly = true)
    public Pagina<Grupo> grupos(Paginacion paginacion) {
        return repositorio.grupos(paginacion);
    }

    @Transactional(readOnly = true)
    public Pagina<Usuario> usuarios(Paginacion paginacion) {
        return repositorio.usuarios(paginacion);
    }

    /**
     * A que grupos pertenece un usuario (#543).
     *
     * <p>Sin esto no hay «heredado» que calcular: la matriz de permisos efectivos distingue lo
     * propio de lo que viene del grupo, y quien la dibuja necesita saber a cuales pertenece.
     *
     * <p>Un usuario que no existe en esta municipalidad es <b>404</b>, no una pagina vacia. No
     * pertenecer a ningun grupo y no existir son dos respuestas distintas y la segunda no se puede
     * decir callando: una pagina vacia se leeria como «no tiene grupos», que es exactamente lo
     * contrario de lo que hay que decirle a quien administra.
     */
    @Transactional(readOnly = true)
    public Pagina<Grupo> gruposDeUsuario(long usuarioId, Paginacion paginacion) {
        repositorio.usuario(usuarioId).orElseThrow(() -> noEncontrado("usuario", usuarioId));
        return repositorio.gruposDeUsuario(usuarioId, paginacion);
    }

    /**
     * Quien esta en un grupo (#582).
     *
     * <p>La pregunta inversa de {@link #gruposDeUsuario}, y hasta ahora no se podia hacer:
     * derivarla obligaba a recorrer el padron de cuentas preguntando por cada una.
     *
     * <p>Un grupo que no existe <b>en esta municipalidad</b> es <b>404</b>, no una pagina vacia. No
     * tener miembros y no existir son dos respuestas distintas, y la segunda no se puede decir
     * callando: cero filas se leeria como «este grupo no lo tiene nadie», que es lo contrario de lo
     * que hay que contestarle a quien administra. Es la misma decision que {@code Optional.empty()}
     * frente a la pagina vacia en el listado de manzanas (#537).
     */
    @Transactional(readOnly = true)
    public Pagina<Usuario> usuariosDeGrupo(long grupoId, Paginacion paginacion) {
        repositorio.grupo(grupoId).orElseThrow(() -> noEncontrado("grupo", grupoId));
        return repositorio.usuariosDeGrupo(grupoId, paginacion);
    }

    // ------------------------------------------------------------------ grupos

    /**
     * Alta de un grupo (#572).
     *
     * <p>El nombre es unico por municipalidad ({@code grupo_nombre_uq}, V5), y quien de verdad lo
     * garantiza es esa restriccion: la comprobacion previa de aqui no protege contra una carrera
     * —dos altas simultaneas del mismo nombre la superan las dos—, y lo unico que aporta es
     * <b>nombrar</b> el grupo repetido en vez de dejar salir un choque de clave. Es la misma
     * reparticion que #489 midio para el alta de predio.
     */
    @Transactional
    public Grupo registrarGrupo(Grupo grupo, Observacion observacion) {
        if (grupo.id() == null && repositorio.grupoPorNombre(grupo.nombre()).isPresent()) {
            throw new GrupoRepetido(grupo.nombre());
        }
        Grupo guardado = repositorio.guardar(grupo);
        auditar("grupo", guardado.id(), Operacion.ALTA, observacion, descripcion(guardado));
        return guardado;
    }

    /**
     * Inhabilita un grupo.
     *
     * <p>Retira el acceso de todos sus miembros de golpe —el comprobador exige {@code
     * grupo.habilitado}— y <b>no borra ninguna relacion</b>: las filas de {@code miembro} siguen
     * ahi, diciendo quien pudo hacer que y hasta cuando. Volver a habilitarlo devuelve el acceso a
     * los mismos, que es lo que se espera de una suspension temporal.
     */
    @Transactional
    public Grupo inhabilitarGrupo(long id, Observacion observacion) {
        Grupo grupo = repositorio.grupo(id).orElseThrow(() -> noEncontrado("grupo", id));
        Grupo guardado = repositorio.guardar(grupo.inhabilitado());
        auditar("grupo", id, Operacion.BAJA, observacion, descripcion(guardado));
        return guardado;
    }

    @Transactional
    public Grupo habilitarGrupo(long id, Observacion observacion) {
        Grupo grupo = repositorio.grupo(id).orElseThrow(() -> noEncontrado("grupo", id));
        Grupo guardado = repositorio.guardar(grupo.habilitadoDeNuevo());
        auditar("grupo", id, Operacion.MODIFICACION, observacion, descripcion(guardado));
        return guardado;
    }

    @Transactional
    public Grupo fijarVigenciaDeGrupo(long id, Vigencia vigencia, Observacion observacion) {
        Grupo grupo = repositorio.grupo(id).orElseThrow(() -> noEncontrado("grupo", id));
        Grupo guardado = repositorio.guardar(grupo.con(vigencia));
        auditar("grupo", id, Operacion.MODIFICACION, observacion, descripcion(guardado));
        return guardado;
    }

    // ------------------------------------------------------------------ usuarios

    /**
     * Alta de un usuario: <b>la fila del padron, no la cuenta del proveedor</b> (#572, ADR-0012
     * §5).
     *
     * <p>Escribe una fila en una transaccion, asi que desde el punto de vista de quien atiende es
     * atomica. Lo que no es atomico —ni puede serlo— es el par (cuenta, fila): la cuenta la crea el
     * archivo declarativo de {@code despliegue/identidad/}, y quien da de alta aqui tiene que saber
     * que le falta ese paso. Sin la cuenta, esta fila aparece en el padron, admite permisos y <b>no
     * puede entrar</b>: es el estado inofensivo de los dos, frente al de una cuenta sin fila —que
     * autentica y recibe un 403 en todo—.
     *
     * <p><b>{@code sujetoOidc} se queda nulo, a proposito.</b> Nadie lo escribe hoy y nadie lo lee
     * —el guardia resuelve por {@code cuenta}—, y las dos formas de rellenarlo son peores que
     * dejarlo: pedirle a quien atiende un UUID del proveedor, o escribirlo en el primer acceso, que
     * seria una escritura sin observacion en el camino de lectura del guardia. ADR-0012 §5.4 lo
     * razona entero.
     *
     * <p>La cuenta es unica por municipalidad ({@code usuario_cuenta_uq}, V5) y esa restriccion es
     * la que de verdad lo garantiza; la comprobacion de aqui solo la <b>nombra</b>.
     */
    @Transactional
    public Usuario registrarUsuario(Usuario usuario, Observacion observacion) {
        if (usuario.id() == null && repositorio.usuarioPorCuenta(usuario.cuenta()).isPresent()) {
            throw new CuentaRepetida(usuario.cuenta());
        }
        Usuario guardado = repositorio.guardar(usuario);
        auditar("usuario", guardado.id(), Operacion.ALTA, observacion, descripcion(guardado));
        return guardado;
    }

    @Transactional
    public Usuario inhabilitarUsuario(long id, Observacion observacion) {
        Usuario usuario = repositorio.usuario(id).orElseThrow(() -> noEncontrado("usuario", id));
        Usuario guardado = repositorio.guardar(usuario.inhabilitado());
        auditar("usuario", id, Operacion.BAJA, observacion, descripcion(guardado));
        return guardado;
    }

    @Transactional
    public Usuario habilitarUsuario(long id, Observacion observacion) {
        Usuario usuario = repositorio.usuario(id).orElseThrow(() -> noEncontrado("usuario", id));
        Usuario guardado = repositorio.guardar(usuario.habilitadoDeNuevo());
        auditar("usuario", id, Operacion.MODIFICACION, observacion, descripcion(guardado));
        return guardado;
    }

    @Transactional
    public Usuario fijarVigenciaDeUsuario(long id, Vigencia vigencia, Observacion observacion) {
        Usuario usuario = repositorio.usuario(id).orElseThrow(() -> noEncontrado("usuario", id));
        Usuario guardado = repositorio.guardar(usuario.con(vigencia));
        auditar("usuario", id, Operacion.MODIFICACION, observacion, descripcion(guardado));
        return guardado;
    }

    // ------------------------------------------------------------------ miembros

    /** Afiliar a alguien a un grupo, o reafiliarlo: la fila anterior se reactiva. */
    @Transactional
    public Miembro afiliar(long grupoId, long usuarioId, Observacion observacion) {
        exigirQueExistan(grupoId, usuarioId);
        Miembro guardado = repositorio.guardar(Miembro.alta(grupoId, usuarioId));
        auditarMiembro(guardado, Operacion.ALTA, observacion);
        return guardado;
    }

    /** Sacar a alguien de un grupo: se da de baja, no se borra (RNF-051). */
    @Transactional
    public Miembro desafiliar(long grupoId, long usuarioId, Observacion observacion) {
        exigirQueExistan(grupoId, usuarioId);
        Miembro guardado = repositorio.guardar(new Miembro(grupoId, usuarioId, false));
        auditarMiembro(guardado, Operacion.BAJA, observacion);
        return guardado;
    }

    // ------------------------------------------------------------------

    private void exigirQueExistan(long grupoId, long usuarioId) {
        repositorio.grupo(grupoId).orElseThrow(() -> noEncontrado("grupo", grupoId));
        repositorio.usuario(usuarioId).orElseThrow(() -> noEncontrado("usuario", usuarioId));
    }

    private void auditar(
            String tabla,
            @Nullable Long clave,
            Operacion operacion,
            Observacion observacion,
            String datos) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                tabla,
                                String.valueOf(clave),
                                operacion,
                                observacion)
                        .con(null, datos));
    }

    private void auditarMiembro(Miembro miembro, Operacion operacion, Observacion observacion) {
        auditar(
                "miembro",
                miembro.grupoId(),
                operacion,
                observacion,
                "{\"grupoId\":"
                        + miembro.grupoId()
                        + ",\"usuarioId\":"
                        + miembro.usuarioId()
                        + ",\"activo\":"
                        + miembro.activo()
                        + "}");
    }

    /**
     * No existe, o existe en otra municipalidad. Desde aqui es lo mismo, y decir cual de las dos
     * seria filtrar la existencia de datos ajenos.
     */
    private static ProblemaDeNegocio noEncontrado(String que, long id) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO, "No hay ningun " + que + " con identificador " + id);
    }

    private static String descripcion(Grupo grupo) {
        return "{\"nombre\":\""
                + grupo.nombre()
                + "\",\"habilitado\":"
                + grupo.habilitado()
                + ",\"vigenciaDesde\":"
                + comillas(grupo.vigencia().desde())
                + ",\"vigenciaHasta\":"
                + comillas(grupo.vigencia().hasta())
                + "}";
    }

    private static String descripcion(Usuario usuario) {
        return "{\"cuenta\":\""
                + usuario.cuenta()
                + "\",\"nombre\":\""
                + usuario.nombre()
                + "\",\"habilitado\":"
                + usuario.habilitado()
                + ",\"vigenciaDesde\":"
                + comillas(usuario.vigencia().desde())
                + ",\"vigenciaHasta\":"
                + comillas(usuario.vigencia().hasta())
                + "}";
    }

    private static String comillas(@Nullable Object valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    /** Ya hay un grupo con ese nombre en esta municipalidad. */
    public static final class GrupoRepetido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        GrupoRepetido(String nombre) {
            super("Ya hay un grupo llamado '" + nombre + "' en esta municipalidad");
        }
    }

    /** Ya hay un usuario con esa cuenta en esta municipalidad. */
    public static final class CuentaRepetida extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CuentaRepetida(String cuenta) {
            super("Ya hay un usuario con la cuenta '" + cuenta + "' en esta municipalidad");
        }
    }
}
