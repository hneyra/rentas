package kamayuk.rentas.tesoreria.aplicacion;

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
 * Da de alta las ventanillas de una municipalidad ya implantada, leyendo un CSV (#430).
 *
 * <p>Corre en el perfil {@code batch}, igual que {@code ImplantarMunicipalidad} y {@code
 * CargarCatalogoVial}, y por la misma razón: es un proceso de arranque de vida corta, sin servidor
 * web, que hace su trabajo y termina. No necesita las credenciales de {@code sgtm_owner} —{@code
 * area} y {@code caja} son tablas de tenant que {@code sgtm_app} ya escribe desde {@code V7}—, así
 * que la municipalidad tiene que existir de antemano: este proceso no la crea.
 *
 * <p><b>Y no exige {@code es_demostracion}</b>, a diferencia de los seis pasos que siembran
 * personas y predios inventados. Una ventanilla no es un dato inventado: es la configuración con la
 * que una municipalidad real abre su caja, y sin ella la primera cobranza falla con {@code
 * CajaInexistente} tanto en la instalación de demostración como en la de verdad.
 *
 * <p>El perfil {@code batch} no tiene filtros HTTP, así que los dos contextos que en una petición
 * salen del token se fijan aquí a mano.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-cajas.archivo")
@EnableConfigurationProperties(DatosDeCargaCajas.class)
public class CargarCajas implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarCajas.class);

    private final ImportarCajas importar;
    private final DatosDeCargaCajas datos;

    public CargarCajas(ImportarCajas importar, DatosDeCargaCajas datos) {
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
                log.warn("Caja de la fila {} rechazada: {}", rechazada.fila(), rechazada.motivo());
            }
            log.info(
                    "Ventanillas de la municipalidad {} cargadas desde {}: {} fila(s) leidas, {}"
                            + " caja(s) nueva(s), {} rechazada(s)",
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
