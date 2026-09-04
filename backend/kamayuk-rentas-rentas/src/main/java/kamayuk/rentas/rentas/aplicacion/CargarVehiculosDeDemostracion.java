package kamayuk.rentas.rentas.aplicacion;

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
 * Siembra vehiculos <b>inventados</b> para que la instalacion de demostracion tenga algo que
 * ensenar en las pantallas que los leen, y solo para eso.
 *
 * <p>Mismo patron de arranque que {@code CargarFichasDeDemostracion} —perfil {@code batch}, la
 * propiedad que lo enciende, los dos contextos fijados a mano porque no hay filtro HTTP— y la misma
 * guarda: antes de leer una sola fila pregunta si la municipalidad en curso esta marcada como de
 * demostracion, y si no lo esta <b>no escribe nada</b>. Ver {@link SoloEnDemostracion}: un {@code
 * --municipalidad-id} equivocado en un digito metia datos ficticios en el padron de una
 * municipalidad que ya opera, y aqui no se borra nada (RNF-051).
 *
 * <p><b>El orden importa.</b> Cada fila nombra por codigo algo que otra carga tuvo que escribir
 * antes; la secuencia completa esta en {@code infra/carga-de-datos/README.md}.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-vehiculos-demo.archivo")
@EnableConfigurationProperties(DatosDeCargaVehiculosDemo.class)
public class CargarVehiculosDeDemostracion implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarVehiculosDeDemostracion.class);

    private final ImportarVehiculos importar;
    private final SoloEnDemostracion soloEnDemostracion;
    private final DatosDeCargaVehiculosDemo datos;

    public CargarVehiculosDeDemostracion(
            ImportarVehiculos importar,
            SoloEnDemostracion soloEnDemostracion,
            DatosDeCargaVehiculosDemo datos) {
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
            soloEnDemostracion.exigirlo("vehiculos ficticios");

            try (Reader archivo =
                    Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
                InformeDeImportacion informe =
                        importar.importar(archivo, Observacion.de(datos.observacion()));

                for (FilaRechazada rechazada : informe.rechazadas()) {
                    log.warn("Fila {} rechazada: {}", rechazada.fila(), rechazada.motivo());
                }
                log.info(
                        "Vehiculos de demostracion de la municipalidad {} sembrados desde {}: {} fila(s)"
                                + " leidas, {} nueva(s), {} rechazada(s)",
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
