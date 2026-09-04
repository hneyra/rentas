package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import org.jspecify.annotations.Nullable;

/**
 * Un resumen de papeletas por HTTP (#53, RF-073).
 *
 * <p><b>Ningún campo se llama «recaudado».</b> Todos los importes son los de las actas agrupados
 * por el estado de cada papeleta; {@code importeDeLasPagadas} es «cuánto sumaban las actas de las
 * pagadas» y no «cuánto se cobró». Lo cobrado sale del libro y tiene su propia operación, {@code
 * transito_resumen_recaudacion}. Los nombres están escritos para que la confusión no se pueda
 * cometer sin darse cuenta.
 *
 * <p>El total va calculado en el servidor —{@code ResumenDePapeletas#importeTotal}— y no sumando en
 * la interfaz: recomponer una cifra en el cliente es cómo se acaba mostrando un total que no
 * coincide con el papel exportado.
 */
public record ResumenDePapeletasResource(
        String agrupadoPor,
        LocalDate desde,
        LocalDate hasta,
        long papeletas,
        Dinero importeTotal,
        LocalDate actualizadoA,
        List<Linea> lineas) {

    public static ResumenDePapeletasResource de(ResumenDePapeletas resumen) {
        return new ResumenDePapeletasResource(
                resumen.agrupacion().name(),
                resumen.desde(),
                resumen.hasta(),
                resumen.total(),
                resumen.importeTotal(),
                resumen.aLaFecha(),
                resumen.lineas().stream()
                        .map(linea -> Linea.de(linea, resumen.aLaFecha()))
                        .toList());
    }

    /**
     * Una línea del resumen, con la misma fecha que el resumen entero.
     *
     * <p>{@code ano} sale <b>solo</b> cuando el agrupador lo determina —{@code ANO} y {@code MES}—
     * y va nulo con los otros tres (#398). La columna «Año» de {@code transito_resumen_papeletas}
     * se dibuja con este campo y no con {@code clave}: la clave es el estado cuando se agrupa por
     * estado, y ponerla bajo un rótulo que dice «Año» es lo que RNF-080 no permite.
     */
    public record Linea(
            String clave,
            @Nullable String descripcion,
            @Nullable Integer ano,
            long cantidad,
            Dinero importe,
            long pagadas,
            Dinero importeDeLasPagadas,
            long pendientes,
            Dinero importeDeLasPendientes,
            long enCoactiva,
            Dinero importeEnCoactiva,
            LocalDate actualizadoA) {

        static Linea de(LineaDelResumen linea, LocalDate aLaFecha) {
            return new Linea(
                    linea.clave(),
                    linea.descripcion(),
                    linea.ano(),
                    linea.cantidad(),
                    linea.importe(),
                    linea.pagadas(),
                    linea.importeDeLasPagadas(),
                    linea.pendientes(),
                    linea.importeDeLasPendientes(),
                    linea.enCoactiva(),
                    linea.importeEnCoactiva(),
                    aLaFecha);
        }
    }
}
