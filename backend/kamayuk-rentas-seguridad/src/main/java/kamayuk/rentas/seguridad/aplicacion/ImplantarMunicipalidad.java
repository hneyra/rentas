package kamayuk.rentas.seguridad.aplicacion;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.dominio.AdministracionRepository;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import kamayuk.rentas.seguridad.dominio.Usuario;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pone una municipalidad dentro del sistema: sin esto no hay nada que administrar.
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>Sin fila en {@code municipalidad} no hay {@code municipalidad_id} que poner en ningun token, y
 * sin accesos sembrados no hay ninguna opcion a la que dar permiso. La escalera de identidad del
 * despliegue lo dejaba a la vista: un token correcto llegaba hasta el guardia de acceso y recibia
 * {@code SIN_PRIVILEGIO}, porque del otro lado no habia nada.
 *
 * <h2>Por que un proceso y no un endpoint</h2>
 *
 * <p>Dar de alta una municipalidad es escribir en {@code municipalidad}, y esa tabla la escribe
 * <b>solo {@code sgtm_owner}</b> —{@code V6__rls.sql} lo dice con una politica {@code FOR ALL TO
 * sgtm_owner}, y el esquema lo explica: «dar de alta una municipalidad es una operacion de
 * implantacion»—. Un endpoint que lo hiciera le exigiria a {@code sgtm_app} un privilegio que se le
 * quito a proposito, y seria el camino mas corto de una pantalla de alta a una escalada entre
 * municipalidades.
 *
 * <p>Corre en el perfil {@code batch}: sin servidor web, sin puerto expuesto y con vida corta. Las
 * credenciales de {@code sgtm_owner} entran <b>solo</b> aqui, para <b>un</b> {@code INSERT}, en una
 * conexion que se abre y se cierra en el paso 1. Todo lo demas —accesos, grupo, usuario, permisos—
 * va por el camino normal de la aplicacion, como {@code sgtm_app} y con su auditoria, porque son
 * escrituras de negocio y tienen que dejar el mismo rastro que dejarian hechas a mano.
 *
 * <h2>Los dos grupos que deja</h2>
 *
 * <ul>
 *   <li><b>{@code Administracion del sistema}</b> — el catalogo entero, los siete privilegios. Es
 *       el grupo del primer administrador, que gestiona la municipalidad completa (como el
 *       superadministrador de un ERP).
 *   <li><b>{@code Seguridad}</b> — solo el modulo de seguridad: administra el acceso de los
 *       usuarios —grupos, usuarios, permisos, miembros— sin tocar el resto. Se crea <b>sin
 *       miembros</b>, como plantilla; una municipalidad afilia en el a quien lleve las altas y
 *       bajas de usuarios.
 * </ul>
 *
 * <h2>Idempotente, entera</h2>
 *
 * <p>Se ejecuta en cada despliegue. Lo que ya existe se queda como esta —con los permisos que
 * alguien haya configurado despues—, y lo que falta se crea. Nunca borra: retirarle permisos al
 * administrador porque alguien relanzo el despliegue seria peor que no tener el procedimiento.
 *
 * <h2>Lo que NO hace, y por que</h2>
 *
 * <ul>
 *   <li><b>No crea ninguna clave.</b> El sistema no guarda contrasenas y no las transporta
 *       (ADR-0005): la credencial del administrador vive en Keycloak y se crea con {@code
 *       despliegue/identidad/crear-usuario.sh}. Lo que se crea aqui es la <b>fila</b> del usuario,
 *       y lo que une las dos mitades es que {@code usuario.cuenta} sea el mismo {@code
 *       preferred_username} del token.
 *   <li><b>No fija ningun ejercicio de trabajo.</b> El ejercicio vive en {@code sesion}, es de cada
 *       sesion y se elige al entrar; no hay un ejercicio de la municipalidad que fijar aqui.
 * </ul>
 *
 * <p>Las dos cosas figuraban en el alcance del issue y no se pueden hacer como estaban escritas.
 * Quedan anotadas aqui en vez de resueltas a medias, que es como se cuelan las contrasenas en la
 * base de datos.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("kamayuk.implantacion.ubigeo")
@EnableConfigurationProperties(DatosDeImplantacion.class)
public class ImplantarMunicipalidad implements ApplicationRunner {

    /** El grupo del que cuelgan los permisos del primer administrador. */
    static final String GRUPO_DE_ADMINISTRACION = "Administracion del sistema";

    /**
     * Grupo delegado que solo administra el <b>acceso de los usuarios</b>: grupos, usuarios,
     * permisos y miembros, con los siete privilegios. Es el alcance que tuvo {@link
     * #GRUPO_DE_ADMINISTRACION} antes de pasar a recibir el catalogo entero.
     *
     * <p>La implantacion lo crea <b>sin miembros</b>, como plantilla: una municipalidad afilia en
     * el a quien lleva las altas y bajas de usuarios sin darle con ello el resto del sistema.
     */
    static final String GRUPO_DE_SEGURIDAD = "Seguridad";

    /**
     * Modulo del catalogo cuyas opciones recibe el grupo {@link #GRUPO_DE_SEGURIDAD}.
     *
     * <p>Es el codigo que el catalogo <b>deriva</b> del nombre del modulo —«SEGURIDAD», en
     * mayusculas y sin tildes—, no el nombre del contexto acotado. Confundir los dos hizo que una
     * version otorgara cero permisos, y lo caza {@code ImplantarMunicipalidadTest}.
     */
    static final String MODULO_DE_SEGURIDAD = "SEGURIDAD";

    /** La opcion que hace administrador a quien la tiene. Se otorga primero; ver mas abajo. */
    static final String ACCESO_QUE_ADMINISTRA = "permisos";

    private static final Logger log = LoggerFactory.getLogger(ImplantarMunicipalidad.class);

    private final SembradorDeAccesos sembrador;
    private final AdministrarSeguridad seguridad;
    private final AdministrarPermisos permisos;
    private final AdministracionRepository administracion;
    private final RegistroDeMunicipalidades registro;
    private final TransactionTemplate transaccion;
    private final DatosDeImplantacion datos;

    public ImplantarMunicipalidad(
            SembradorDeAccesos sembrador,
            AdministrarSeguridad seguridad,
            AdministrarPermisos permisos,
            AdministracionRepository administracion,
            RegistroDeMunicipalidades registro,
            PlatformTransactionManager gestorDeTransacciones,
            DatosDeImplantacion datos) {
        this.sembrador = sembrador;
        this.seguridad = seguridad;
        this.permisos = permisos;
        this.administracion = administracion;
        this.registro = registro;
        this.transaccion = new TransactionTemplate(gestorDeTransacciones);
        this.datos = datos;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        long municipalidadId =
                registro.darDeAltaSiFalta(
                        datos.ubigeo(), datos.nombre(), datos.tipo(), datos.esDemostracion());

        // El perfil batch no tiene filtros HTTP, asi que los dos contextos que en una
        // peticion salen del token se fijan aqui a mano. Origen.deProceso existe para
        // esto: una escritura sin peticion detras, que aun asi tiene que decir quien.
        TenantContext.fijar(new MunicipalidadId(municipalidadId));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try {
            Observacion porQue =
                    Observacion.de(
                            "Implantacion de la municipalidad " + datos.ubigeo() + " (despliegue)");

            // Una sola transaccion para los cuatro pasos, y por dos motivos distintos.
            //
            // El primero es de negocio: una municipalidad implantada a medias —con
            // accesos y sin administrador— es peor que ninguna, porque parece lista.
            //
            // El segundo es tecnico y se paga en cuanto se olvida: `grupo`, `usuario` y
            // `miembro` son tablas de tenant, y sus politicas leen app.municipalidad_id,
            // que TenantTransactionManager fija con SET LOCAL AL ABRIR la transaccion.
            // Leerlas fuera de una no devuelve vacio: falla con «unrecognized
            // configuration parameter». Es exactamente el defecto que tenia el guardia
            // de acceso, y aparecio aqui otra vez a las pocas horas de arreglarlo alli.
            Resumen resumen =
                    transaccion.execute(
                            estado -> {
                                int nuevos = sembrador.sembrar(porQue);
                                Grupo grupo = grupoDeAdministracion(porQue);
                                Usuario admin = administrador(porQue);
                                afiliar(grupo, admin, porQue);
                                int alAdministrador = permisosDelAdministrador(grupo, porQue);
                                int aSeguridad =
                                        permisosDeSeguridad(grupoDeSeguridad(porQue), porQue);
                                return new Resumen(
                                        nuevos, admin.cuenta(), alAdministrador, aSeguridad);
                            });
            if (resumen == null) {
                throw new IllegalStateException("La implantacion no devolvio resumen");
            }

            // El regimen se registra aqui aunque sea una sola palabra: es lo unico del
            // resultado que no se puede comprobar mirando pantallas. Una instalacion que
            // se creia de demostracion y salio real emite papeles sin marca, y quien lo
            // descubre es quien recibe uno (#122).
            log.info(
                    "Municipalidad {} lista ({}): id {}, {} accesos nuevos, administrador '{}',"
                            + " {} permisos al grupo '{}', {} al grupo '{}'",
                    datos.ubigeo(),
                    datos.esDemostracion() ? "DEMOSTRACION" : "instalacion real",
                    municipalidadId,
                    resumen.accesosNuevos(),
                    resumen.cuenta(),
                    resumen.permisosDelAdministrador(),
                    GRUPO_DE_ADMINISTRACION,
                    resumen.permisosDeSeguridad(),
                    GRUPO_DE_SEGURIDAD);
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }

    private Grupo grupoDeAdministracion(Observacion porQue) {
        return administracion
                .grupoPorNombre(GRUPO_DE_ADMINISTRACION)
                .orElseGet(
                        () ->
                                seguridad.registrarGrupo(
                                        Grupo.nuevo(
                                                GRUPO_DE_ADMINISTRACION,
                                                "Creado por la implantacion: administra la"
                                                        + " municipalidad entera"),
                                        porQue));
    }

    private Grupo grupoDeSeguridad(Observacion porQue) {
        return administracion
                .grupoPorNombre(GRUPO_DE_SEGURIDAD)
                .orElseGet(
                        () ->
                                seguridad.registrarGrupo(
                                        Grupo.nuevo(
                                                GRUPO_DE_SEGURIDAD,
                                                "Creado por la implantacion: administra el acceso"
                                                        + " de los usuarios (grupos, usuarios,"
                                                        + " permisos, miembros). Sin miembros:"
                                                        + " es una plantilla"),
                                        porQue));
    }

    /**
     * El primer administrador, como fila.
     *
     * <p>{@code cuenta} tiene que coincidir con el {@code preferred_username} del token: es lo
     * unico que une esta fila con la identidad de Keycloak, y si no coinciden el usuario entra y no
     * es nadie.
     */
    private Usuario administrador(Observacion porQue) {
        return administracion
                .usuarioPorCuenta(datos.administrador())
                .orElseGet(
                        () ->
                                seguridad.registrarUsuario(
                                        Usuario.nuevo(
                                                datos.administrador(),
                                                datos.nombreDelAdministrador(),
                                                null),
                                        porQue));
    }

    private void afiliar(Grupo grupo, Usuario administrador, Observacion porQue) {
        long grupoId = exigirId(grupo.id(), "grupo");
        long usuarioId = exigirId(administrador.id(), "usuario");
        if (administracion.miembro(grupoId, usuarioId).isEmpty()) {
            seguridad.afiliar(grupoId, usuarioId, porQue);
        }
    }

    /**
     * Los permisos con que arranca {@link #GRUPO_DE_ADMINISTRACION}: <b>el catalogo entero, los
     * siete privilegios</b>.
     *
     * <p>El administrador inicial de una municipalidad la administra <b>toda</b>, no solo su
     * seguridad: recibe las 134 opciones de los 12 modulos con {@link Privilegio} completo. Desde
     * ahi crea los grupos funcionales de REQ-03 §3 —Jefe de Rentas, Cajero, Fiscalizador…— y les
     * reparte lo que a cada uno le toca.
     *
     * <p>Esto no deja una cuenta que lo pueda todo sin control: las reglas de separacion de
     * funciones (REQ-03 §4, SoD-1…SoD-5) se verifican en el servidor <b>al margen de los
     * permisos</b> —quien carga un parametro sigue sin poder aprobarlo, tenga o no el privilegio—.
     * Y es idempotente y no destructivo: si despues se le acota el alcance a este grupo, un
     * relanzamiento del despliegue no lo vuelve a abrir (la guarda del ultimo administrador impide
     * que quede en cero, no que quede en poco).
     */
    private int permisosDelAdministrador(Grupo grupo, Observacion porQue) {
        return otorgarSietePrivilegios(grupo, opcion -> true, porQue);
    }

    /**
     * Los permisos de {@link #GRUPO_DE_SEGURIDAD}: <b>solo el modulo de seguridad</b>, los siete
     * privilegios. Es el alcance con que se administra el acceso de los usuarios —grupos, usuarios,
     * permisos, miembros— sin tocar el resto del sistema. El grupo se crea sin miembros; una
     * municipalidad afilia en el a quien haga esa tarea.
     */
    private int permisosDeSeguridad(Grupo grupo, Observacion porQue) {
        return otorgarSietePrivilegios(
                grupo, opcion -> MODULO_DE_SEGURIDAD.equals(opcion.moduloCodigo()), porQue);
    }

    /**
     * Otorga los siete privilegios sobre cada opcion del catalogo que pase el filtro.
     *
     * <p>La lista sale del catalogo y no de constantes: una pantalla nueva entra sola, que es lo
     * mismo que promete la siembra de accesos.
     */
    private int otorgarSietePrivilegios(
            Grupo grupo, Predicate<CatalogoDeOpciones.Opcion> filtro, Observacion porQue) {
        long grupoId = exigirId(grupo.id(), "grupo");
        Set<Privilegio> todos = EnumSet.allOf(Privilegio.class);

        List<String> opciones =
                CatalogoDeOpciones.leer().stream()
                        .filter(filtro)
                        .map(CatalogoDeOpciones.Opcion::codigo)
                        // `permisos` primero, y no es cosmetico: AdministrarPermisos rechaza
                        // —despues de escribir, dentro de la misma transaccion— cualquier cambio
                        // que deje a la municipalidad sin nadie capaz de administrar permisos. En
                        // una municipalidad recien creada no hay nadie, asi que el PRIMER permiso
                        // que se otorgue tiene que ser justamente ese; con cualquier otro, la
                        // guarda mira el estado resultante, no encuentra administrador y aborta la
                        // implantacion entera.
                        //
                        // La guarda protege un invariante que durante el arranque todavia no se
                        // cumple. La alternativa seria relajarla para la implantacion, que es como
                        // se desactivan las barreras: con un caso legitimo.
                        .sorted(
                                Comparator.comparing(
                                        codigo -> !ACCESO_QUE_ADMINISTRA.equals(codigo)))
                        .toList();

        if (opciones.isEmpty()) {
            throw new IllegalStateException(
                    "El filtro del catalogo no dejo ninguna opcion que otorgar. Un grupo de"
                            + " administracion sin permisos no puede repartir ninguno, y de ahi"
                            + " solo se sale por la base de datos");
        }

        for (String codigo : opciones) {
            permisos.fijarParaGrupo(grupoId, codigo, todos, porQue);
        }
        return opciones.size();
    }

    /** Lo que la implantacion hizo, para el mensaje final. */
    private record Resumen(
            int accesosNuevos,
            String cuenta,
            int permisosDelAdministrador,
            int permisosDeSeguridad) {}

    private static long exigirId(@Nullable Long id, String que) {
        if (id == null) {
            throw new IllegalStateException(
                    "El " + que + " recien guardado no tiene identificador");
        }
        return id;
    }
}
