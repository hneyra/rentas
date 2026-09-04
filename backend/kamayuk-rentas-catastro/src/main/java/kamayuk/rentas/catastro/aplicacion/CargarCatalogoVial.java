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
 * Carga el catalogo vial de una municipalidad ya implantada, leyendo el CSV que produce {@code
 * importar_arancel_via_gpkg.py} o cualquier otro con el mismo formato (#121).
 *
 * <p>Corre en el perfil {@code batch}, igual que {@link
 * kamayuk.rentas.seguridad.aplicacion.ImplantarMunicipalidad} y por la misma razon: es un proceso
 * de arranque de vida corta, sin servidor web, que hace su trabajo y termina. A diferencia de la
 * implantacion, no necesita las credenciales de {@code sgtm_owner} —{@code via} es una tabla de
 * tenant que {@code sgtm_app} ya puede escribir—, asi que la municipalidad tiene que existir de
 * antemano: este proceso no la crea.
 *
 * <p>El perfil {@code batch} no tiene filtros HTTP, asi que los dos contextos que en una peticion
 * salen del token se fijan aqui a mano, igual que hace la implantacion.
 *
 * <p>El informe de {@link ImportarVias#importar} se registra completo: cuantas filas entraron y
 * cuales se rechazaron, con su motivo. Una fila rechazada no aborta el proceso —es exactamente el
 * comportamiento que #121 pide—, pero tiene que quedar visible en el log de quien corrio la carga.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.carga-vial.archivo")
@EnableConfigurationProperties(DatosDeCargaVial.class)
public class CargarCatalogoVial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CargarCatalogoVial.class);

    private final ImportarVias importar;
    private final DatosDeCargaVial datos;

    public CargarCatalogoVial(ImportarVias importar, DatosDeCargaVial datos) {
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
                log.warn("Via de la fila {} rechazada: {}", rechazada.fila(), rechazada.motivo());
            }
            log.info(
                    "Catalogo vial de la municipalidad {} cargado desde {}: {} fila(s) leidas, {}"
                            + " via(s) nueva(s), {} rechazada(s)",
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
