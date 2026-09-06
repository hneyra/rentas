package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que cruza el lote: zonas de riesgo y fajas marginales, a la fecha con que se resolvio.
 *
 * <p><b>{@code hayRiesgoNoMitigable} viene derivado y arriba</b>, tal como {@code catastro} lo
 * publica. No se recalcula recorriendo las zonas: repetir aqui la unica linea que decide es
 * exactamente la repeticion que un dia se escribe al reves, y entonces los dos sistemas contestan
 * distinto sobre el mismo lote.
 *
 * <p><b>{@code aLaFecha} es la que uso {@code catastro}</b>, no una que se le pidiera: esta
 * operacion no admite fecha. Viaja igual porque las cartas de peligro caducan (regla 9).
 */
public record RiesgoDelPredio(
        long predioId,
        LocalDate aLaFecha,
        boolean hayRiesgoNoMitigable,
        List<ZonaDeRiesgo> zonas,
        List<FajaMarginal> fajasMarginales) {}
