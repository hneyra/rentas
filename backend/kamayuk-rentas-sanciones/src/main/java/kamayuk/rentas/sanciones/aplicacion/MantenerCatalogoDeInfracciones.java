package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.Familia;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta y versionado del catálogo de códigos de infracción (#43). Quien mantiene este catálogo no es
 * quien impone papeletas: la separación la da el privilegio de cada opción (#8), no esta clase.
 *
 * <p>No hay «editar en el sitio»: {@link #modificar} cierra la versión vigente y guarda una nueva —
 * la anterior queda (regla 4). La {@link Observacion} está en la firma de los dos métodos, no en el
 * cuerpo de una petición (regla 10, RNF-052).
 */
@Service
public class MantenerCatalogoDeInfracciones {

    private final CodigoInfraccionRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public MantenerCatalogoDeInfracciones(
            CodigoInfraccionRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Un código nuevo del catálogo, sin versión previa. */
    @Transactional
    public CodigoInfraccion registrar(CodigoInfraccion codigo, Observacion observacion) {
        CodigoInfraccion guardado = repositorio.insertar(codigo);
        auditar(guardado, Operacion.ALTA, observacion);
        return guardado;
    }

    /**
     * Cierra la versión vigente de {@code familia}/{@code codigo} el día anterior a cuando empieza
     * a regir {@code nuevaVersion}, y guarda esta última (regla 4: la anterior queda, no se pisa).
     */
    @Transactional
    public CodigoInfraccion modificar(
            Familia familia,
            String codigo,
            CodigoInfraccion nuevaVersion,
            Observacion observacion) {
        LocalDate cierre = nuevaVersion.vigenciaDesde().minusDays(1);
        CodigoInfraccion vigente =
                repositorio
                        .vigenteA(familia, codigo, cierre)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No hay ninguna version vigente de "
                                                        + familia
                                                        + " "
                                                        + codigo
                                                        + " antes del "
                                                        + nuevaVersion.vigenciaDesde()));

        repositorio.actualizar(vigente.cerradoEl(cierre));
        CodigoInfraccion guardada = repositorio.insertar(nuevaVersion);
        auditar(guardada, Operacion.MODIFICACION, observacion);
        return guardada;
    }

    private void auditar(CodigoInfraccion codigo, Operacion operacion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                        LocalDate.now(reloj),
                        "codigo_infraccion",
                        String.valueOf(codigo.id()),
                        operacion,
                        observacion));
    }
}
