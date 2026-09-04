package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import org.jspecify.annotations.Nullable;

/**
 * Cómo sale la etapa «criterio» de una generación masiva de valores por papeletas (#53, RF-066,
 * RF-073).
 *
 * <p><b>No trae ningún valor emitido</b>, y no puede: al momento en que esta respuesta se entrega
 * —al registrar el criterio— no se ha generado ninguno. La etapa de generación corre aparte, en el
 * perfil batch (ADR-0003), y lo que hasta ahí se emite se consulta con {@code valores_busqueda} o
 * en el padrón de coactiva.
 *
 * <p>Tampoco trae un número de corrida propio distinto del identificador: el <b>número</b> que
 * importa es el de cada resolución de multa, y lo pone {@code valor_correlativo} (V26) cuando se
 * emite. Aquí no hay nada numerado.
 */
public record CorridaDeValoresResource(
        long id,
        String familia,
        LocalDate desde,
        LocalDate hasta,
        LocalDate fechaCriterio,
        String origen,
        int totalCandidatos,
        @Nullable String usuarioRegistro,
        String observacion) {

    public static CorridaDeValoresResource de(CorridaDeValores corrida) {
        return new CorridaDeValoresResource(
                corrida.identificador(),
                corrida.familia().name(),
                corrida.desde(),
                corrida.hasta(),
                corrida.fechaCriterio(),
                corrida.origen().name(),
                corrida.totalCandidatos(),
                corrida.usuarioRegistro(),
                corrida.observacion().texto());
    }
}
