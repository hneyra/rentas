package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra un programa de fiscalización: la <b>única</b> transición que este sistema escribe sobre él
 * (#341).
 *
 * <h2>Por qué hacía falta</h2>
 *
 * <p>La exclusión de #481 —«no sortees un predio que otro programa {@code ABIERTO} o {@code
 * EN_PROCESO} ya se llevó»— dependía de un estado que ningún camino de producción cambiaba: el
 * programa nacía {@code ABIERTO}, su tipo no tenía ningún método que moviera {@code estado}, no
 * había un solo {@code UPDATE programa_fiscalizacion} en {@code src/main} y ninguna ruta lo
 * cerraba. Así que un omiso crónico sorteado una vez salía en {@code excluidosPorOtroPrograma} de
 * todo programa futuro, del ejercicio que fuera, y la respuesta le atribuía la exclusión a «otro
 * programa» sin que nadie pudiera corregirlo desde la aplicación. Tres comentarios afirmaban lo
 * contrario, y la prueba que los respaldaba cerraba el programa con SQL crudo, como el dueño.
 *
 * <h2>Por qué es un acto y no una derivación</h2>
 *
 * <p>Ninguna fila de ninguna tabla dice que un programa terminó: ni la muestra sorteada, ni las
 * actas levantadas, ni {@code fecha_fin} —que es el plazo que se programó, y un programa puede
 * terminar antes o seguir después—. Es una decisión de la administración, igual que anular un acta
 * ({@link AnularActaFiscalizacion}, #214) o una papeleta ({@code AnularPapeleta}, #267), y por eso
 * se escribe con su caso de uso, su observación (regla 10), la fecha del acto (regla 9) y su
 * auditoría. {@code EN_PROCESO} no: si hace falta, se deriva de «tiene acta levantada».
 *
 * <h2>Lo que no hace</h2>
 *
 * <p>No acota la exclusión por ejercicio ni por criterio —eso cambia la regla de negocio de #481 y
 * se decide aparte— y no toca lo que el programa sorteó: la muestra se sigue leyendo entera y las
 * actas levantadas sobre ella siguen valiendo. Cerrar sólo dice que el programa ya no retiene sus
 * predios.
 */
@Service
public class CerrarProgramaFiscalizacion {

    private static final String TABLA_AUDITADA = "programa_fiscalizacion";

    private final ProgramaFiscalizacionRepository programas;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CerrarProgramaFiscalizacion(
            ProgramaFiscalizacionRepository programas, Auditoria auditoria, Clock reloj) {
        this.programas = programas;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cierra el programa.
     *
     * @param programaId cuál se cierra
     * @param fecha el día del acto, no el de su registro (regla 9); ni anterior al inicio del
     *     programa ni posterior a hoy (#402)
     * @param observacion por qué se cierra (regla 10, RNF-052)
     * @throws ProgramaInexistente si no hay ninguno con ese identificador en esta municipalidad
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior al inicio del
     *     programa o posterior a hoy
     * @throws ProgramaFiscalizacion.TransicionIlegal si ya estaba cerrado
     */
    @Transactional
    public ProgramaFiscalizacion cerrar(long programaId, LocalDate fecha, Observacion observacion) {
        ProgramaFiscalizacion antes =
                programas
                        .findById(programaId)
                        .orElseThrow(() -> new ProgramaInexistente(programaId));

        OrdenDeLosActos.exigir(
                "el cierre del programa " + antes.codigo(),
                fecha,
                LocalDate.now(reloj),
                new OrdenDeLosActos.ActoPrevio(
                        "el inicio del programa " + antes.codigo(), antes.fechaInicio()));

        ProgramaFiscalizacion cerrado = programas.cerrar(programaId);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(programaId),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(antes), descripcionDelCierre(cerrado, fecha)));

        return cerrado;
    }

    private static String descripcion(ProgramaFiscalizacion programa) {
        return "{\"estado\":\"" + programa.estado() + "\"}";
    }

    /**
     * El estado cerrado con el día del acto. La tabla no tiene columna para ese día —{@code
     * fecha_fin} es el plazo programado y no se toca—, así que el dato del acto viaja aquí, como
     * {@code fechaAnulacion} en la anulación del acta.
     */
    private static String descripcionDelCierre(ProgramaFiscalizacion programa, LocalDate fecha) {
        return "{\"estado\":\"" + programa.estado() + "\",\"fechaCierre\":\"" + fecha + "\"}";
    }

    /** No hay ningún programa con ese identificador en esta municipalidad. */
    public static final class ProgramaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProgramaInexistente(long id) {
            super("No hay ningun programa de fiscalizacion con identificador " + id);
        }
    }
}
