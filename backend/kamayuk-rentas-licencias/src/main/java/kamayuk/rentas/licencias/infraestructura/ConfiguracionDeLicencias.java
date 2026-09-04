package kamayuk.rentas.licencias.infraestructura;

import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeCertificado;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeEdificacion;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El unico sitio que decide con que plantilla se numera una licencia.
 *
 * <p>{@code EmitirLicenciaDeFuncionamiento} recibe la plantilla por constructor —asi las pruebas
 * emiten con dos plantillas distintas, que es lo que delato el analisis del numero en #40—, y este
 * bean es quien la fija para la aplicacion real: {@link PlantillaDeNumeroDeLicencia#POR_OMISION}
 * mientras D-09 siga abierta. Cerrar la decision es cambiar este metodo (o leer la plantilla de la
 * municipalidad), no tocar el caso de uso.
 *
 * <p>La marcha blanca es la prueba de este cableado: sin el bean, la aplicacion real ni arranca
 * —las pruebas no lo detectan porque instancian el caso de uso a mano—.
 */
@Configuration
class ConfiguracionDeLicencias {

    @Bean
    PlantillaDeNumeroDeLicencia plantillaDeNumeroDeLicencia() {
        return PlantillaDeNumeroDeLicencia.POR_OMISION;
    }

    /**
     * Y la de la licencia de <b>edificacion</b> (#48).
     *
     * <p>Es un bean aparte y de un tipo aparte a proposito: las dos numeraciones son independientes
     * —una obra y un establecimiento no comparten correlativo— y con el mismo tipo Spring tendria
     * dos candidatos y elegiria mal. Es ademas el defecto que la marcha blanca de #44 caza: sin el
     * bean, la aplicacion real no arranca y las pruebas no lo notan, porque instancian el caso de
     * uso a mano.
     */
    @Bean
    PlantillaDeNumeroDeEdificacion plantillaDeNumeroDeEdificacion() {
        return PlantillaDeNumeroDeEdificacion.POR_OMISION;
    }

    /**
     * Y la del certificado (#54).
     *
     * <p>Tercer bean y tercer tipo, por lo mismo que el segundo: las tres numeraciones son
     * independientes —una obra, un establecimiento y un certificado de zonificacion no comparten
     * correlativo— y con el mismo tipo Spring tendria tres candidatos y elegiria mal.
     *
     * <p>La marcha blanca es la prueba de este cableado: sin el bean, la aplicacion real no arranca
     * —las pruebas no lo notan, porque instancian el caso de uso a mano—.
     */
    @Bean
    PlantillaDeNumeroDeCertificado plantillaDeNumeroDeCertificado() {
        return PlantillaDeNumeroDeCertificado.POR_OMISION;
    }
}
