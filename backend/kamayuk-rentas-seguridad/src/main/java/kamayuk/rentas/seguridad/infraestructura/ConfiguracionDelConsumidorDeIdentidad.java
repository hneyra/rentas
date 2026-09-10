package kamayuk.rentas.seguridad.infraestructura;

import java.time.Clock;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.TokenDeServicioDeKeycloak;
import kamayuk.rentas.seguridad.aplicacion.AplicarUnEventoDeIdentidad;
import kamayuk.rentas.seguridad.aplicacion.ConsumirEventosDeIdentidad;
import kamayuk.rentas.seguridad.aplicacion.PasadaDelConsumidorDeIdentidad;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arma el consumidor del buzon de {@code identidad} en el perfil {@code batch}, solo si hay un
 * buzon al que ir ({@code KAMAYUK_IDENTIDAD_URL}).
 *
 * <p>Sin la propiedad ninguno de estos beans existe, y la implantacion lo dice al terminar. Con
 * ella hacen falta ademas la credencial de servicio —punto de emision, cliente y clave— y a quien
 * avisar; las cinco son de {@code kamayuk.identidad.*} y las pone el mismo descriptor.
 *
 * <p>El proveedor del token se construye AQUI y no se publica como bean: {@code
 * ConfiguracionDelIngestor} ya publica uno para el buzon de `catastro` con la otra clave, y dos
 * beans del mismo tipo obligarian a cada consumidor a nombrar el suyo — la forma mas facil de
 * mandar la clave de `catastro` a `identidad` sin que nada lo diga.
 */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@ConditionalOnProperty("kamayuk.identidad.url")
public class ConfiguracionDelConsumidorDeIdentidad {

    @Bean
    FuenteDeEventosDeIdentidad fuenteDeEventosDeIdentidad(
            JsonMapper json,
            Clock reloj,
            @Value("${kamayuk.identidad.url}") String raiz,
            @Value("${kamayuk.identidad.token:}") String puntoDeEmision,
            @Value("${kamayuk.identidad.cliente:}") String cliente,
            @Value("${kamayuk.identidad.credencial:}") String clave) {
        CredencialDeServicio credencial =
                new TokenDeServicioDeKeycloak(json, reloj, puntoDeEmision, cliente, clave);
        return new ClienteHttpDelBuzonDeIdentidad(json, raiz, credencial);
    }

    /**
     * Quien recibe el aviso. Las dos propiedades son obligatorias y el canal NO tiene que ser
     * http(s): el motivo, medido, esta en {@link ResponsableDelConsumidor}.
     */
    @Bean
    ResponsableDelConsumidor responsableDelConsumidorDeIdentidad(
            @Value("${kamayuk.identidad.responsable:}") String responsable,
            @Value("${kamayuk.identidad.canal:}") String canal) {
        return new ResponsableDelConsumidor(responsable, canal);
    }

    @Bean
    AlertaDeEventosSinAplicar alertaDeEventosSinAplicar(
            JsonMapper json, ResponsableDelConsumidor responsable) {
        return new AlertaAlResponsableDeLaCopiaLocal(json, responsable);
    }

    /**
     * La pasada entera —vueltas y aviso de pospuestos— como bean propio (ADR-0039, etapa 5).
     *
     * <p>La piden DOS: el {@code CronJob} por {@code CorrerElConsumidorDeIdentidad}, y la
     * implantacion, que la llama en linea porque desde la etapa 5 es la unica fuente del
     * administrador. Que este condicionada a {@code kamayuk.identidad.url} es lo que le permite a
     * la implantacion <b>ver que falta</b> y decirlo, en vez de terminar en verde con la copia
     * vacia.
     */
    @Bean
    PasadaDelConsumidorDeIdentidad pasadaDelConsumidorDeIdentidad(
            ConsumirEventosDeIdentidad consumidor, AlertaDeEventosSinAplicar alerta, Clock reloj) {
        return new PasadaDelConsumidorDeIdentidad(consumidor, alerta, reloj);
    }

    @Bean
    ConsumirEventosDeIdentidad consumirEventosDeIdentidad(
            FuenteDeEventosDeIdentidad fuente,
            AplicarUnEventoDeIdentidad aplicador,
            AlertaDeEventosSinAplicar alerta) {
        return new ConsumirEventosDeIdentidad(fuente, aplicador, alerta);
    }
}
