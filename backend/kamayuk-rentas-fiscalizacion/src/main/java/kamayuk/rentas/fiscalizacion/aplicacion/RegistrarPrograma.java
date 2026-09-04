package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Programa la muestra de predios o vehículos que entran a un proceso de fiscalización (RF-050,
 * #45).
 *
 * <p><b>Reprogramar no borra el programa anterior</b> (AC de #45): no hay ningún método de
 * actualización aquí. Un programa que reemplaza a otro es un programa nuevo, con su propio código;
 * el anterior queda tal cual, lo haya usado alguna acta o no.
 */
@Service
public class RegistrarPrograma {

    private static final String TABLA_AUDITADA = "programa_fiscalizacion";

    private final ProgramaFiscalizacionRepository repositorio;
    private final Auditoria auditoria;

    public RegistrarPrograma(ProgramaFiscalizacionRepository repositorio, Auditoria auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    /**
     * @param ejercicio qué ejercicio examina; con {@code criterio} y {@code fiscalizador}, uno de
     *     los tres parámetros sin los cuales el programa no puede sortear su muestra ({@code V60}).
     *     Van opcionales porque los programas anteriores a esa migración están así en la base, y
     *     {@code GenerarMuestra} falla nombrando el que falte
     * @param sectorCodigo sobre qué sector se sortea; su nulo significa «todo el distrito», que es
     *     una respuesta y no una falta
     */
    @Transactional
    public ProgramaFiscalizacion registrar(
            String codigo,
            String descripcion,
            TipoDePrograma tipo,
            LocalDate fechaInicio,
            @Nullable LocalDate fechaFin,
            @Nullable Ejercicio ejercicio,
            @Nullable String sectorCodigo,
            @Nullable CondicionFiscalizada criterio,
            @Nullable String fiscalizador,
            Observacion observacion) {

        ProgramaFiscalizacion guardado =
                repositorio.insertar(
                        ProgramaFiscalizacion.nuevo(
                                codigo,
                                descripcion,
                                tipo,
                                fechaInicio,
                                fechaFin,
                                ejercicio,
                                sectorCodigo,
                                criterio,
                                fiscalizador));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fechaInicio,
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));

        return guardado;
    }

    private static String descripcion(ProgramaFiscalizacion programa) {
        return "{\"codigo\":\""
                + programa.codigo()
                + "\",\"tipo\":\""
                + programa.tipo()
                + "\",\"estado\":\""
                + programa.estado()
                + "\"}";
    }
}
