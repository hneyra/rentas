package kamayuk.rentas.seguridad.aplicacion;

import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.seguridad.dominio.MunicipalidadImplantada;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pone una municipalidad dentro del sistema: sin esto no hay nada que autorizar.
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
 * <b>solo {@code kamayuk_owner}</b>: un endpoint que lo hiciera le exigiria a {@code kamayuk_app}
 * un privilegio que se le quito a proposito, y seria el camino mas corto de una pantalla de alta a
 * una escalada entre municipalidades. Corre en el perfil {@code batch}: sin servidor web, sin
 * puerto expuesto y con vida corta. Las credenciales de {@code kamayuk_owner} entran <b>solo</b>
 * aqui, para <b>un</b> {@code INSERT}, en una conexion que se abre y se cierra en el paso 1.
 *
 * <h2>Tres pasos desde la etapa 5 de ADR-0039</h2>
 *
 * <ol>
 *   <li>Da de alta la municipalidad.
 *   <li>Siembra el <b>catalogo</b> de este sistema con {@link SembradorDelCatalogo} —{@code
 *       modulo_sistema} y {@code acceso}, o sea que pantallas existen—.
 *   <li>Corre <b>una pasada del consumidor del buzon</b> y comprueba el resultado.
 * </ol>
 *
 * <p>El paso 3 es lo que la etapa 5 cambia, y no es un adorno: hasta la etapa 4 el arranque en frio
 * —el grupo de administracion, el primer administrador, su afiliacion y sus permisos— lo escribia
 * el sembrador con SQL directo, de modo que habia <b>dos origenes</b> para las mismas cuatro tablas
 * y el de aqui solo agregaba. Retirado el sembrador, la unica forma de que alguien pueda entrar es
 * que el buzon de {@code identidad} lo traiga. Por eso la pasada se llama <b>en linea</b> y no se
 * encadena por {@code @Order}: sin {@code KAMAYUK_IDENTIDAD_URL} el runner del consumidor <b>no
 * existe</b> —{@code @ConditionalOnProperty}— y este {@code Job} saldria {@code Complete} con la
 * copia vacia, sin una linea que lo dijera. Es el {@code Job} roto de C-18 con otra cara, y este
 * repositorio ya lo pago una vez.
 *
 * <h2>Lo que se comprueba, y lo que NO</h2>
 *
 * <p>Se comprueba el <b>resultado</b>: que despues de la pasada la copia local tenga alguna cuenta.
 * <b>No</b> se comprueba que no queden eventos pospuestos, y la diferencia importa: un pospuesto es
 * un evento cuya dependencia esta en camino, se reintenta solo, y el {@code CronJob} que corre cada
 * cinco minutos <b>avisa</b> de el sin fallar (AC-5/AC-6 de `identidad`#4). Hacer fallar la
 * implantacion por un pospuesto convertiria ese mismo camino en un {@code Job} que falla cada cinco
 * minutos y al que nadie mira. Lo que no puede pasar es lo contrario: terminar en verde sin que
 * nadie pueda entrar.
 *
 * <p><b>Y el resultado decide tambien cuando el buzon no contesta</b> (#453). {@code
 * IdentidadNoContesta} es un 503 de un {@code identidad} que se reinicia, un 403 de una afiliacion,
 * un token que el emisor no entrega: todo transitorio o de despliegue, y nada de ello dice si la
 * copia esta vacia. En la primera implantacion lo esta, y se falla como siempre. En un
 * <b>redespliegue</b> —un {@code Job} nuevo por cada {@code sha}— la copia ya tiene sus cuentas y
 * el {@code Deployment} autoriza con ellas: fallar ahi dejaba el despliegue de `rentas` atascado
 * por la disponibilidad de otro sistema y mandaba al operador a buscar una copia vacia que no
 * existia. Asi que la causa solo elige el mensaje; lo que decide es si hay cuentas.
 *
 * <h2>Lo que NO hace</h2>
 *
 * <ul>
 *   <li><b>No da de alta a nadie.</b> Ni al administrador: lo crea {@code identidad}, que es el
 *       dueño de la autorizacion, y llega aqui por el buzon. Este sistema no guarda contrasenas ni
 *       las transporta (ADR-0005), y desde la etapa 5 tampoco decide quien existe.
 *   <li><b>No fija ningun ejercicio de trabajo.</b> El ejercicio vive en {@code sesion}, es de cada
 *       sesion y se elige al entrar.
 * </ul>
 */
@Component
@Profile("batch")
@ConditionalOnProperty("kamayuk.implantacion.ubigeo")
@EnableConfigurationProperties(DatosDeImplantacion.class)
@Order(ImplantarMunicipalidad.ORDEN)
public class ImplantarMunicipalidad implements ApplicationRunner {

    /** Antes que el consumidor del buzon: primero la municipalidad, despues lo que le llega. */
    public static final int ORDEN = 100;

    private static final Logger log = LoggerFactory.getLogger(ImplantarMunicipalidad.class);

    /** El remedio es el mismo en los tres desenlaces, y se escribe una vez. */
    private static final String REMEDIO =
            " Remedio: implantar `identidad` PRIMERO —es el dueño de la autorizacion y quien crea"
                    + " el administrador— y dejar que este Job vuelva a correr. El orden de los"
                    + " cinco despliegues no es indiferente desde la etapa 5 de ADR-0039";

    private final RegistroDeMunicipalidades registro;
    private final SembradorDelCatalogo sembrador;
    private final LecturaDeLaCopiaLocal copiaLocal;
    private final DatosDeImplantacion datos;

    /**
     * La pasada del buzon, si el despliegue dijo donde esta.
     *
     * <p>{@code ObjectProvider} y no el bean a secas: {@code ConfiguracionDelConsumidorDeIdentidad}
     * esta condicionada a {@code kamayuk.identidad.url}, asi que sin esa variable el bean no
     * existe. Pedirlo por constructor haria fallar el contexto con «no such bean», que manda a
     * mirar el cableado; pedirlo asi deja que <b>esta</b> clase diga lo que de verdad falta.
     */
    private final @Nullable PasadaDelConsumidorDeIdentidad pasada;

    public ImplantarMunicipalidad(
            RegistroDeMunicipalidades registro,
            SembradorDelCatalogo sembrador,
            LecturaDeLaCopiaLocal copiaLocal,
            DatosDeImplantacion datos,
            ObjectProvider<PasadaDelConsumidorDeIdentidad> pasada) {
        this.registro = registro;
        this.sembrador = sembrador;
        this.copiaLocal = copiaLocal;
        this.datos = datos;
        this.pasada = pasada.getIfAvailable();
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        MunicipalidadImplantada implantada =
                registro.darDeAltaSiFalta(
                        datos.ubigeo(), datos.nombre(), datos.tipo(), datos.esDemostracion());
        avisarSiElRegimenNoEsElPedido(implantada);

        // El perfil batch no tiene filtros HTTP, asi que los dos contextos que en una peticion
        // salen del token se fijan aqui a mano. `Origen.deProceso` existe para esto: una escritura
        // sin peticion detras, que aun asi tiene que decir quien.
        TenantContext.fijar(new MunicipalidadId(implantada.id()));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try {
            int nuevos =
                    sembrador.sembrar(
                            Observacion.de(
                                    "Implantacion de la municipalidad "
                                            + datos.ubigeo()
                                            + " en rentas (despliegue)"));

            // El regimen se registra aunque sea una sola palabra: es lo unico del resultado que no
            // se puede comprobar mirando pantallas. Una instalacion que se creia de demostracion y
            // salio real emite papeles sin marca, y quien lo descubre es quien recibe uno (#122).
            // Y se registra el de la FILA, no el pedido: relanzar no cambia la marca, asi que el
            // pedido puede ser el contrario de lo que sale en los papeles (#348).
            log.info(
                    "Catalogo de rentas sembrado para la municipalidad {} ({}): id {}, {} accesos"
                            + " nuevos",
                    datos.ubigeo(),
                    implantada.regimen(),
                    implantada.id(),
                    nuevos);

            traerLaAutorizacion();
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }

    /**
     * El despliegue pidio un regimen y la fila tiene el otro (#348): se dice, con los dos valores y
     * con como se cambia.
     *
     * <p>Pasa al relanzar con la variable cambiada: el alta no toca una fila que ya existe, y eso
     * es a proposito (#122) —una instalacion no deja de ser de demostracion porque alguien relance
     * el despliegue con otra variable—. Lo que no puede pasar es que el registro afirme el regimen
     * pedido: la linea del regimen es el unico sitio donde se comprueba sin mirar un papel.
     *
     * <p><b>Un {@code ERROR} y no un fallo del {@code Job}</b>, y es una eleccion: la discrepancia
     * no deja a la municipalidad a medias —la fila es coherente y los papeles salen segun ella— y
     * no la arregla este proceso, que no cambia la marca. Hacer fallar el {@code Job} dejaria cada
     * despliegue siguiente de `rentas` en rojo, sin sembrar las pantallas nuevas del catalogo ni
     * traer la autorizacion, hasta que alguien con {@code kamayuk_owner} decidiera cual de los dos
     * tiene razon. Es el mismo criterio que {@link #avisarSiFaltaElAdministradorDeclarado()}: lo
     * declarado no cuadra con lo que hay, se dice fuerte, y lo que hay manda.
     */
    private void avisarSiElRegimenNoEsElPedido(MunicipalidadImplantada implantada) {
        if (implantada.esDemostracion() == datos.esDemostracion()) {
            return;
        }
        log.error(
                "La municipalidad {} ya estaba dada de alta con es_demostracion = {} y este"
                        + " despliegue pidio es_demostracion = {}"
                        + " (KAMAYUK_IMPLANTACION_ESDEMOSTRACION). Relanzar la implantacion NO"
                        + " cambia la marca (#122): todo documento que emita sigue saliendo {}. Si"
                        + " la que tiene razon es la fila, se corrige la variable del descriptor."
                        + " Si es el descriptor, se cambia a mano como kamayuk_owner —UPDATE"
                        + " municipalidad SET es_demostracion = {} WHERE ubigeo = '{}'— y se"
                        + " reinician los procesos de rentas, porque RegimenDeLaInstalacionJdbc"
                        + " guarda el regimen en cache",
                datos.ubigeo(),
                implantada.esDemostracion(),
                datos.esDemostracion(),
                implantada.esDemostracion()
                        ? "MARCADO como de demostracion"
                        : "SIN la marca de demostracion",
                datos.esDemostracion(),
                datos.ubigeo());
    }

    /**
     * El paso 3: la pasada del buzon, y las TRES negativas, que se arreglan de tres maneras.
     *
     * <p>«No hay buzon configurado» se arregla dandole al {@code Job} sus variables; «el buzon no
     * contesta» se arregla en el despliegue —una clave, una afiliacion, un pod que no esta— y es
     * transitorio; y «el buzon contesto y no trajo ni una cuenta» se arregla implantando {@code
     * identidad}, que es otra cosa y en otro repositorio. Colapsarlas en un solo mensaje mandaria a
     * dos de cada tres casos a mirar donde no es.
     *
     * <p>La segunda solo es negativa si la copia se queda VACIA (#453): con cuentas —un
     * redespliegue, o una pasada que cayo a mitad despues de aplicar parte— la copia se queda como
     * estaba, se avisa y el {@code Job} termina. La falta de buzon configurado no entra en eso: es
     * configuracion, y no se cura sola.
     */
    private void traerLaAutorizacion() {
        if (pasada == null) {
            throw new SinLaAutorizacionDeIdentidad(
                    "La municipalidad "
                            + datos.ubigeo()
                            + " quedo con su catalogo sembrado y SIN NADIE que pueda entrar: no"
                            + " hay buzon de `identidad` configurado (falta KAMAYUK_IDENTIDAD_URL"
                            + " en este Job). Desde la etapa 5 de ADR-0039 este sistema ya no"
                            + " siembra usuarios, grupos ni permisos —los trae el buzon—, asi que"
                            + " terminar aqui dejaria un Job en `Complete` con una copia vacia."
                            + REMEDIO);
        }
        int aplicados;
        try {
            aplicados = pasada.hastaAgotar();
        } catch (FuenteDeEventosDeIdentidad.IdentidadNoContesta noContesta) {
            // Se cuenta ANTES de decidir (#453): lanzar aqui sin mirar afirmaba «sin una sola
            // cuenta» de una copia que en un redespliegue tiene todas las suyas.
            long cuentasQueHabia = copiaLocal.usuariosEnLaCopia();
            if (cuentasQueHabia > 0) {
                seQuedaComoEstaba(pasada, noContesta, cuentasQueHabia);
                return;
            }
            throw new SinLaAutorizacionDeIdentidad(
                    "No se pudo leer el buzon de `identidad` al implantar la municipalidad "
                            + datos.ubigeo()
                            + ", asi que su copia local se queda sin una sola cuenta: "
                            + noContesta.getMessage()
                            + "."
                            + REMEDIO,
                    noContesta);
        }
        long cuentas = copiaLocal.usuariosEnLaCopia();
        if (cuentas == 0) {
            throw new SinLaAutorizacionDeIdentidad(
                    "El buzon de `identidad` contesto y no trajo ni una cuenta para la"
                            + " municipalidad "
                            + datos.ubigeo()
                            + ": la copia local de `rentas` se queda con su catalogo y con CERO"
                            + " usuarios, o sea implantada y sin que nadie pueda entrar —«"
                            + datos.administrador()
                            + "», que este despliegue declara como administrador, tampoco esta—."
                            + " Lo mas probable es que `identidad` todavia no haya implantado esta"
                            + " municipalidad: su buzon solo publica lo que su propia implantacion"
                            + " escribio."
                            + REMEDIO);
        }
        log.info(
                "La autorizacion de la municipalidad {} llego por el buzon de `identidad`: {}"
                        + " evento(s) aplicados y {} cuenta(s) en la copia local",
                datos.ubigeo(),
                aplicados,
                cuentas);
        avisarSiFaltaElAdministradorDeclarado();
    }

    /**
     * El buzon no contesto y la copia TIENE cuentas: se queda como estaba, se dice con la causa, y
     * el {@code Job} termina bien (#453).
     *
     * <p>Es el mismo criterio que {@link #avisarSiFaltaElAdministradorDeclarado()}: fallar dejaria
     * todo despliegue de `rentas` en rojo por la disponibilidad de otro sistema, y no impide que
     * nadie entre —ahi hay cuentas—. Y en el {@code Job} de implantacion nadie mas lo reintenta —el
     * runner del consumidor se aparta alli—: lo que la pone al dia es el {@code CronJob}. El aviso
     * sale ademas al responsable de la copia, porque una linea de {@code WARN} en el registro de un
     * {@code Job} que termino bien no la lee nadie.
     */
    private void seQuedaComoEstaba(
            PasadaDelConsumidorDeIdentidad pasada,
            FuenteDeEventosDeIdentidad.IdentidadNoContesta noContesta,
            long cuentas) {
        log.warn(
                "No se pudo leer el buzon de `identidad` al implantar la municipalidad {}: {}. Su"
                        + " copia local tiene {} cuenta(s) y se queda como estaba —el Deployment"
                        + " sigue autorizando con ella—, asi que la implantacion termina; el"
                        + " CronJob del consumidor la pone al dia en cuanto `identidad` conteste",
                datos.ubigeo(),
                noContesta.getMessage(),
                cuentas,
                noContesta);
        pasada.avisarQueLaCopiaSeQuedaComoEstaba(noContesta, cuentas);
    }

    /**
     * Un aviso, no un fallo, y el motivo esta medido.
     *
     * <p>El despliegue declara en {@code KAMAYUK_IMPLANTACION_ADMINISTRADOR} que cuenta es la del
     * primer administrador —la misma que recibe {@code identidad}, del mismo sitio— y desde la
     * etapa 5 este sistema ya no la crea: la comprueba. Que no este es una inconsistencia real y
     * hay que decirla, con el nombre dentro.
     *
     * <p><b>Y no falla</b>, al reves que la copia vacia: renombrar o dar de baja al administrador
     * en {@code identidad} es un acto legitimo de quien administra, y hacerlo fallar dejaria todo
     * despliegue posterior de `rentas` en rojo por una decision que se tomo en otro sistema y que
     * no impide que nadie entre —ahi hay cuentas, y son las que {@code identidad} dice—.
     */
    private void avisarSiFaltaElAdministradorDeclarado() {
        String cuenta = datos.administrador();
        if (copiaLocal.usuarioPorCuenta(cuenta).isEmpty()) {
            log.warn(
                    "Este despliegue declara «{}» ({}) como administrador de la municipalidad {}"
                            + " y esa cuenta NO llego por el buzon de `identidad`. La copia local"
                            + " tiene otras cuentas, asi que la municipalidad se puede usar; lo"
                            + " que no cuadra es quien manda en ella. Se arregla en `identidad`,"
                            + " que es donde se da de alta, o corrigiendo la variable de este Job",
                    cuenta,
                    datos.nombreDelAdministrador(),
                    datos.ubigeo());
        }
    }

    /**
     * La implantacion no pudo traer la autorizacion, asi que no hay implantacion.
     *
     * <p>Sube y mata el proceso a proposito: un {@code Job} de implantacion que sale con codigo 0
     * se lee en Kubernetes como {@code Complete}, y eso es lo que C-18 encontro haciendo el {@code
     * Job} de `rentas` durante meses —arrancaba, no hacia nada y salia bien—.
     */
    public static final class SinLaAutorizacionDeIdentidad extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinLaAutorizacionDeIdentidad(String mensaje) {
            super(mensaje);
        }

        SinLaAutorizacionDeIdentidad(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
