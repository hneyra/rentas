package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.time.Clock;
import javax.sql.DataSource;
import kamayuk.rentas.nucleo.aplicacion.AlertaDeHechosSinAplicar;
import kamayuk.rentas.nucleo.aplicacion.AplicarUnHecho;
import kamayuk.rentas.nucleo.aplicacion.IngestarHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.PoliticaDeLoQueNoAvanza;
import kamayuk.rentas.plataforma.PoolDeUnRol;
import kamayuk.rentas.plataforma.ResponsableDeOperacion;
import kamayuk.rentas.plataforma.TokenDeServicioDeKeycloak;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cablea el ingestor de {@code catastro} (C-8).
 *
 * <h2>Un SEGUNDO pool, con otro rol, y por eso todo esto esta aqui y no en anotaciones</h2>
 *
 * <p>El ingestor escribe {@code predio_ref}, {@code ficha_ref}, {@code valuacion_predio} y {@code
 * valuacion_corrida}, y `V4` y `V5` no le dan a {@code kamayuk_app} mas que {@code SELECT} sobre
 * las cuatro. Quien las escribe es {@code rol_ingestor_catastro}. Asi que esto construye:
 *
 * <ol>
 *   <li>un pool propio con ESA credencial, con {@link PoolDeUnRol}: lleva el mismo guardia que el
 *       de la aplicacion —ninguna conexion vuelve al pool con {@code app.municipalidad_id} puesto—
 *       y el mismo gestor de transacciones, que es lo que emite el {@code SET LOCAL};
 *   <li>y las piezas del ingestor colgadas de el.
 * </ol>
 *
 * <h2>Solo en el perfil {@code batch}, y solo si esta configurado</h2>
 *
 * <p>{@code @Profile("batch")} porque el proceso que atiende la ventanilla <b>no debe tener</b> una
 * credencial capaz de escribir la proyeccion: si la tuviera, «la proyeccion es de solo lectura para
 * la aplicacion» dejaria de ser un privilegio y volveria a ser disciplina — que es exactamente lo
 * que `V4` dice que no quiere ser.
 *
 * <p>Y {@code @ConditionalOnProperty} porque el perfil {@code batch} corre muchas cosas mas: la
 * implantacion, las cargas, la anti-entropia. Sin la propiedad, este cableado no existe y ninguna
 * de ellas necesita la credencial del ingestor.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@ConditionalOnProperty("kamayuk.rentas.ingestor.usuario")
public class ConfiguracionDelIngestor {

    /** El nombre del gestor de transacciones que {@link AplicarUnHecho} nombra. */
    public static final String TRANSACCIONES = AplicarUnHecho.TRANSACCIONES;

    /** El nombre del pool del ingestor. Quien lo necesite lo pide por este nombre, y solo asi. */
    static final String FUENTE = "fuenteDelIngestor";

    /**
     * El pool del ingestor.
     *
     * <p>Pequeño a proposito: el ingestor aplica un hecho por transaccion y en un solo hilo. Un
     * pool grande aqui no aceleraria nada y competiria por las conexiones del motor con el proceso
     * que atiende la ventanilla.
     */
    /*
     * `defaultCandidate = false` en el pool Y en su gestor, y es lo que arregla rentas#70.
     *
     * Sin eso, estos dos beans compiten por TIPO con los de la plataforma, y el defecto tiene dos
     * mitades. La que da error: «required a single bean, but 2 were found: transaccionesDelIngestor,
     * transactionManager», y el `CronJob` no arrancaba. La que NO da error, medida el 2026-09-13:
     * un `@Bean` de tipo `DataSource` hace que la autoconfiguracion del pool de Boot se retire (lo
     * avisa `ConfiguracionDeTenant`), asi que el UNICO pool del contexto era este, y el gestor de la
     * plataforma conectaba como `rol_ingestor_catastro`. Arreglar la primera mitad con un `@Qualifier`
     * en quien pide el gestor dejaba el contexto arrancando con ese rol en todo lo que no es el
     * ingestor — `ArranqueDeLaAplicacionTest` lo mide preguntando `current_user` a cada gestor.
     *
     * Con `defaultCandidate = false` no cuentan para la inyeccion por tipo ni para las condiciones de
     * la autoconfiguracion: solo los recibe quien los nombra —`AplicarUnHecho` por su
     * `@Transactional(transactionManager = TRANSACCIONES)`, y los dos beans de abajo por `@Qualifier`—.
     */
    @Bean(name = FUENTE, defaultCandidate = false)
    DataSource fuenteDelIngestor(
            @Value("${spring.datasource.url}") String url,
            @Value("${kamayuk.rentas.ingestor.usuario}") String usuario,
            @Value("${kamayuk.rentas.ingestor.clave}") String clave) {
        return PoolDeUnRol.con(url, usuario, clave, 2, "ingestor-catastro");
    }

    @Bean(name = TRANSACCIONES, defaultCandidate = false)
    PlatformTransactionManager transaccionesDelIngestor(
            @Qualifier(FUENTE) DataSource fuenteDelIngestor) {
        return PoolDeUnRol.transaccionesDe(fuenteDelIngestor);
    }

    @Bean
    ProyeccionDeCatastro proyeccionDeCatastro(
            @Qualifier(FUENTE) DataSource fuenteDelIngestor, JsonMapper json) {
        return new ProyeccionDeCatastroJdbc(
                JdbcClient.create(fuenteDelIngestor), new CuerpoDelHecho(json));
    }

    @Bean
    AplicarUnHecho aplicarUnHecho(ProyeccionDeCatastro proyeccion) {
        return new AplicarUnHecho(proyeccion);
    }

    /**
     * Quien recibe el aviso. Las dos propiedades son obligatorias y el canal NO tiene que ser
     * http(s): el motivo, medido (#70), esta en {@link ResponsableDeOperacion}, que desde #377 es
     * la misma clase para los dos consumidores de buzon.
     *
     * <p>No se publica como bean: el consumidor de {@code identidad} construye el suyo en el mismo
     * perfil, y dos beans del mismo tipo obligarian a cada alerta a nombrar el suyo — la forma mas
     * facil de avisar al responsable equivocado sin que nada lo diga.
     */
    static ResponsableDeOperacion responsableDeLaProyeccion(String nombre, String canal) {
        return new ResponsableDeOperacion(
                nombre,
                canal,
                "Faltan kamayuk.rentas.ingestor.responsable y/o .canal. No son opcionales:"
                        + " ADR-0026 §4 exige que un hecho que no se pudo aplicar avise A UNA"
                        + " PERSONA CON NOMBRE. Mientras ese hecho este sin aplicar, la"
                        + " proyeccion del padron dice algo que `catastro` ya no dice y ninguna"
                        + " cifra lo delata: el ingestor no arranca hasta que alguien diga quien"
                        + " lo recibe");
    }

    @Bean
    AlertaDeHechosSinAplicar alertaDeHechosSinAplicar(
            JsonMapper json,
            @Value("${kamayuk.rentas.ingestor.responsable:}") String nombre,
            @Value("${kamayuk.rentas.ingestor.canal:}") String canal) {
        return new AlertaAlCanalDelResponsable(json, responsableDeLaProyeccion(nombre, canal));
    }

    /**
     * De donde sale el {@code Authorization} del ingestor (#21 AC-2).
     *
     * <p>Es un {@code @Bean} de esta configuracion y no un {@code @Component}: el ingestor solo
     * existe en el perfil de lotes, y un componente descubierto por barrido se construiria tambien
     * en el proceso web, que no llama a nadie y no tiene por que tener una credencial de servicio.
     */
    @Bean
    CredencialDeServicio credencialDeServicio(
            JsonMapper json,
            java.time.Clock reloj,
            @Value("${kamayuk.rentas.ingestor.identidad.token:}") String punto,
            @Value("${kamayuk.rentas.ingestor.identidad.cliente:}") String cliente,
            @Value("${kamayuk.catastro.credencial:}") String clave) {
        return new TokenDeServicioDeKeycloak(json, reloj, punto, cliente, clave);
    }

    @Bean
    FuenteDeHechosDeCatastro fuenteDeHechosDeCatastro(
            JsonMapper json,
            @Value("${kamayuk.catastro.url:}") String raiz,
            CredencialDeServicio credencial) {
        return new ClienteHttpDelBuzonDeCatastro(json, raiz, credencial);
    }

    @Bean
    kamayuk.rentas.nucleo.aplicacion.CorrerElIngestor correrElIngestor(
            IngestarHechosDeCatastro ingestor,
            @Value("${kamayuk.rentas.ingestor.municipalidad}") long municipalidadId) {
        return new kamayuk.rentas.nucleo.aplicacion.CorrerElIngestor(ingestor, municipalidadId);
    }

    @Bean
    IngestarHechosDeCatastro ingestarHechosDeCatastro(
            FuenteDeHechosDeCatastro fuente,
            AplicarUnHecho aplicador,
            AlertaDeHechosSinAplicar alerta,
            Clock reloj) {
        // Lo que no se sabe aplicar se APARTA en el acto con `SIN_CAPACIDAD:<tipo>` (#377):
        // esperarlo no lo trae, y ocupando la cabeza paraba todo el padron de detras.
        return new IngestarHechosDeCatastro(
                fuente,
                aplicador,
                alerta,
                PoliticaDeLoQueNoAvanza.apartarLoQueNoSeSabeAplicar(),
                reloj);
    }
}
