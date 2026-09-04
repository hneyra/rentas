package kamayuk.rentas.rentas.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.rentas.dominio.Beneficio;
import kamayuk.rentas.rentas.dominio.BeneficioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta y cese de beneficios y exoneraciones (RF-029).
 *
 * <p>Sigue la plantilla de {@code ActualizarFichaCatastral}: el beneficio ya trae su propia {@link
 * Observacion} —es dato de la fila, {@code beneficio.observacion NOT NULL}— y ademas se pide por
 * separado, porque es el mismo argumento el que satisface la regla 10 en la firma y el que se
 * guarda en la columna.
 *
 * <p><b>No calcula nada.</b> No aplica el beneficio a ningun importe: eso es D-02. Lo unico que
 * decide este caso de uso es si el beneficio se puede registrar tal como llega.
 */
@Service
public class RegistrarBeneficio {

    private final BeneficioRepository repositorio;
    private final Auditoria auditoria;

    public RegistrarBeneficio(BeneficioRepository repositorio, Auditoria auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    /**
     * Da de alta un beneficio, rechazando el que se solape con otro vigente del mismo tipo para el
     * mismo contribuyente.
     */
    @Transactional
    public Beneficio registrar(Beneficio beneficio, Observacion observacion) {
        rechazarSolapado(beneficio);

        Beneficio guardado = repositorio.insertar(beneficio);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                beneficio.vigenciaDesde(),
                                "beneficio",
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));

        return guardado;
    }

    /** Cesa un beneficio vigente. No lo borra: deja la fila con {@code vigenciaHasta}. */
    @Transactional
    public Beneficio cesar(long id, LocalDate fecha, Observacion observacion) {
        Beneficio vigente =
                repositorio.findById(id).orElseThrow(() -> new BeneficioInexistente(id));

        Beneficio cesado = repositorio.actualizar(vigente.cesadoEl(fecha));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha, "beneficio", String.valueOf(id), Operacion.BAJA, observacion)
                        .con(descripcion(vigente), descripcion(cesado)));

        return cesado;
    }

    /**
     * Rechaza el alta si ya hay un beneficio del mismo tipo, para el mismo contribuyente, con la
     * vigencia solapada.
     */
    private void rechazarSolapado(Beneficio beneficio) {
        List<Beneficio> delMismoTipo =
                repositorio.delContribuyente(beneficio.contribuyenteId(), beneficio.tipo());
        boolean haySolape = delMismoTipo.stream().anyMatch(beneficio::solapaCon);
        if (haySolape) {
            throw new VigenciaSolapada(beneficio.contribuyenteId(), beneficio.tipo());
        }
    }

    private static String descripcion(Beneficio beneficio) {
        return "{\"contribuyenteId\":"
                + beneficio.contribuyenteId()
                + ",\"tipo\":\""
                + beneficio.tipo()
                + "\",\"clase\":\""
                + beneficio.clase()
                + "\",\"tributo\":\""
                + beneficio.tributo()
                + "\",\"vigenciaDesde\":\""
                + beneficio.vigenciaDesde()
                + "\",\"vigenciaHasta\":"
                + (beneficio.vigenciaHasta() == null
                        ? "null"
                        : "\"" + beneficio.vigenciaHasta() + "\"")
                + "}";
    }

    /** Ya hay un beneficio del mismo tipo vigente en ese rango para el contribuyente. */
    public static final class VigenciaSolapada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VigenciaSolapada(long contribuyenteId, String tipo) {
            super(
                    "El contribuyente "
                            + contribuyenteId
                            + " ya tiene un beneficio de tipo "
                            + tipo
                            + " vigente en ese rango de fechas");
        }
    }

    /** No hay ningun beneficio con ese identificador, o es de otra municipalidad. */
    public static final class BeneficioInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BeneficioInexistente(long id) {
            super("No hay ningun beneficio con identificador " + id + " en esta municipalidad");
        }
    }
}
