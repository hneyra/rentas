package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.AdministracionRepository;
import kamayuk.rentas.seguridad.dominio.Permiso;
import kamayuk.rentas.seguridad.dominio.PermisoEfectivo;
import kamayuk.rentas.seguridad.dominio.PermisoRepository;
import kamayuk.rentas.seguridad.dominio.TitularDelPrivilegio;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Otorgar y retirar los siete privilegios sobre un acceso, por grupo y por usuario (RF-121).
 *
 * <p>Un solo metodo para otorgar y para retirar, con el conjunto completo de privilegios: <b>lo que
 * no esta en el conjunto se retira</b>. Es como funciona la pantalla —siete casillas que se marcan
 * y se desmarcan y se guarda— y evita el defecto de un {@code otorgar}/{@code revocar} por
 * separado, donde desmarcar una casilla se traduce en no hacer nada.
 *
 * <h2>La regla del ultimo administrador</h2>
 *
 * <p>Un cambio que dejara a la municipalidad <b>sin ningun usuario capaz de administrar
 * permisos</b> se rechaza. No es una precaucion teorica: el error mas caro de esta pantalla es
 * tambien el mas facil de cometer —quitarse a uno mismo, o al grupo del que uno es el unico
 * miembro, el privilegio que hacia falta para devolverselo—, y de ahi no se sale por el sistema:
 * hace falta entrar por la base de datos.
 *
 * <p>La comprobacion se hace <b>despues</b> de escribir el cambio y dentro de la misma transaccion,
 * no antes: lo que hay que verificar no es el estado actual sino el que quedaria, y calcularlo
 * simulando el cambio en memoria seria repetir la logica del comprobador y arriesgarse a que las
 * dos versiones se separen.
 */
@Service
public class AdministrarPermisos {

    /** Id en el catalogo (NEG-03) de la pantalla que administra los permisos. */
    private static final String ACCESO_DE_ADMINISTRACION = "permisos";

    private final PermisoRepository permisos;
    private final AdministracionRepository administracion;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AdministrarPermisos(
            PermisoRepository permisos,
            AdministracionRepository administracion,
            Auditoria auditoria,
            Clock reloj) {
        this.permisos = permisos;
        this.administracion = administracion;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Los permisos ya configurados de un grupo, con el codigo de cada acceso resuelto.
     *
     * <p><b>No trae las 134 opciones del catalogo</b>: trae las que este grupo tiene configuradas,
     * que para la mayoria son unas pocas. La pantalla de la matriz combina esta lista —sparse— con
     * la pagina de {@code GET /seguridad/accesos} que ya esta conectada, y así nunca carga el
     * catalogo entero en memoria solo para dibujar una matriz.
     */
    @Transactional(readOnly = true)
    public List<PermisoDeAcceso> deGrupo(long grupoId) {
        exigirQueElGrupoExista(grupoId);
        List<PermisoDeAcceso> resultado = new ArrayList<>();
        for (Permiso permiso : permisos.todosLosDeGrupo(grupoId)) {
            resultado.add(resuelto(permiso));
        }
        return resultado;
    }

    private PermisoDeAcceso resuelto(Permiso permiso) {
        Acceso acceso =
                administracion
                        .accesoPorId(permiso.accesoId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El permiso "
                                                        + permiso.id()
                                                        + " apunta al acceso "
                                                        + permiso.accesoId()
                                                        + ", que ya no existe"));
        return new PermisoDeAcceso(
                permiso.id() == null ? 0L : permiso.id(),
                acceso.codigo(),
                permiso.grupoId(),
                permiso.usuarioId(),
                permiso.privilegios());
    }

    /**
     * Los permisos <b>efectivos</b> de un usuario, cada uno diciendo de donde viene (#543).
     *
     * <p>No es {@link #deGrupo(long)} con otro sujeto. Aquella devuelve lo <b>configurado</b> de un
     * grupo —la matriz que se edita—; esta devuelve lo que el usuario <b>puede</b>, ya resuelto por
     * la precedencia: una excepcion de usuario sustituye al grupo entero para ese acceso, otorgue o
     * niegue. Publicar las dos listas sin resolver obligaria a quien pregunta a reimplementar esa
     * regla, y es la que no se puede equivocar —la interfaz la tenia invertida, {@code on =
     * esPropio || esHeredado}, que convierte una excepcion que restringe en una que amplia—.
     *
     * <p>Un {@code usuarioId} que no existe en esta municipalidad es 404 y no una lista vacia: no
     * tener permisos y no existir son dos respuestas distintas, y la segunda no se puede decir
     * callando.
     */
    @Transactional(readOnly = true)
    public List<PermisoEfectivo> efectivosDeUsuario(long usuarioId) {
        exigirQueElUsuarioExista(usuarioId);
        return permisos.efectivosConOrigenDe(usuarioId, LocalDate.now(reloj));
    }

    /**
     * Lo que una cuenta tiene <b>configurado</b>, pueda operar hoy o no (#583).
     *
     * <p>No es {@link #efectivosDeUsuario(long)} con otra guarda: es la otra pregunta. Aquella
     * aplica la regla del guardia entera —una cuenta deshabilitada recibe la lista vacia, porque
     * ensenar privilegios que despues responden 403 seria peor—, y su consecuencia es que <b>«se
     * deshabilito y conserva permisos» y «nunca tuvo ninguno» devuelven el mismo JSON</b>.
     * Deshabilitar no retira nada y rehabilitar lo devuelve entero, asi que quien audita necesita
     * saber que volveria a poder esa cuenta el dia que alguien la reactive.
     *
     * <p>Por eso la respuesta lleva {@code surtenEfectoHoy}: la lista de filas es, campo a campo,
     * la misma forma que la de la matriz efectiva, y sin esa marca un cliente que se equivoque de
     * lectura ensenaria como vigente lo que hoy responde 403. La marca se calcula con {@link
     * kamayuk.rentas.seguridad.dominio.Usuario#autorizaEn(LocalDate)}, o sea con la misma regla que
     * el guardia aplica al usuario.
     *
     * <p>Un {@code usuarioId} que no existe en esta municipalidad es 404 y no una respuesta vacia,
     * igual que en la matriz efectiva.
     */
    @Transactional(readOnly = true)
    public PermisosConfigurados configuradosDeUsuario(long usuarioId) {
        Usuario usuario = exigirQueElUsuarioExista(usuarioId);
        LocalDate hoy = LocalDate.now(reloj);
        return new PermisosConfigurados(
                usuarioId,
                usuario.cuenta(),
                usuario.autorizaEn(hoy),
                permisos.configuradosDe(usuarioId, hoy));
    }

    /**
     * Que cuentas pueden hoy ejercer un privilegio sobre un acceso (#583).
     *
     * <p>La pregunta del panel que auditaba quien tiene la llave de la caja, y que hasta ahora
     * costaba <b>una peticion por cuenta del padron</b>. No se compone con {@link
     * #efectivosDeUsuario(long)} ni recorriendo los grupos: la excepcion propia de una cuenta
     * sustituye a lo que su grupo le da, asi que un usuario cuyo grupo no tiene el privilegio puede
     * tenerlo por excepcion, y al reves.
     *
     * <p>Un {@code codigoDeAcceso} que no existe en esta municipalidad es 404 nombrandolo, no una
     * pagina vacia: no tener titulares y no existir son dos respuestas distintas, y la segunda no
     * se puede decir callando.
     */
    @Transactional(readOnly = true)
    public Pagina<TitularDelPrivilegio> quienesTienen(
            String codigoDeAcceso, Privilegio privilegio, Paginacion paginacion) {
        long accesoId = acceso(codigoDeAcceso);
        return permisos.quienesTienen(accesoId, privilegio, LocalDate.now(reloj), paginacion);
    }

    /**
     * Lo configurado de una cuenta, con la marca de si hoy surte efecto.
     *
     * @param surtenEfectoHoy falso cuando la cuenta esta deshabilitada o fuera de vigencia; los
     *     permisos siguen ahi y volverian a valer el dia que se reactive
     */
    public record PermisosConfigurados(
            long usuarioId,
            String cuenta,
            boolean surtenEfectoHoy,
            List<PermisoEfectivo> permisos) {}

    /** Un permiso ya resuelto: el codigo de su acceso en vez del id interno. */
    public record PermisoDeAcceso(
            long id,
            String codigoDeAcceso,
            @Nullable Long grupoId,
            @Nullable Long usuarioId,
            Set<Privilegio> privilegios) {}

    /** Fija los privilegios de un <b>grupo</b> sobre un acceso. Lo no incluido se retira. */
    @Transactional
    public Permiso fijarParaGrupo(
            long grupoId,
            String codigoDeAcceso,
            Set<Privilegio> privilegios,
            Observacion observacion) {

        long accesoId = acceso(codigoDeAcceso);
        exigirQueElGrupoExista(grupoId);

        Permiso permiso =
                permisos.deGrupo(accesoId, grupoId)
                        .map(existente -> conPrivilegios(existente, privilegios))
                        .orElseGet(() -> new Permiso(null, accesoId, grupoId, null, privilegios));

        return guardarYComprobar(permiso, codigoDeAcceso, observacion);
    }

    /**
     * Fija los privilegios de un <b>usuario</b> sobre un acceso: la excepcion.
     *
     * <p>Sustituye al grupo para ese acceso, no se suma a el. Sirve tanto para ampliar como para
     * restringir, y la segunda es la que no se puede expresar de otra forma. Ver la precedencia en
     * {@code ComprobadorDeAccesoJdbc}.
     */
    @Transactional
    public Permiso fijarParaUsuario(
            long usuarioId,
            String codigoDeAcceso,
            Set<Privilegio> privilegios,
            Observacion observacion) {

        long accesoId = acceso(codigoDeAcceso);
        exigirQueElUsuarioExista(usuarioId);

        Permiso permiso =
                permisos.deUsuario(accesoId, usuarioId)
                        .map(existente -> conPrivilegios(existente, privilegios))
                        .orElseGet(() -> new Permiso(null, accesoId, null, usuarioId, privilegios));

        return guardarYComprobar(permiso, codigoDeAcceso, observacion);
    }

    // ------------------------------------------------------------------

    private Permiso guardarYComprobar(
            Permiso permiso, String codigoDeAcceso, Observacion observacion) {

        Permiso guardado = permisos.save(permiso);

        // ADR-0008 §5: quien administra la seguridad no puede alterar su propia pista.
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "permiso",
                                String.valueOf(guardado.id()),
                                Operacion.PERMISO,
                                observacion)
                        .con(null, descripcion(guardado, codigoDeAcceso)));

        exigirQueQuedeAlgunAdministrador();
        return guardado;
    }

    private void exigirQueQuedeAlgunAdministrador() {
        if (permisos.usuariosQuePuedenAdministrarPermisos(LocalDate.now(reloj)) == 0) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "El cambio dejaria a la municipalidad sin ningun usuario capaz de administrar"
                            + " permisos, y de ahi no se sale por el sistema. Otorgue primero el"
                            + " privilegio a otro usuario o grupo");
        }
    }

    private long acceso(String codigo) {
        Acceso acceso =
                administracion
                        .accesoPorCodigo(codigo)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun acceso con codigo '"
                                                        + codigo
                                                        + "'"));
        return Objects.requireNonNull(
                acceso.id(), "Un acceso leido de la base tiene identificador");
    }

    private void exigirQueElGrupoExista(long grupoId) {
        administracion
                .grupo(grupoId)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun grupo con identificador " + grupoId));
    }

    private Usuario exigirQueElUsuarioExista(long usuarioId) {
        return administracion
                .usuario(usuarioId)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun usuario con identificador " + usuarioId));
    }

    private static Permiso conPrivilegios(Permiso existente, Set<Privilegio> privilegios) {
        return new Permiso(
                existente.id(),
                existente.accesoId(),
                existente.grupoId(),
                existente.usuarioId(),
                privilegios);
    }

    private static String descripcion(Permiso permiso, String codigoDeAcceso) {
        StringBuilder json = new StringBuilder("{\"acceso\":\"").append(codigoDeAcceso).append('"');
        if (permiso.grupoId() != null) {
            json.append(",\"grupoId\":").append(permiso.grupoId());
        }
        if (permiso.usuarioId() != null) {
            json.append(",\"usuarioId\":").append(permiso.usuarioId());
        }
        json.append(",\"privilegios\":[");
        boolean primero = true;
        for (Privilegio privilegio : Privilegio.values()) {
            if (permiso.tiene(privilegio)) {
                if (!primero) {
                    json.append(',');
                }
                json.append('"').append(privilegio.name()).append('"');
                primero = false;
            }
        }
        return json.append("]}").toString();
    }

    /** El acceso que gobierna esta misma pantalla; queda nombrado para que se vea en el diff. */
    static String accesoDeAdministracion() {
        return ACCESO_DE_ADMINISTRACION;
    }
}
