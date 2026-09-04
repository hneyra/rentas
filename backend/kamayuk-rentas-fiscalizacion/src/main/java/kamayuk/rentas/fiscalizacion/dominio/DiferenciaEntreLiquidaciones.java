package kamayuk.rentas.fiscalizacion.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Qué cambió entre una liquidación y la que la reliquida (#49, AC 2: «una reliquidación deja las
 * dos versiones y <b>explica la diferencia</b>»).
 *
 * <p><b>Función pura</b> (regla 6): entran las dos versiones con sus líneas y sale la lista de
 * cambios. Sin base de datos y sin reloj, para que la explicación de una reliquidación de 2026 se
 * pueda reconstruir idéntica en 2036.
 *
 * <h2>Dejar las dos versiones no basta</h2>
 *
 * <p>Guardar la anterior y la nueva satisface la mitad del criterio; la otra mitad es que alguien
 * pueda decir <b>qué</b> cambió sin poner las dos pantallas una al lado de la otra y compararlas a
 * ojo. Eso es lo que produce esta clase, y es lo que la pantalla {@code fisc_historico} pinta en su
 * pestaña «Versiones».
 *
 * <h2>Las cifras que faltan se nombran, no se omiten</h2>
 *
 * <p>Mientras D-02a siga abierta las dos versiones tienen sus importes en {@code null}, así que la
 * comparación de importes no encuentra ningún cambio. Decir «no cambió nada» sería falso: lo que
 * pasa es que no hay nada que comparar. Por eso {@link #importesSinCifra()} devuelve las líneas
 * cuya comparación monetaria está pendiente, y la interfaz puede decirlo en vez de dibujar un cero.
 */
public record DiferenciaEntreLiquidaciones(
        Liquidacion anterior,
        Liquidacion nueva,
        List<CambioEntreVersiones> cambios,
        List<String> importesSinCifra) {

    /** Los conceptos de la cabecera que se comparan, con el nombre que la pantalla les da. */
    private static final String PERIODO = "Periodo fiscalizado";

    private static final String TIPO = "Tipo de fiscalizacion";

    private static final String MOTIVO = "Motivo determinante";

    public DiferenciaEntreLiquidaciones {
        Objects.requireNonNull(anterior, "La diferencia es entre dos liquidaciones");
        Objects.requireNonNull(nueva, "La diferencia es entre dos liquidaciones");
        cambios = List.copyOf(cambios);
        importesSinCifra = List.copyOf(importesSinCifra);
    }

    /**
     * Compara las dos versiones.
     *
     * @param anterior la liquidación sustituida
     * @param lineasAnteriores su detalle
     * @param nueva la reliquidación
     * @param lineasNuevas su detalle
     * @throws IllegalArgumentException si la segunda no reliquida a la primera: comparar dos
     *     liquidaciones que no se encadenan produce un informe que parece una explicación y no lo
     *     es
     */
    public static DiferenciaEntreLiquidaciones entre(
            Liquidacion anterior,
            List<LineaDeLiquidacion> lineasAnteriores,
            Liquidacion nueva,
            List<LineaDeLiquidacion> lineasNuevas) {

        Objects.requireNonNull(anterior, "La diferencia es entre dos liquidaciones");
        Objects.requireNonNull(nueva, "La diferencia es entre dos liquidaciones");
        if (!Objects.equals(nueva.liquidacionAnteriorId(), anterior.id())) {
            throw new IllegalArgumentException(
                    "La liquidacion "
                            + nueva.numero()
                            + " no reliquida a "
                            + anterior.numero()
                            + ": comparar dos que no se encadenan produce un informe que parece"
                            + " una explicacion y no lo es");
        }

        List<CambioEntreVersiones> cambios = new ArrayList<>();
        agregarSiCambia(
                cambios,
                PERIODO,
                anterior.ejercicioDesde() + "-" + anterior.ejercicioHasta(),
                nueva.ejercicioDesde() + "-" + nueva.ejercicioHasta());
        agregarSiCambia(cambios, TIPO, anterior.tipo().name(), nueva.tipo().name());
        agregarSiCambia(cambios, MOTIVO, anterior.motivoDeterminante(), nueva.motivoDeterminante());

        cambios.addAll(cambiosDelDetalle(lineasAnteriores, lineasNuevas));

        List<String> pendientes = new ArrayList<>();
        for (LineaDeLiquidacion linea : lineasNuevas) {
            if (linea.esperaSusCifras()) {
                pendientes.add(claveDe(linea));
            }
        }

        return new DiferenciaEntreLiquidaciones(anterior, nueva, cambios, pendientes);
    }

    /** Si las dos versiones dicen exactamente lo mismo. Una reliquidación así no explica nada. */
    public boolean sinCambios() {
        return cambios.isEmpty();
    }

    // ------------------------------------------------------------------

    private static List<CambioEntreVersiones> cambiosDelDetalle(
            List<LineaDeLiquidacion> anteriores, List<LineaDeLiquidacion> nuevas) {

        Map<String, LineaDeLiquidacion> antes = porClave(anteriores);
        Map<String, LineaDeLiquidacion> despues = porClave(nuevas);
        Set<String> claves = new LinkedHashSet<>(antes.keySet());
        claves.addAll(despues.keySet());

        List<CambioEntreVersiones> cambios = new ArrayList<>();
        for (String clave : claves) {
            LineaDeLiquidacion vieja = antes.get(clave);
            LineaDeLiquidacion nueva = despues.get(clave);
            if (nueva == null) {
                cambios.add(
                        new CambioEntreVersiones(
                                clave, descripcion(Objects.requireNonNull(vieja)), null));
                continue;
            }
            if (vieja == null) {
                cambios.add(new CambioEntreVersiones(clave, null, descripcion(nueva)));
                continue;
            }
            agregarSiCambia(
                    cambios,
                    clave + " · condicion",
                    vieja.condicion().name(),
                    nueva.condicion().name());
            agregarSiCambia(
                    cambios,
                    clave + " · area declarada",
                    texto(vieja.areaDeclarada()),
                    texto(nueva.areaDeclarada()));
            agregarSiCambia(
                    cambios,
                    clave + " · area hallada",
                    texto(vieja.areaHallada()),
                    texto(nueva.areaHallada()));
            agregarSiCambia(
                    cambios, clave + " · uso hallado", vieja.usoHallado(), nueva.usoHallado());
            agregarSiCambia(
                    cambios,
                    clave + " · insoluto omitido",
                    texto(vieja.insolutoOmitido()),
                    texto(nueva.insolutoOmitido()));
            agregarSiCambia(
                    cambios,
                    clave + " · multa tributaria",
                    texto(vieja.multaTributaria()),
                    texto(nueva.multaTributaria()));
        }
        return cambios;
    }

    private static Map<String, LineaDeLiquidacion> porClave(List<LineaDeLiquidacion> lineas) {
        Map<String, LineaDeLiquidacion> mapa = new LinkedHashMap<>();
        for (LineaDeLiquidacion linea : lineas) {
            mapa.put(claveDe(linea), linea);
        }
        return mapa;
    }

    private static String claveDe(LineaDeLiquidacion linea) {
        String unidad =
                linea.predioId() != null
                        ? "predio " + linea.predioId()
                        : "vehiculo " + linea.vehiculoId();
        return unidad + " · " + linea.ejercicio();
    }

    private static String descripcion(LineaDeLiquidacion linea) {
        return linea.condicion().name();
    }

    private static void agregarSiCambia(
            List<CambioEntreVersiones> cambios,
            String concepto,
            @Nullable String antes,
            @Nullable String despues) {
        if (!Objects.equals(antes, despues)) {
            cambios.add(new CambioEntreVersiones(concepto, antes, despues));
        }
    }

    private static @Nullable String texto(@Nullable Object valor) {
        return valor == null ? null : valor.toString();
    }
}
