package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * La comparacion de huellas entre este sistema y el padron de {@code catastro} (P6, punto 4).
 *
 * <p>Es una <b>funcion pura</b> (regla 6): recibe las dos listas de huellas y devuelve que sectores
 * no cuadran. Sin base, sin reloj y sin cliente HTTP — la fecha entra como argumento, y solo para
 * fechar el informe. Lo que la hace probable sin levantar nada es justamente eso: el defecto que
 * esta comparacion existe para encontrar es de datos, y una funcion pura se puede alimentar con los
 * datos exactos que lo reproducen.
 *
 * <h2>Las tres cosas que puede decir de un sector, y por que son tres y no dos</h2>
 *
 * <ul>
 *   <li><b>Cuadra</b>: las dos huellas coinciden. No se pide detalle.
 *   <li><b>Discrepa</b>: los dos lados tienen el sector y sus huellas difieren. Alguna fila dice
 *       otra cosa; hay que pedir el detalle.
 *   <li><b>Falta de un lado</b>: uno lo tiene y el otro no. Se separa a proposito de «discrepa»
 *       porque se arregla distinto —ahi no hay ninguna fila que comparar, falta el sector entero— y
 *       porque es el sintoma tipico de un evento que nunca llego.
 * </ul>
 *
 * <p>Y el recuento de lotes viaja al lado de la huella: cuando dos huellas difieren, saber si
 * ademas difieren los recuentos separa «una fila cambio» de «faltan filas». Las dos se leen igual
 * en la huella y no se arreglan igual.
 */
public final class AntiEntropia {

    private AntiEntropia() {}

    /** Lo que un lado dice de un sector. */
    public record HuellaDeSector(@Nullable String sector, int lotes, String huella) {}

    /** Por que un sector no cuadra. */
    public enum Discrepancia {
        /** Los dos lo tienen y las huellas difieren. */
        HUELLA_DISTINTA,
        /** Catastro lo tiene y la proyeccion no: un evento que no llego. */
        FALTA_EN_LA_PROYECCION,
        /** La proyeccion lo tiene y catastro no: filas que sobreviven a su origen. */
        SOBRA_EN_LA_PROYECCION
    }

    /** Un sector que no cuadra, con lo que cada lado dice de el. */
    public record SectorQueNoCuadra(
            @Nullable String sector,
            Discrepancia porQue,
            int lotesEnCatastro,
            int lotesEnLaProyeccion) {

        /** El renglon del informe. Nombra el sector, que es lo que el criterio de P6 pide. */
        public String comoLinea() {
            return switch (porQue) {
                case HUELLA_DISTINTA ->
                        "sector "
                                + nombre()
                                + ": las huellas no cuadran ("
                                + lotesEnCatastro
                                + " lotes en catastro, "
                                + lotesEnLaProyeccion
                                + " en la proyeccion)";
                case FALTA_EN_LA_PROYECCION ->
                        "sector "
                                + nombre()
                                + ": catastro tiene "
                                + lotesEnCatastro
                                + " lotes y la proyeccion no tiene el sector";
                case SOBRA_EN_LA_PROYECCION ->
                        "sector "
                                + nombre()
                                + ": la proyeccion tiene "
                                + lotesEnLaProyeccion
                                + " lotes y catastro no tiene el sector";
            };
        }

        private String nombre() {
            return sector == null ? "«sin sectorizar»" : "«" + sector + "»";
        }
    }

    /** El resultado de una corrida. */
    public record Informe(
            LocalDate aLaFecha, int sectoresComparados, List<SectorQueNoCuadra> noCuadran) {

        /** Si todo cuadro. */
        public boolean cuadra() {
            return noCuadran.isEmpty();
        }

        /**
         * El informe entero en texto, para el registro del proceso.
         *
         * <p>Dice <b>cuantos se compararon</b> aunque no haya ninguna discrepancia, y eso no sobra:
         * «0 discrepancias de 0 sectores comparados» y «0 de 47» se leen igual en un booleano y son
         * dos cosas distintas — la primera es que nadie comparo nada.
         */
        public String comoTexto() {
            StringBuilder texto =
                    new StringBuilder(
                            "Anti-entropia catastro/rentas al "
                                    + aLaFecha
                                    + ": "
                                    + noCuadran.size()
                                    + " sectores no cuadran de "
                                    + sectoresComparados
                                    + " comparados");
            for (SectorQueNoCuadra sector : noCuadran) {
                texto.append("\n  - ").append(sector.comoLinea());
            }
            return texto.toString();
        }
    }

    /**
     * Compara las dos listas y dice que sectores no cuadran.
     *
     * @param enCatastro lo que el padron dice de si mismo
     * @param enLaProyeccion lo que este sistema tiene proyectado
     * @param aLaFecha la fecha del informe. Entra como argumento (regla 9): un informe sin fecha no
     *     se puede volver a leer dentro de un mes
     */
    public static Informe comparar(
            List<HuellaDeSector> enCatastro,
            List<HuellaDeSector> enLaProyeccion,
            LocalDate aLaFecha) {

        Map<String, HuellaDeSector> deCatastro = porClave(enCatastro);
        Map<String, HuellaDeSector> deLaProyeccion = porClave(enLaProyeccion);

        // Un `TreeSet` sobre la union: el informe sale en orden estable, y dos corridas del
        // mismo estado producen el mismo texto. Un informe cuyo orden cambia solo es un
        // informe que nadie puede comparar con el de ayer.
        TreeSet<String> todas = new TreeSet<>(deCatastro.keySet());
        todas.addAll(deLaProyeccion.keySet());

        List<SectorQueNoCuadra> noCuadran = new ArrayList<>();
        for (String clave : todas) {
            HuellaDeSector aqui = deLaProyeccion.get(clave);
            HuellaDeSector alla = deCatastro.get(clave);

            if (alla == null && aqui != null) {
                noCuadran.add(
                        new SectorQueNoCuadra(
                                aqui.sector(),
                                Discrepancia.SOBRA_EN_LA_PROYECCION,
                                0,
                                aqui.lotes()));
            } else if (aqui == null && alla != null) {
                noCuadran.add(
                        new SectorQueNoCuadra(
                                alla.sector(),
                                Discrepancia.FALTA_EN_LA_PROYECCION,
                                alla.lotes(),
                                0));
            } else if (aqui != null
                    && alla != null
                    && !Objects.equals(aqui.huella(), alla.huella())) {
                noCuadran.add(
                        new SectorQueNoCuadra(
                                alla.sector(),
                                Discrepancia.HUELLA_DISTINTA,
                                alla.lotes(),
                                aqui.lotes()));
            }
        }
        return new Informe(aLaFecha, todas.size(), List.copyOf(noCuadran));
    }

    /**
     * La clave del mapa, con el nulo representado.
     *
     * <p>Un {@code HashMap} admite la clave nula, pero el sector sin sectorizar tiene que poder
     * compararse igual que los demas y salir ordenado en el informe: se le da una clave que ningun
     * codigo de sector puede tener —empieza por un espacio, y {@code sector.codigo} es {@code
     * varchar(10)} sin espacios— en vez de dejarla fuera.
     */
    private static Map<String, HuellaDeSector> porClave(List<HuellaDeSector> huellas) {
        Map<String, HuellaDeSector> mapa = new LinkedHashMap<>();
        for (HuellaDeSector huella : huellas) {
            mapa.put(huella.sector() == null ? " SIN SECTORIZAR" : huella.sector(), huella);
        }
        return mapa;
    }
}
