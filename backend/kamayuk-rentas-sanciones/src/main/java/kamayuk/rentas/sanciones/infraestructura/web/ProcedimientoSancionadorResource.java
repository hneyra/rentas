package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionador;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de «Infracción administrativa» por HTTP (#397). Campos en español {@code camelCase}
 * (ARQ-04 §3).
 *
 * <h2>{@code fase} y {@code estadoDeLaDeuda} son dos campos, y tienen que serlo</h2>
 *
 * <p>El manual habla de dos estados distintos sobre la misma acta —el del procedimiento sancionador
 * y el de la deuda— y ninguno de los dos se renombra para parecerse al otro (RNF-080). Publicarlos
 * con dos nombres es lo que impide que una pantalla dibuje uno bajo el rótulo del otro: no existe
 * ningún campo llamado {@code estado} a secas en este recurso, de modo que quien lo lea tiene que
 * elegir, y al elegir sabe cuál eligió.
 *
 * <p>{@code fase} viaja vacío cuando ninguna de las cinco palabras del manual nombra la fila —un
 * acta anulada o prescrita—, y entonces la grilla dibuja «—». No se sustituye por la más parecida.
 *
 * <h2>Las dos fechas</h2>
 *
 * <p>{@code actualizadoA} es la fecha de la <b>infracción</b>, no la de hoy: {@code importeAPagar}
 * es el importe del acta, congelado al registrarla y no recalculado nunca (regla 9, RNF-075), igual
 * que en {@link PapeletaDelPadronResource}. Lo que se debe hoy es otra cifra —la del libro, con sus
 * intereses— y esta grilla no la pinta.
 *
 * <p>{@code faseAlDia} es la fecha a la que se resolvió {@code fase}, y es otra: un procedimiento
 * cuya notificación preventiva vence mañana estará mañana en otra fase sin que nadie haya tocado
 * una fila. Una sola fecha para las dos cosas haría que una de las dos mintiera.
 */
public record ProcedimientoSancionadorResource(
        long id,
        String numeroActa,
        @Nullable String administrado,
        String codigoCuis,
        String descripcionInfraccion,
        Alicuota porcentajeInfraccion,
        Dinero importeAPagar,
        LocalDate actualizadoA,
        @Nullable String medidaComplementaria,
        @Nullable String fase,
        LocalDate faseAlDia,
        String estadoDeLaDeuda) {

    public static ProcedimientoSancionadorResource de(ProcedimientoSancionador fila) {
        return new ProcedimientoSancionadorResource(
                fila.papeletaId(),
                fila.numeroActa(),
                fila.administrado(),
                fila.codigoCuis(),
                fila.descripcionInfraccion(),
                fila.porcentajeInfraccion(),
                fila.importeAPagar(),
                fila.fechaInfraccion(),
                fila.medidaComplementaria(),
                fila.fase() == null ? null : fila.fase().name(),
                fila.faseAlDia(),
                fila.estadoDeLaDeuda().name());
    }
}
