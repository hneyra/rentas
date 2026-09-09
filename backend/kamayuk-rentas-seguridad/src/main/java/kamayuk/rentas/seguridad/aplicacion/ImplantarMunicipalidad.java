package kamayuk.rentas.seguridad.aplicacion;

import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <h2>La forma de los otros tres, desde la etapa 4 de ADR-0039</h2>
 *
 * <p>Hasta la etapa 4 esta implantacion pasaba por los once casos de uso de administracion y dejaba
 * dos grupos. Esos casos de uso ya no estan aqui —la administracion es de {@code identidad}— asi
 * que la implantacion hace lo mismo que la de catastro, normativa y caja: da de alta la
 * municipalidad, siembra la copia local con {@link SembradorDeLaCopiaLocal} —el catalogo, un grupo,
 * un administrador, sus siete privilegios— y <b>termina cediendole el paso al consumidor del
 * buzon</b>, que corre a continuacion ({@link CorrerElConsumidorDeIdentidad}) y trae lo que {@code
 * identidad} diga de esta municipalidad. Si no hay identidad configurada, lo dice y no falla: en la
 * etapa 4 se admite una copia que nadie actualiza; en la 5 deja de admitirse.
 *
 * <h2>Lo que NO hace</h2>
 *
 * <ul>
 *   <li><b>No crea ninguna clave.</b> El sistema no guarda contrasenas y no las transporta
 *       (ADR-0005): la credencial del administrador vive en Keycloak. Lo que se crea aqui es la
 *       <b>fila</b> del usuario, y lo que une las dos mitades es que {@code usuario.cuenta} sea el
 *       mismo {@code preferred_username} del token.
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

    private final RegistroDeMunicipalidades registro;
    private final SembradorDeLaCopiaLocal sembrador;
    private final DatosDeImplantacion datos;
    private final String buzonDeIdentidad;

    public ImplantarMunicipalidad(
            RegistroDeMunicipalidades registro,
            SembradorDeLaCopiaLocal sembrador,
            DatosDeImplantacion datos,
            @Value("${kamayuk.identidad.url:}") String buzonDeIdentidad) {
        this.registro = registro;
        this.sembrador = sembrador;
        this.datos = datos;
        this.buzonDeIdentidad = buzonDeIdentidad;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        long municipalidadId =
                registro.darDeAltaSiFalta(
                        datos.ubigeo(), datos.nombre(), datos.tipo(), datos.esDemostracion());

        // El perfil batch no tiene filtros HTTP, asi que los dos contextos que en una peticion
        // salen del token se fijan aqui a mano. `Origen.deProceso` existe para esto: una escritura
        // sin peticion detras, que aun asi tiene que decir quien.
        TenantContext.fijar(new MunicipalidadId(municipalidadId));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try {
            int nuevos =
                    sembrador.sembrar(
                            datos.administrador(),
                            datos.nombreDelAdministrador(),
                            Observacion.de(
                                    "Implantacion de la municipalidad "
                                            + datos.ubigeo()
                                            + " en rentas (despliegue)"));

            // El regimen se registra aunque sea una sola palabra: es lo unico del resultado que no
            // se puede comprobar mirando pantallas. Una instalacion que se creia de demostracion y
            // salio real emite papeles sin marca, y quien lo descubre es quien recibe uno (#122).
            log.info(
                    "Municipalidad {} lista en rentas ({}): id {}, {} accesos nuevos,"
                            + " administrador '{}' en el grupo '{}'",
                    datos.ubigeo(),
                    datos.esDemostracion() ? "DEMOSTRACION" : "instalacion real",
                    municipalidadId,
                    nuevos,
                    datos.administrador(),
                    SembradorDeLaCopiaLocal.GRUPO_DE_ADMINISTRACION);
            if (buzonDeIdentidad.isBlank()) {
                log.warn(
                        "No hay identidad configurada (KAMAYUK_IDENTIDAD_URL): la copia local de"
                                + " {} se queda con lo que sembro esta implantacion y NADIE la"
                                + " actualiza. En la etapa 4 de ADR-0039 se admite; en la 5 deja"
                                + " de admitirse, porque la siembra desaparece y todo llega por"
                                + " el buzon",
                        datos.ubigeo());
            } else {
                log.info(
                        "Sembrada la municipalidad {}, el consumidor del buzon de `identidad`"
                                + " ({}) corre a continuacion y trae lo que falte",
                        datos.ubigeo(),
                        buzonDeIdentidad);
            }
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }
}
