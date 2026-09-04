package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeLaHojaDePapeleta;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import org.jspecify.annotations.Nullable;

/**
 * La hoja informativa de una papeleta de tránsito, por HTTP (#396, RF-068).
 *
 * <h2>Dos fechas, y las dos hacen falta</h2>
 *
 * <p>{@code actualizadoA} es la <b>fecha de la infracción</b>: los seis importes son los del acta,
 * congelados al registrarla, y no se recalculan nunca (regla 9, RNF-075). {@code emitidaEl} es el
 * día en que sale la hoja, y con él se resolvió el domicilio del obligado. Poner «hoy» en {@code
 * actualizadoA} haría que la cifra y su fecha dijeran cosas distintas, que es peor que no tener
 * fecha: parecería actualizada. Es la misma decisión que {@code PapeletaDelPadronResource}.
 *
 * <h2>Lo que esta hoja NO dice</h2>
 *
 * <p><b>No dice lo que se debe hoy.</b> Esa cifra es del libro, cambia cada día con el interés
 * moratorio y obligaría a fechar la hoja de otra manera; el estado de cuenta la publica ({@code GET
 * /transito/estado-cuenta}). Una hoja informativa que mezclara el importe del acta con el saldo
 * vivo dejaría al que la lee sin saber cuál de los dos le están cobrando.
 *
 * @param numero el número impreso en el acta
 * @param fechaInfraccion cuándo ocurrió
 * @param horaInfraccion a qué hora, si el acta la trae
 * @param lugar dónde
 * @param placa del vehículo infractor
 * @param licenciaConducir del infractor, si el acta la trae
 * @param codigoInfraccion el código del catálogo; nulo si esa versión ya no está
 * @param descripcionInfraccion su texto; nulo por el mismo motivo
 * @param obligadoCodigo el código del contribuyente contra el que se asentó el cargo
 * @param obligadoNombre su nombre
 * @param obligadoDocumento su documento, tipo y número
 * @param obligadoDomicilio su domicilio fiscal vigente el día de la emisión
 * @param estado en qué punto está la papeleta
 * @param baseImponible la UIT del ejercicio de la infracción, tal como se aplicó en el acta
 * @param porcentajeInfraccion el porcentaje que fija el código, como texto
 * @param importeInfraccion base por porcentaje, ya calculado en el acta
 * @param porcentajeACobrar el porcentaje que realmente se cobra, como texto
 * @param importeAPagar lo que corresponde pagar, sin beneficio
 * @param importeConBeneficio con el descuento vigente, si el acta trae uno
 * @param actualizadoA la fecha de la infracción: la de los seis importes
 * @param emitidaEl el día en que se emite la hoja
 */
public record HojaInformativaResource(
        String numero,
        LocalDate fechaInfraccion,
        @Nullable String horaInfraccion,
        String lugar,
        @Nullable String placa,
        @Nullable String licenciaConducir,
        @Nullable String codigoInfraccion,
        @Nullable String descripcionInfraccion,
        @Nullable String obligadoCodigo,
        @Nullable String obligadoNombre,
        @Nullable String obligadoDocumento,
        @Nullable String obligadoDomicilio,
        String estado,
        Dinero baseImponible,
        String porcentajeInfraccion,
        Dinero importeInfraccion,
        String porcentajeACobrar,
        Dinero importeAPagar,
        @Nullable Dinero importeConBeneficio,
        LocalDate actualizadoA,
        LocalDate emitidaEl) {

    public static HojaInformativaResource de(ConsultaDeLaHojaDePapeleta.Hoja hoja) {
        Papeleta papeleta = hoja.papeleta();
        return new HojaInformativaResource(
                papeleta.numero(),
                papeleta.fechaInfraccion(),
                papeleta.horaInfraccion() == null ? null : papeleta.horaInfraccion().toString(),
                papeleta.lugar(),
                papeleta.placa(),
                papeleta.licenciaConducir(),
                hoja.codigo() == null ? null : hoja.codigo().codigo(),
                hoja.descripcionDeLaInfraccion().orElse(null),
                hoja.obligado() == null ? null : hoja.obligado().codigo(),
                hoja.obligado() == null ? null : hoja.obligado().nombre(),
                hoja.obligado() == null ? null : hoja.obligado().documento(),
                hoja.domicilioDelObligado(),
                papeleta.estado().name(),
                papeleta.baseImponible(),
                papeleta.porcentajeInfraccion().valor().toPlainString(),
                papeleta.importeInfraccion(),
                papeleta.porcentajeACobrar().valor().toPlainString(),
                papeleta.importeAPagar(),
                papeleta.importeConBeneficio(),
                papeleta.fechaInfraccion(),
                hoja.emitidaEl());
    }
}
