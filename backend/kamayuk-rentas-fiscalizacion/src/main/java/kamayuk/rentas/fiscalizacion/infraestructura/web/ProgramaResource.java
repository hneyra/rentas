package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import org.jspecify.annotations.Nullable;

/** Un programa de fiscalización tal como sale por HTTP. Campos en español {@code camelCase}. */
public record ProgramaResource(
        long id,
        String codigo,
        String descripcion,
        String tipo,
        String fechaInicio,
        @Nullable String fechaFin,
        String estado,
        @Nullable String ejercicio,
        @Nullable String sector,
        @Nullable String criterio,
        @Nullable String fiscalizador) {

    public static ProgramaResource de(ProgramaFiscalizacion programa) {
        return new ProgramaResource(
                programa.id() == null ? 0L : programa.id(),
                programa.codigo(),
                programa.descripcion(),
                programa.tipo().name(),
                programa.fechaInicio().toString(),
                programa.fechaFin() == null ? null : programa.fechaFin().toString(),
                programa.estado().name(),
                programa.ejercicio() == null ? null : String.valueOf(programa.ejercicio().valor()),
                programa.sectorCodigo(),
                programa.criterio() == null ? null : programa.criterio().name(),
                programa.fiscalizador());
    }
}
