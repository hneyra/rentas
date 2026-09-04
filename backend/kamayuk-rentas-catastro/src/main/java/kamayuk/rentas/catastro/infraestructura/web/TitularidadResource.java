package kamayuk.rentas.catastro.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.catastro.dominio.Titularidad;
import org.jspecify.annotations.Nullable;

/**
 * Una cuota de titularidad, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04
 * §3).
 *
 * <p>Lleva el {@code contribuyenteId} porque es la respuesta al acto de <b>registrarlo</b>: quien
 * acaba de declarar de quien es el predio ya sabe de quien es. La lectura del titular —{@code GET
 * /catastro/predios/{predioId}/titulares}— es otra cosa y no lo publica, porque ahi la pregunta la
 * hace quien todavia no lo sabe (ADR-0015 §2.4).
 *
 * <p>{@code porcentaje} viaja como texto: es un objeto de valor y un numero JSON perderia escala.
 */
public record TitularidadResource(
        long titularidadId,
        long predioId,
        long contribuyenteId,
        String condicion,
        String porcentaje,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    public static TitularidadResource de(Titularidad titularidad) {
        return new TitularidadResource(
                titularidad.id() == null ? 0L : titularidad.id(),
                titularidad.predioId(),
                titularidad.contribuyenteId(),
                titularidad.condicion().name(),
                titularidad.porcentaje().valor().toPlainString(),
                titularidad.vigenciaDesde(),
                titularidad.vigenciaHasta(),
                titularidad.documentoOrigen());
    }
}
