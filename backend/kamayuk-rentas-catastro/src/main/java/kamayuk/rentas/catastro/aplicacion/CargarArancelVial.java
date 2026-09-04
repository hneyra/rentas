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
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Carga el arancel de terreno por via de una municipalidad, contra un conjunto de parametros que el
 * llamador ya abrio (docs/10-negocio/valores-normativos/aranceles-2026.md S1.4).
 *
 * <p>Corre en el perfil {@code batch}, igual que {@link CargarCatalogoVial} y por la misma razon.
 * No abre ni sella ningun conjunto: eso es {@code AdministrarParametros}, del contexto {@code
 * parametros}, y esta clase solo conoce su paquete raiz (ARQ-01 §4, regla 1). El operador que corre
 * esta carga tiene que haber llamado antes a {@code AdministrarParametros.abrirVersion} —por el
 * proceso batch {@code AbrirConjuntoDeParametros}, que imprime el identificador (#247 §2)— y pasar
 * aqui el identificador que devolvio.
 *
 * <p>Se ejecuta una vez por ejercicio: el CSV que produce {@code importar_arancel_via_gpkg.py} trae
 * un archivo {@code arancel_<ejercicio>.csv} distinto para cada uno, y cada ejercicio cuelga de su
 * propio conjunto.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-arancel.archivo")
@EnableConfigurationProperties(DatosDeCargaArancel.class)
public class CargarArancelVial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarArancelVial.class);

    private final ImportarArancel importar;
    private final DatosDeCargaArancel datos;

    public CargarArancelVial(ImportarArancel importar, DatosDeCargaArancel datos) {
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
                    importar.importar(
                            archivo,
                            new IdentificadorDeConjunto(datos.conjuntoId()),
                            Observacion.de(datos.observacion()));

            for (FilaRechazada rechazada : informe.rechazadas()) {
                log.warn(
                        "Arancel de la fila {} rechazado: {}",
                        rechazada.fila(),
                        rechazada.motivo());
            }
            log.info(
                    "Arancel de la municipalidad {} cargado desde {} contra el conjunto {}: {}"
                            + " fila(s) leidas, {} nueva(s), {} rechazada(s)",
                    datos.municipalidadId(),
                    datos.archivo(),
                    datos.conjuntoId(),
                    informe.totalFilas(),
                    informe.nuevas(),
                    informe.rechazadas().size());
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }
}
