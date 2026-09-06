package kamayuk.rentas.catastro;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;

/**
 * Una faja marginal que cruza el lote: la restriccion de dominio publico hidraulico que fija la
 * ANA, con su ancho.
 *
 * <p><b>El ancho es una {@link Medida} y no un decimal desnudo</b>, por lo mismo que un importe es
 * {@code Dinero}: un decimal a secas no dice de que es la cifra ni en que unidad, y aqui un decimal
 * de mas o de menos mueve un lindero (ADR-0021). {@code catastro} lo publica en metros lineales y
 * con la unidad en el nombre del campo ({@code anchoM}); este lado la vuelve a poner dentro del
 * dato, que es donde no se puede perder.
 *
 * <p>Va aparte de {@link ZonaDeRiesgo} y no dentro, igual que en {@code catastro}: meterla dentro
 * obligaria a inventarle un {@code nivel} y un {@code mitigable} que ninguna resolucion de la ANA
 * le dio — y {@code mitigable} es justo el campo del que cuelga la decision.
 */
public record FajaMarginal(
        long id,
        String codigo,
        String cuerpoDeAgua,
        Medida ancho,
        String fuente,
        String documentoOrigen,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta) {}
