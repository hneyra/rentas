package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de los padrones y los records de papeletas, por HTTP (#53).
 *
 * <p><b>{@code actualizadoA} es la fecha de la infracción</b>, y no la de hoy. {@code
 * importeAPagar} es el importe <b>del acta</b>, congelado al registrar la papeleta y no recalculado
 * nunca (regla 9, RNF-075). Lo que se debe hoy es otra cifra —la del libro, con sus intereses— y
 * este padrón no la pinta: pedirla por fila serían tantas consultas al libro como filas tenga la
 * página, y una pantalla de doscientas filas tardaría segundos en abrirse.
 *
 * <p>Poner aquí «hoy» haría que la cifra y su fecha dijeran cosas distintas, que es peor que no
 * tener fecha: parecería actualizada.
 */
public record PapeletaDelPadronResource(
        String numero,
        String familia,
        LocalDate fechaInfraccion,
        @Nullable String horaInfraccion,
        String lugar,
        @Nullable String placa,
        @Nullable String licenciaConducir,
        String codigoInfraccion,
        String descripcionInfraccion,
        @Nullable String obligadoCodigo,
        @Nullable String obligadoNombre,
        @Nullable String infractorNombre,
        String estado,
        boolean pendiente,
        Dinero importeAPagar,
        LocalDate actualizadoA,
        @Nullable String valorNumero) {

    public static PapeletaDelPadronResource de(PapeletaDelPadron papeleta) {
        return new PapeletaDelPadronResource(
                papeleta.numero(),
                papeleta.familia().name(),
                papeleta.fechaInfraccion(),
                papeleta.horaInfraccion() == null ? null : papeleta.horaInfraccion().toString(),
                papeleta.lugar(),
                papeleta.placa(),
                papeleta.licenciaConducir(),
                papeleta.codigoInfraccion(),
                papeleta.descripcionInfraccion(),
                papeleta.obligadoCodigo(),
                papeleta.obligadoNombre(),
                papeleta.infractorNombre(),
                papeleta.estado().name(),
                papeleta.estaPendiente(),
                papeleta.importeAPagar(),
                papeleta.fechaInfraccion(),
                papeleta.valorNumero());
    }
}
