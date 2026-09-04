package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.SoloEnDemostracion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Siembra el <b>detalle</b> de las fichas ficticias —construcciones por piso, obras
 * complementarias, actividades economicas, bienes comunes y grupos de tierra— para que las
 * pantallas de la ficha catastral tengan algo dentro que ensenar.
 *
 * <p>Mismo patron de arranque que {@link CargarFichasDeDemostracion} y la misma guarda: antes de
 * leer una sola fila pregunta si la municipalidad en curso esta marcada como de demostracion, y si
 * no lo esta <b>no escribe nada</b>. Ver {@link SoloEnDemostracion}.
 *
 * <p><b>Va justo despues de {@link CargarFichasDeDemostracion}</b>: cada fila nombra su predio por
 * el codigo de referencia catastral, y {@link ImportarDetalleDeFichas} versiona la ficha vigente de
 * ese predio. Sin la ficha inscrita no hay nada que versionar, y el grupo entero se rechaza
 * nombrando el codigo.
 *
 * <p>El informe cuenta <b>fichas versionadas</b> en {@code nuevas} y filas leidas en {@code
 * totalFilas}: aqui varias filas son una escritura, porque una version de ficha es atomica.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-detalle-fichas-demo.archivo")
@EnableConfigurationProperties(DatosDeCargaDetalleFichasDemo.class)
public class CargarDetalleDeFichasDemostracion implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(CargarDetalleDeFichasDemostracion.class);

    private final ImportarDetalleDeFichas importar;
    private final SoloEnDemostracion soloEnDemostracion;
    private final DatosDeCargaDetalleFichasDemo datos;

    public CargarDetalleDeFichasDemostracion(
            ImportarDetalleDeFichas importar,
            SoloEnDemostracion soloEnDemostracion,
            DatosDeCargaDetalleFichasDemo datos) {
        this.importar = importar;
        this.soloEnDemostracion = soloEnDemostracion;
        this.datos = datos;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws IOException {
        TenantContext.fijar(new MunicipalidadId(datos.municipalidadId()));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try {
            // Antes de abrir el archivo: si esto lanza, no se ha escrito nada.
            soloEnDemostracion.exigirlo("el detalle de unas fichas ficticias");

            try (Reader archivo =
                    Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
                InformeDeImportacion informe =
                        importar.importar(archivo, Observacion.de(datos.observacion()));

                for (FilaRechazada rechazada : informe.rechazadas()) {
                    log.warn(
                            "Predio de la fila {} rechazado: {}",
                            rechazada.fila(),
                            rechazada.motivo());
                }
                log.info(
                        "Detalle de fichas de demostracion de la municipalidad {} sembrado desde"
                                + " {}: {} fila(s) leidas, {} ficha(s) versionada(s), {} predio(s)"
                                + " rechazado(s)",
                        datos.municipalidadId(),
                        datos.archivo(),
                        informe.totalFilas(),
                        informe.nuevas(),
                        informe.rechazadas().size());
            }
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }
}
