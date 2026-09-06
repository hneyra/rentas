package kamayuk.rentas.catastro;

import java.time.LocalDate;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Un hallazgo de la fiscalizacion catastral: dos superficies y su resta.
 *
 * <p><b>Lleva {@code fichaId} y {@code verificadoEn}, y las dos por el mismo motivo</b>: el exceso
 * solo significa algo si se sabe contra que version se comparo y cuando. Es la regla 9 aplicada a
 * una superficie — la ficha se versiona, asi que una diferencia sin su version es una diferencia
 * que manana es otra.
 *
 * <p><b>Ni un importe.</b> Ninguna de las cinco tablas de la fiscalizacion catastral tiene columna
 * de dinero y ninguno de estos campos la tiene. Lo que se cobre lo decide este sistema, con su
 * ordenanza y su alicuota, y no sale de aqui.
 *
 * @param predioId de que predio es; {@code null} cuando el hallazgo es un omiso catastral, que por
 *     definicion no esta en el padron
 * @param areaDeLaFicha lo inscrito; {@code null} cuando no hay ficha con que comparar
 * @param excesoVerificado lo verificado menos lo inscrito; <b>{@code null}</b> cuando no hay con
 *     que comparar o cuando lo verificado no supera lo inscrito. Nulo y no cero: cero significaria
 *     que coinciden
 */
public record HallazgoCatastral(
        long id,
        long candidatoId,
        String clase,
        @Nullable Long predioId,
        @Nullable Long fichaId,
        @Nullable AreaM2 areaDeLaFicha,
        AreaM2 areaVerificada,
        @Nullable AreaM2 excesoVerificado,
        String inspector,
        LocalDate verificadoEn,
        String estado) {}
