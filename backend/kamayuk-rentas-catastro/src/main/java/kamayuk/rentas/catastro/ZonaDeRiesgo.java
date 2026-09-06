package kamayuk.rentas.catastro;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Una zona de riesgo que cruza el lote.
 *
 * <p><b>{@code mitigable} va al lado de {@code nivel} y no en su lugar</b>: el nivel dice cuanto
 * peligro hay y {@code mitigable} dice si se puede hacer algo, y es el segundo el que decide. Leer
 * solo el nivel dejaria creyendo que MUY_ALTO impide siempre, que es falso —una zona MUY ALTO
 * mitigable se construye con su obra de mitigacion—.
 *
 * <p><b>El nivel es texto y no un enumerado de este sistema.</b> El vocabulario lo fija la carta de
 * peligro que emite el organismo tecnico; una enumeracion cerrada aqui obligaria a desplegar {@code
 * rentas} para admitir un nivel nuevo, y mientras tanto la lectura fallaria sobre un dato que es
 * correcto.
 *
 * <p><b>Sin el poligono</b>, y no es un olvido: la pregunta es que cruza el lote, y la respuesta es
 * la zona y no su geometria —que puede tener miles de vertices y ya se uso dentro de la consulta—.
 * Dibujarla es del visor del plano, que vive en {@code catastro} (ADR-0022).
 */
public record ZonaDeRiesgo(
        long id,
        String codigo,
        String fenomeno,
        String nivel,
        boolean mitigable,
        String fuente,
        String documentoOrigen,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta) {}
