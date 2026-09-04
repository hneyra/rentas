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
 * Carga los lotes del plano catastral de una municipalidad ya implantada (ADR-0021, #400).
 *
 * <p>Mismo patron que {@link CargarCatalogoVial}: perfil {@code batch}, un proceso de vida corta
 * sin servidor web, que fija a mano los dos contextos que en una peticion salen del token.
 *
 * <p><b>Y sin la guarda de demostracion, a proposito.</b> Los seis pasos de {@code
 * sembrar-demostracion.sh} que meten datos inventados exigen {@code es_demostracion = true} contra
 * la base antes de leer una fila; este no, porque el plano catastral de una municipalidad no es un
 * dato inventado: es su padron. Sin este cargador, {@link ImportarFichas} solo era alcanzable desde
 * {@link CargarFichasDeDemostracion}, de modo que una instalacion de verdad no tenia por donde
 * poblar su catastro —el mismo hueco que #430 encontro para {@code area} y {@code caja}—.
 *
 * <p>El informe se registra completo: cuantos lotes nacieron, cuantos ya estaban y solo recibieron
 * su poligono, y cuales se rechazaron con su motivo. Una fila rechazada no aborta la corrida, pero
 * tiene que quedar visible en el log de quien la corrio.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-predios.archivo")
@EnableConfigurationProperties(DatosDeCargaPredios.class)
public class CargarPredios implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarPredios.class);

    private final ImportarPrediosDelPlano importar;
    private final DatosDeCargaPredios datos;

    public CargarPredios(ImportarPrediosDelPlano importar, DatosDeCargaPredios datos) {
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
                log.warn("Lote de la fila {} rechazado: {}", rechazada.fila(), rechazada.motivo());
            }
            log.info(
                    "Plano de la municipalidad {} cargado desde {}: {} fila(s) leidas, {} predio(s)"
                            + " nuevo(s), {} ya existente(s) con su poligono puesto, {}"
                            + " rechazada(s)",
                    datos.municipalidadId(),
                    datos.archivo(),
                    informe.totalFilas(),
                    informe.nuevas(),
                    informe.totalFilas() - informe.nuevas() - informe.rechazadas().size(),
                    informe.rechazadas().size());
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }
}
