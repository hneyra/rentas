package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.time.Clock;
import javax.sql.DataSource;
import kamayuk.rentas.nucleo.aplicacion.AlertaDeHechosSinAplicar;
import kamayuk.rentas.nucleo.aplicacion.AplicarUnHecho;
import kamayuk.rentas.nucleo.aplicacion.IngestarHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import kamayuk.rentas.plataforma.PoolDeUnRol;
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

    /**
     * El pool del ingestor.
     *
     * <p>Pequeño a proposito: el ingestor aplica un hecho por transaccion y en un solo hilo. Un
     * pool grande aqui no aceleraria nada y competiria por las conexiones del motor con el proceso
     * que atiende la ventanilla.
     */
    @Bean
    DataSource fuenteDelIngestor(
            @Value("${spring.datasource.url}") String url,
            @Value("${kamayuk.rentas.ingestor.usuario}") String usuario,
            @Value("${kamayuk.rentas.ingestor.clave}") String clave) {
        return PoolDeUnRol.con(url, usuario, clave, 2, "ingestor-catastro");
    }

    @Bean(TRANSACCIONES)
    PlatformTransactionManager transaccionesDelIngestor(DataSource fuenteDelIngestor) {
        return PoolDeUnRol.transaccionesDe(fuenteDelIngestor);
    }

    @Bean
    ProyeccionDeCatastro proyeccionDeCatastro(DataSource fuenteDelIngestor, JsonMapper json) {
        return new ProyeccionDeCatastroJdbc(
                JdbcClient.create(fuenteDelIngestor), new CuerpoDelHecho(json));
    }

    @Bean
    AplicarUnHecho aplicarUnHecho(ProyeccionDeCatastro proyeccion) {
        return new AplicarUnHecho(proyeccion);
    }

    @Bean
    ResponsableDeLaProyeccion responsableDeLaProyeccion(
            @Value("${kamayuk.rentas.ingestor.responsable:}") String nombre,
            @Value("${kamayuk.rentas.ingestor.canal:}") String canal) {
        return new ResponsableDeLaProyeccion(nombre, canal);
    }

    @Bean
    AlertaDeHechosSinAplicar alertaDeHechosSinAplicar(
            JsonMapper json, ResponsableDeLaProyeccion responsable) {
        return new AlertaAlCanalDelResponsable(json, responsable);
    }

    @Bean
    FuenteDeHechosDeCatastro fuenteDeHechosDeCatastro(
            JsonMapper json,
            @Value("${kamayuk.catastro.url:}") String raiz,
            @Value("${kamayuk.catastro.credencial:}") String credencial) {
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
        return new IngestarHechosDeCatastro(fuente, aplicador, alerta, reloj);
    }
}
