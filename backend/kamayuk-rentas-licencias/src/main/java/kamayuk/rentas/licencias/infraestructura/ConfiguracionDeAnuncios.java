package kamayuk.rentas.licencias.infraestructura;

import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeAnuncio;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El unico sitio que decide con que plantilla se numera una autorizacion de anuncio.
 *
 * <p>Mismo cableado y mismo motivo que {@code ConfiguracionDeLicencias} (#44): {@code
 * RegistrarAnuncio} recibe la plantilla por constructor —asi las pruebas registran con dos
 * plantillas distintas, que es lo que delato el analisis del numero en #40—, y este bean es quien
 * la fija para la aplicacion real: {@link PlantillaDeNumeroDeAnuncio#POR_OMISION} mientras D-09
 * siga abierta. Cerrar la decision es cambiar este metodo (o leer la plantilla de la
 * municipalidad), no tocar el caso de uso.
 *
 * <p><b>La marcha blanca es la prueba de este cableado</b>: sin el bean, la aplicacion real ni
 * arranca —las pruebas no lo detectan porque instancian el caso de uso a mano—. Es la leccion que
 * #44 dejo escrita, y por eso este archivo existe antes que su primera prueba.
 */
@Configuration
class ConfiguracionDeAnuncios {

    @Bean
    PlantillaDeNumeroDeAnuncio plantillaDeNumeroDeAnuncio() {
        return PlantillaDeNumeroDeAnuncio.POR_OMISION;
    }
}
