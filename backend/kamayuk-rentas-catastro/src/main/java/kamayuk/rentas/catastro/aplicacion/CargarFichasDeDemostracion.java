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
 * Siembra predios fichados <b>inventados</b> para que la instalacion de demostracion tenga un
 * catastro que mostrar, y solo para eso (#290).
 *
 * <p>Mismo patron de arranque que {@link CargarCatalogoVial}, con la misma guarda que el cargador
 * gemelo de contribuyentes: antes de leer una sola fila pregunta si la municipalidad en curso esta
 * marcada como de demostracion, y si no lo esta no escribe nada. Ver {@link SoloEnDemostracion}.
 *
 * <h2>La secuencia</h2>
 *
 * <p>Este proceso va el ultimo de todos. Cada fila nombra su sector, su manzana, su via y su
 * contribuyente <b>por codigo</b>, y {@link InscribirFicha} rechaza la fila que nombre algo que no
 * existe: hacen falta {@link CargarCatalogoVial}, {@link CargarSectores}, {@link CargarManzanas} y
 * la siembra de contribuyentes antes que este.
 *
 * <p><b>Lo que no siembra:</b> ni aranceles, ni valores unitarios de edificacion, ni tablas de
 * depreciacion. Son valores normativos —D-02a, D-13— y sus pantallas tienen que seguir diciendo
 * «sin conjunto sellado»: un arancel inventado se distingue de uno real solo por quien lo puso.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-fichas-demo.archivo")
@EnableConfigurationProperties(DatosDeCargaFichasDemo.class)
public class CargarFichasDeDemostracion implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarFichasDeDemostracion.class);

    private final ImportarFichas importar;
    private final SoloEnDemostracion soloEnDemostracion;
    private final DatosDeCargaFichasDemo datos;

    public CargarFichasDeDemostracion(
            ImportarFichas importar,
            SoloEnDemostracion soloEnDemostracion,
            DatosDeCargaFichasDemo datos) {
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
            soloEnDemostracion.exigirlo("un catastro de predios ficticios");

            try (Reader archivo =
                    Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
                InformeDeImportacion informe =
                        importar.importar(archivo, Observacion.de(datos.observacion()));

                for (FilaRechazada rechazada : informe.rechazadas()) {
                    log.warn(
                            "Ficha de la fila {} rechazada: {}",
                            rechazada.fila(),
                            rechazada.motivo());
                }
                log.info(
                        "Fichas de demostracion de la municipalidad {} sembradas desde {}: {}"
                                + " fila(s) leidas, {} inscrita(s), {} rechazada(s)",
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
