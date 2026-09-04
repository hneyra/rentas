package kamayuk.rentas.contribuyentes.aplicacion;

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
 * Siembra un padron de contribuyentes <b>inventados</b> para que la instalacion de demostracion
 * tenga a quien enlazar predios, y solo para eso (#290).
 *
 * <p>Mismo patron de arranque que {@code CargarCatalogoVial} —perfil {@code batch}, la propiedad
 * que lo enciende, los dos contextos fijados a mano porque no hay filtro HTTP— con <b>una
 * diferencia que no se negocia</b>.
 *
 * <h2>La guarda</h2>
 *
 * <p>Antes de leer una sola fila pregunta si la municipalidad en curso esta marcada como de
 * demostracion, y si no lo esta <b>no escribe nada</b>: ver {@link SoloEnDemostracion} para el
 * porque completo. Resumido: un {@code --municipalidad-id} equivocado en un digito metia ocho
 * personas que no existen en el padron de una municipalidad que ya opera, y aqui no se borra nada
 * (RNF-051).
 *
 * <p>La guarda esta en este proceso y no en {@link ImportarContribuyentes} a proposito: el
 * importador es el camino por el que un dia entrara un padron <b>real</b> migrado, y exigirle una
 * instalacion de demostracion lo dejaria inservible para su propio proposito. Lo que es de
 * demostracion es esta carga, no el mecanismo de leer un archivo.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-contribuyentes-demo.archivo")
@EnableConfigurationProperties(DatosDeCargaContribuyentesDemo.class)
public class CargarContribuyentesDeDemostracion implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(CargarContribuyentesDeDemostracion.class);

    private final ImportarContribuyentes importar;
    private final SoloEnDemostracion soloEnDemostracion;
    private final DatosDeCargaContribuyentesDemo datos;

    public CargarContribuyentesDeDemostracion(
            ImportarContribuyentes importar,
            SoloEnDemostracion soloEnDemostracion,
            DatosDeCargaContribuyentesDemo datos) {
        this.importar = importar;
        this.soloEnDemostracion = soloEnDemostracion;
        this.datos = datos;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws IOException {
        TenantContext.fijar(new MunicipalidadId(datos.municipalidadId()));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try {
            // Antes de abrir el archivo: si esto lanza, no se ha escrito nada y no hay nada
            // que deshacer.
            soloEnDemostracion.exigirlo("un padron de contribuyentes ficticio");

            try (Reader archivo =
                    Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
                InformeDeImportacion informe =
                        importar.importar(archivo, Observacion.de(datos.observacion()));

                for (FilaRechazada rechazada : informe.rechazadas()) {
                    log.warn(
                            "Contribuyente de la fila {} rechazado: {}",
                            rechazada.fila(),
                            rechazada.motivo());
                }
                log.info(
                        "Contribuyentes de demostracion de la municipalidad {} sembrados desde {}:"
                                + " {} fila(s) leidas, {} nuevo(s), {} rechazada(s)",
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
