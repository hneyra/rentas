package kamayuk.rentas.seguridad.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSeguridad;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Miembro;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los cinco endpoints de administracion del manual (RF-120), tal como los declara el contrato.
 *
 * <p>Van en un solo controlador porque son las cinco pantallas de un mismo modulo del menu y
 * comparten el caso de uso; cinco clases de un metodo no aclararian nada.
 *
 * <p><b>Cada operacion declara su acceso por separado</b> y no la clase entera: son cinco opciones
 * distintas del catalogo, con permisos distintos. Quien administra usuarios no tiene por que poder
 * ver los modulos del sistema.
 *
 * <h2>Las ocho escrituras de #572, y la mitad que no escriben</h2>
 *
 * <p>El alta, la baja, la reactivacion y el cambio de vigencia de <b>grupo</b> y de <b>usuario</b>.
 * Las cuatro de grupo no esperaban mas que su controlador. Las cuatro de usuario esperaban la
 * decision de <b>ADR-0012 §5</b>, porque un usuario son <b>dos mitades</b> —esta fila y la cuenta
 * del proveedor de identidad (ADR-0005)— y hoy la segunda la crea el archivo declarativo de {@code
 * despliegue/identidad/}.
 *
 * <p><b>Lo que estas rutas escriben es el padron, y solo el padron.</b> Esta aplicacion no habla
 * con Keycloak: no tiene con que —ni un cliente HTTP saliente en {@code src/main}— ni debe tenerlo
 * (ADR-0011 §3 mantiene la credencial de administracion del realm fuera de su alcance). Asi que el
 * alta de un usuario deja una fila que figura en los listados, admite permisos y <b>no puede
 * entrar</b> mientras nadie declare su cuenta; y esa es la mitad inofensiva de las dos, frente a
 * una cuenta sin fila —que autentica y recibe 403 en todo—, que es la que este endpoint cierra.
 *
 * <p>El reparto de verbos sigue al de {@code PredioController} (#489, #490): {@code POST} para el
 * alta, {@code POST /&#123;id&#125;/baja} y {@code /&#123;id&#125;/reactivacion} para los dos
 * cambios de estado —que son <b>dos actos distintos</b>, con dos privilegios y dos operaciones de
 * auditoria (BAJA y MODIFICACION), no un booleano— y {@code PUT /&#123;id&#125;/vigencia} para la
 * caducidad, que sustituye el valor entero.
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad")
public class SeguridadController {

    private final AdministrarSeguridad administrar;

    public SeguridadController(AdministrarSeguridad administrar) {
        this.administrar = administrar;
    }

    @GetMapping("/modulos")
    @RequiereAcceso(acceso = "modulos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.ModuloResource> modulos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.modulos(paginacion.aPaginacion("orden")), Recursos.ModuloResource::de);
    }

    @GetMapping("/accesos")
    @RequiereAcceso(acceso = "accesos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.AccesoResource> accesos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.accesos(paginacion.aPaginacion("codigo")), Recursos.AccesoResource::de);
    }

    @GetMapping("/grupos")
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.GrupoResource> grupos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.grupos(paginacion.aPaginacion("nombre")), Recursos.GrupoResource::de);
    }

    @GetMapping("/usuarios")
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.UsuarioResource> usuarios(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.usuarios(paginacion.aPaginacion("cuenta")),
                Recursos.UsuarioResource::de);
    }

    /**
     * A que grupos pertenece un usuario: {@code GET /seguridad/usuarios/{id}/grupos} (#543).
     *
     * <p>Sin esta lectura la matriz de permisos de un usuario no puede decir de donde le viene lo
     * heredado, porque no se sabe de quien hereda. {@code /grupos/{grupo}/miembros} solo tenia el
     * {@code POST} que afilia y desafilia, y el dominio solo sabia contestar por la pareja concreta
     * —{@code miembro(grupoId, usuarioId)}—.
     *
     * <p><b>Su acceso es {@code usuarios} y no {@code miembros}</b>: es una lectura sobre un
     * usuario, y la propia grilla de «Usuarios del sistema» del manual dibuja una columna «Grupo».
     * Quien puede ver el padron de usuarios puede ver a que grupos pertenece cada uno; afiliarlo y
     * desafiliarlo sigue exigiendo {@code miembros} con {@code REGISTRO}.
     *
     * <p>La anotacion va <b>en el metodo</b>, como las otras cinco de este controlador: cada
     * operacion es una opcion distinta del catalogo. Aqui eso importa el doble, porque la clase no
     * declara ninguna y una lectura sin la suya se quedaria sin guardia (regla de ArchUnit: «en la
     * clase o en cada endpoint»).
     */
    @GetMapping("/usuarios/{id}/grupos")
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.GrupoResource> gruposDeUsuario(
            @PathVariable("id") long usuario, ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.gruposDeUsuario(usuario, paginacion.aPaginacion("nombre")),
                Recursos.GrupoResource::de);
    }

    /**
     * Quien esta en un grupo (#582).
     *
     * <p><b>Su acceso es {@code grupos} y no {@code miembros}</b>, por simetria exacta con {@code
     * gruposDeUsuario} de aqui arriba: aquella es una lectura sobre un usuario y pide {@code
     * usuarios}; esta es una lectura sobre un grupo y pide {@code grupos}. Quien puede ver el
     * padron de grupos puede ver quien esta en cada uno; afiliar y desafiliar sigue exigiendo
     * {@code miembros} con {@code REGISTRO}, que es el otro endpoint de esta misma ruta.
     *
     * <p>La anotacion va <b>en el metodo</b>: la clase no declara ninguna, asi que una lectura sin
     * la suya se quedaria sin guardia. Y cual sea no lo puede ver ArchUnit —cambiar {@code grupos}
     * por {@code usuarios} deja el build en VERDE— asi que lo fija una prueba (#431, #543).
     */
    @GetMapping("/grupos/{grupo}/miembros")
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.UsuarioResource> usuariosDeGrupo(
            @PathVariable("grupo") long grupo, ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.usuariosDeGrupo(grupo, paginacion.aPaginacion("cuenta")),
                Recursos.UsuarioResource::de);
    }

    /**
     * Alta y baja de la pertenencia a un grupo.
     *
     * <p>Un solo endpoint para las dos, con {@code activo} en el cuerpo, porque la baja <b>no es un
     * borrado</b>: es el mismo registro con otro estado (RNF-051). Un {@code DELETE} aqui sugeriria
     * lo contrario a quien lea el contrato.
     *
     * <p>La observacion viaja en el cuerpo y se convierte en el tipo antes de llegar al caso de
     * uso: si viene vacia, el constructor de {@link Observacion} la rechaza y la peticion es 422.
     */
    @PostMapping("/grupos/{grupo}/miembros")
    @RequiereAcceso(acceso = "miembros", privilegio = Privilegio.REGISTRO)
    public Recursos.MiembroResource miembros(
            @PathVariable("grupo") long grupo, @RequestBody Recursos.CambioDeMiembro cambio) {

        Observacion observacion = Observacion.de(cambio.observacion());
        Miembro resultado =
                cambio.activo()
                        ? administrar.afiliar(grupo, cambio.usuarioId(), observacion)
                        : administrar.desafiliar(grupo, cambio.usuarioId(), observacion);

        return new Recursos.MiembroResource(
                resultado.grupoId(), resultado.usuarioId(), resultado.activo());
    }

    // ------------------------------------------------------------------ grupos (#572)

    /**
     * Alta de un grupo: {@code POST /seguridad/grupos} (#572).
     *
     * <p>Un grupo es una fila de esta base y de ninguna otra, asi que aqui no hay nada que
     * coordinar: se escribe, se audita con su observacion y se devuelve con su identificador.
     *
     * <p>El nombre repetido es <b>409</b>, y quien lo garantiza es {@code grupo_nombre_uq} (V5): la
     * comprobacion previa del caso de uso solo <b>nombra</b> el grupo, y el {@code catch} del
     * choque esta porque una carrera se cuela entre la comprobacion y el {@code INSERT} (#489).
     */
    @PostMapping("/grupos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.REGISTRO)
    public Recursos.GrupoResource registrarGrupo(@RequestBody Recursos.AltaDeGrupo alta) {
        Grupo nuevo =
                new Grupo(
                        null,
                        exigir(alta.nombre(), "nombre"),
                        vacioANulo(alta.descripcion()),
                        true,
                        vigenciaDe(alta.vigenciaDesde(), alta.vigenciaHasta()));
        try {
            return Recursos.GrupoResource.de(
                    administrar.registrarGrupo(nuevo, observacionDe(alta.observacion())));
        } catch (AdministrarSeguridad.GrupoRepetido repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (DuplicateKeyException choque) {
            // Ni tabla, ni restriccion, ni SQL: solo lo que el usuario escribio (RNF-033).
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya hay un grupo con ese nombre en esta municipalidad");
        }
    }

    /**
     * Inhabilita un grupo: retira el acceso de todos sus miembros de golpe y <b>no borra ninguna
     * relacion</b> (RNF-051). Exige {@code ELIMINACION} porque quita acceso a gente.
     */
    @PostMapping("/grupos/{id}/baja")
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.ELIMINACION)
    public Recursos.GrupoResource inhabilitarGrupo(
            @PathVariable("id") long grupo, @RequestBody Recursos.MotivoDelCambio motivo) {
        return Recursos.GrupoResource.de(
                administrar.inhabilitarGrupo(grupo, observacionDe(motivo.observacion())));
    }

    /** Vuelve a habilitar un grupo: sus miembros recuperan el acceso que tenian. */
    @PostMapping("/grupos/{id}/reactivacion")
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.MODIFICACION)
    public Recursos.GrupoResource habilitarGrupo(
            @PathVariable("id") long grupo, @RequestBody Recursos.MotivoDelCambio motivo) {
        return Recursos.GrupoResource.de(
                administrar.habilitarGrupo(grupo, observacionDe(motivo.observacion())));
    }

    /** Fija la vigencia de un grupo (RF-123): sin ella caduca sola el dia que toca. */
    @PutMapping("/grupos/{id}/vigencia")
    @RequiereAcceso(acceso = "grupos", privilegio = Privilegio.MODIFICACION)
    public Recursos.GrupoResource fijarVigenciaDeGrupo(
            @PathVariable("id") long grupo, @RequestBody Recursos.CambioDeVigencia cambio) {
        return Recursos.GrupoResource.de(
                administrar.fijarVigenciaDeGrupo(
                        grupo,
                        vigenciaDe(cambio.vigenciaDesde(), cambio.vigenciaHasta()),
                        observacionDe(cambio.observacion())));
    }

    // ------------------------------------------------------------------ usuarios (#572)

    /**
     * Alta de un usuario: {@code POST /seguridad/usuarios} (#572, ADR-0012 §5).
     *
     * <p><b>Escribe la fila del padron, no la cuenta del proveedor.</b> Es una escritura sola en
     * una transaccion, asi que para quien atiende es atomica; lo que no es atomico —ni puede serlo—
     * es el par (cuenta, fila), y por eso el contrato dice con todas las letras que la cuenta se
     * declara aparte. Prometer «usuario creado» seria la unica forma de que quien da de alta no
     * supiera que le falta un paso.
     *
     * <p>La cuenta repetida es <b>409</b>, sostenido por {@code usuario_cuenta_uq} (V5).
     */
    @PostMapping("/usuarios")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.REGISTRO)
    public Recursos.UsuarioResource registrarUsuario(@RequestBody Recursos.AltaDeUsuario alta) {
        Usuario nuevo =
                new Usuario(
                        null,
                        exigir(alta.cuenta(), "cuenta"),
                        // `sujetoOidc` se queda nulo a proposito: ADR-0012 §5.4.
                        null,
                        exigir(alta.nombre(), "nombre"),
                        vacioANulo(alta.correo()),
                        true,
                        vigenciaDe(alta.vigenciaDesde(), alta.vigenciaHasta()));
        try {
            return Recursos.UsuarioResource.de(
                    administrar.registrarUsuario(nuevo, observacionDe(alta.observacion())));
        } catch (AdministrarSeguridad.CuentaRepetida repetida) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetida));
        } catch (DuplicateKeyException choque) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya hay un usuario con esa cuenta en esta municipalidad");
        }
    }

    /**
     * Inhabilita a un usuario: deja de poder entrar, y <b>no se borra</b> (RNF-051). Su fila sigue
     * ahi para que la bitacora pueda seguir diciendo quien hizo que.
     *
     * <p>No toca la cuenta del proveedor de identidad: esa persona seguira autenticando y el
     * guardia le negara todo, que es exactamente lo que una baja significa aqui.
     */
    @PostMapping("/usuarios/{id}/baja")
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.ELIMINACION)
    public Recursos.UsuarioResource inhabilitarUsuario(
            @PathVariable("id") long usuario, @RequestBody Recursos.MotivoDelCambio motivo) {
        return Recursos.UsuarioResource.de(
                administrar.inhabilitarUsuario(usuario, observacionDe(motivo.observacion())));
    }

    /** Vuelve a habilitar a un usuario: recupera los permisos que tenia, sin repetirlos. */
    @PostMapping("/usuarios/{id}/reactivacion")
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.MODIFICACION)
    public Recursos.UsuarioResource habilitarUsuario(
            @PathVariable("id") long usuario, @RequestBody Recursos.MotivoDelCambio motivo) {
        return Recursos.UsuarioResource.de(
                administrar.habilitarUsuario(usuario, observacionDe(motivo.observacion())));
    }

    /**
     * Fija la vigencia de un usuario (RF-123).
     *
     * <p>Es lo que el manual pide para el personal por contrato: su acceso caduca solo el dia que
     * termina, sin depender de que alguien se acuerde de retirarlo.
     */
    @PutMapping("/usuarios/{id}/vigencia")
    @RequiereAcceso(acceso = "usuarios", privilegio = Privilegio.MODIFICACION)
    public Recursos.UsuarioResource fijarVigenciaDeUsuario(
            @PathVariable("id") long usuario, @RequestBody Recursos.CambioDeVigencia cambio) {
        return Recursos.UsuarioResource.de(
                administrar.fijarVigenciaDeUsuario(
                        usuario,
                        vigenciaDe(cambio.vigenciaDesde(), cambio.vigenciaHasta()),
                        observacionDe(cambio.observacion())));
    }

    // ------------------------------------------------------------------ apoyo

    /**
     * La observacion del usuario, obligatoria (regla 10, RNF-052).
     *
     * <p>Se convierte aqui y no se inventa en ningun sitio: la regla de ArchUnit guarda la
     * <b>firma</b> del caso de uso, no el valor, asi que la capa web es justo donde una observacion
     * inventada se colaria sin que nada la viera (#538).
     */
    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Falta la observacion: toda modificacion se guarda con el motivo de quien la"
                            + " hace");
        }
        return Observacion.de(texto);
    }

    private static Vigencia vigenciaDe(@Nullable LocalDate desde, @Nullable LocalDate hasta) {
        try {
            return new Vigencia(desde, hasta);
        } catch (IllegalArgumentException invertida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invertida));
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio: " + campo);
        }
        return valor;
    }

    private static @Nullable String vacioANulo(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    /** El mensaje de la excepcion, que NullAway no da por no nulo. */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
