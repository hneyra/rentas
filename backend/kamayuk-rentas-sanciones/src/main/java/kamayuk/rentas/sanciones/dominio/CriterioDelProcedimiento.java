package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide la pantalla «Infracción administrativa» ({@code infracciones_adm}, #397): sus cuatro
 * filtros y la fecha a la que se resuelve la fase.
 *
 * <p>No se añaden a {@link CriterioDePapeleta} porque aquel lo comparten las seis consultas de
 * papeletas de las <b>dos</b> familias, y {@link #fase} solo existe en el procedimiento sancionador
 * municipal: meterla ahí obligaría a las de tránsito a llevar un campo que no significa nada para
 * ellas, y a alguien a decidir algún día qué hace una papeleta de tránsito «CONSTATADA».
 *
 * @param nroDeActa el número del acta, exacto
 * @param administrado el documento del administrado —DNI o RUC—, tal como lo escribe el operador
 * @param codigoCuis el código del catálogo
 * @param fase la fase del procedimiento; sin ella no se filtra por fase, que es lo que significa el
 *     «Todos» del desplegable
 * @param aLaFecha la fecha a la que se resuelve la fase. <b>Nunca opcional</b>: una fase sin fecha
 *     es una fase que mañana es otra sin que nadie lo sepa (regla 9, RNF-075)
 */
public record CriterioDelProcedimiento(
        @Nullable String nroDeActa,
        @Nullable String administrado,
        @Nullable String codigoCuis,
        @Nullable FaseDelProcedimiento fase,
        LocalDate aLaFecha) {

    public CriterioDelProcedimiento {
        nroDeActa = limpiar(nroDeActa);
        administrado = limpiar(administrado);
        codigoCuis = limpiar(codigoCuis);
        Objects.requireNonNull(
                aLaFecha, "La fase del procedimiento se resuelve a una fecha, y hace falta cual");
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
