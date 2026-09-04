package kamayuk.rentas.rentas.infraestructura;

import kamayuk.rentas.rentas.dominio.PlantillaDeNumeroDeDeclaracion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El unico sitio que decide con que plantilla se numera una declaracion jurada (#365).
 *
 * <p>{@code RegistrarDeclaracionJurada} recibe la plantilla por constructor —asi las pruebas
 * numeran con dos plantillas distintas, que es lo que delato el analisis del numero en #40— y este
 * bean es quien la fija para la aplicacion real: {@link PlantillaDeNumeroDeDeclaracion#POR_OMISION}
 * mientras D-09 siga abierta. Cerrar la decision es cambiar este metodo (o leer la plantilla de la
 * municipalidad), no tocar el caso de uso.
 *
 * <p>Mismo patron que {@code ConfiguracionDeLicencias}, y con la misma advertencia: <b>la marcha
 * blanca es la prueba de este cableado</b>. Sin el bean la aplicacion real no arranca, y las
 * pruebas no lo notan porque instancian el caso de uso a mano.
 */
@Configuration
class ConfiguracionDeRentas {

    @Bean
    PlantillaDeNumeroDeDeclaracion plantillaDeNumeroDeDeclaracion() {
        return PlantillaDeNumeroDeDeclaracion.POR_OMISION;
    }
}
