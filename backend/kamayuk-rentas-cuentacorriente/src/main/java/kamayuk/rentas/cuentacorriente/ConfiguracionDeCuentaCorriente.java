package kamayuk.rentas.cuentacorriente;

import java.math.RoundingMode;
import java.util.Locale;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cablea {@link CalculoDeDeuda} con la unica {@link PoliticaDeMora} que existe hoy.
 *
 * <p>Vive en el paquete raiz del modulo, como {@code ConfiguracionDeTenant}: es lo unico que otro
 * modulo puede ver, y Spring Modulith trata como interno todo lo que esta en un subpaquete.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeCuentaCorriente {

    @Bean
    CalculoDeDeuda calculoDeDeuda(PoliticaDeMora mora) {
        return new CalculoDeDeuda(mora);
    }

    /**
     * La escala y el modo de redondeo, <b>leidos de la configuracion</b>, no compilados.
     *
     * <p>Es lo que {@link PoliticaDeRedondeo} pide en su propio javadoc: mientras D-03a y D-03b
     * sigan abiertas, «el dia que D-03 se cierre habra exactamente un lugar donde escribir la
     * respuesta —los datos de parametrizacion— y ni una constante que buscar en el codigo». Un
     * {@code RoundingMode.HALF_UP} escrito aqui seria justamente esa constante, y el escaner de
     * fuentes lo rechaza (regla 5, {@code RevisorDeCodigoFuente}).
     *
     * <p>Los valores viven en {@code application.yaml}, que es dato versionado y no codigo: se
     * cambian sin desplegar y el diff dice cuando cambiaron. Hoy no llegan a aplicarse a ningun
     * importe —{@code SinAcumulacion}, la unica {@link PoliticaDeMora} que hay, no redondea nada—,
     * pero el bean tiene que existir para que {@code ConsultarDeuda} se pueda construir.
     */
    @Bean
    PoliticaDeRedondeo redondeoDeDeuda(
            @Value("${kamayuk.redondeo.escala}") int escala,
            @Value("${kamayuk.redondeo.modo}") String modo) {
        return new PoliticaDeRedondeo(
                escala, RoundingMode.valueOf(modo.strip().toUpperCase(Locale.ROOT)));
    }
}
