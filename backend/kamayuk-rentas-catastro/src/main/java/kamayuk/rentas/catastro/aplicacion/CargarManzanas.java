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
 * Carga las manzanas de una municipalidad ya implantada (#121), leyendo un CSV {@code
 * sectorCodigo,codigo}.
 *
 * <p>Copia exacta del patron de {@link CargarCatalogoVial}, con la misma razon para el perfil
 * {@code batch} y para fijar a mano los dos contextos que en una peticion salen del token.
 *
 * <h2>La secuencia: este proceso va el ultimo</h2>
 *
 * <p>Cada fila referencia su sector <b>por codigo</b> —lo que trae el archivo, no un identificador
 * interno—, y {@link ImportarManzanas} rechaza la fila cuyo sector no existe todavia. El orden es
 * entonces {@link CargarCatalogoVial} y {@link CargarSectores} primero, este despues. Correrlo
 * antes no rompe nada ni deja datos a medias —cada fila abre su propia transaccion—: deja un
 * informe con todas las filas rechazadas, que es un sintoma facil de leer y de repetir.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-manzanas.archivo")
@EnableConfigurationProperties(DatosDeCargaManzanas.class)
public class CargarManzanas implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarManzanas.class);

    private final ImportarManzanas importar;
    private final DatosDeCargaManzanas datos;

    public CargarManzanas(ImportarManzanas importar, DatosDeCargaManzanas datos) {
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
                        "Manzana de la fila {} rechazada: {}",
                        rechazada.fila(),
                        rechazada.motivo());
            }
            log.info(
                    "Manzanas de la municipalidad {} cargadas desde {}: {} fila(s) leidas, {}"
                            + " manzana(s) nueva(s), {} rechazada(s)",
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
