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
 * Carga el catalogo de sectores de una municipalidad ya implantada (#121), leyendo un CSV {@code
 * codigo,nombre,zona}.
 *
 * <p>Copia exacta del patron de {@link CargarCatalogoVial}: perfil {@code batch} porque es un
 * proceso de arranque de vida corta sin servidor web, credenciales de {@code sgtm_app} —{@code
 * sector} es una tabla de tenant—, y los dos contextos que en una peticion salen del token fijados
 * aqui a mano, porque el perfil {@code batch} no tiene filtros HTTP.
 *
 * <h2>La secuencia: sectores antes que manzanas</h2>
 *
 * <p>El archivo de manzanas referencia su sector <b>por codigo</b>, y {@link ImportarManzanas}
 * rechaza la fila cuyo sector no existe. Asi que este proceso corre antes que {@link
 * CargarManzanas}; al reves, el informe sale con todas las filas rechazadas y ninguna manzana
 * cargada. Es lo mismo que ya documenta {@link ImportarManzanas} para los importadores.
 *
 * <p>El informe se registra completo —cuantas filas entraron y cuales se rechazaron, con su
 * motivo—. Una fila rechazada no aborta el proceso, pero tiene que quedar visible en el log de
 * quien corrio la carga.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-sectores.archivo")
@EnableConfigurationProperties(DatosDeCargaSectores.class)
public class CargarSectores implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarSectores.class);

    private final ImportarSectores importar;
    private final DatosDeCargaSectores datos;

    public CargarSectores(ImportarSectores importar, DatosDeCargaSectores datos) {
        this.importar = importar;
        this.datos = datos;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws IOException {
        TenantContext.fijar(new MunicipalidadId(datos.municipalidadId()));
        OrigenContext.fijar(Origen.deProceso(datos.usuarioDelProceso()));
        try (Reader archivo =
                Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
            InformeDeImportacion informe =
                    importar.importar(archivo, Observacion.de(datos.observacion()));

            for (FilaRechazada rechazada : informe.rechazadas()) {
                log.warn(
                        "Sector de la fila {} rechazado: {}", rechazada.fila(), rechazada.motivo());
            }
            log.info(
                    "Sectores de la municipalidad {} cargados desde {}: {} fila(s) leidas, {}"
                            + " sector(es) nuevo(s), {} rechazada(s)",
                    datos.municipalidadId(),
                    datos.archivo(),
                    informe.totalFilas(),
                    informe.nuevas(),
                    informe.rechazadas().size());
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }
}
