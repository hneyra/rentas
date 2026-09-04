package kamayuk.rentas.licencias.dominio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;

/**
 * Valoriza la obra del FUE piso a piso y estructura a estructura (#48 AC 2, RF-113).
 *
 * <h2>Es una funcion pura (regla 6, regla 7)</h2>
 *
 * <p>Sin base de datos, sin reloj y sin configuracion global: recibe las lineas declaradas y la
 * tabla del conjunto sellado, y devuelve la valorizacion. Valorizar el mismo proyecto con la misma
 * tabla en 2037 da el mismo centimo.
 *
 * <h2>Lo que hace, exactamente</h2>
 *
 * <p>{@code area × valor unitario} por linea, y la suma. <b>Nada mas.</b> Y la lista de lo que
 * <b>no</b> hace importa mas que lo que hace:
 *
 * <ul>
 *   <li><b>No aplica el incremento del 5 %.</b> Ese factor es de la secuencia del autovaluo predial
 *       —{@code valor unitario → +5 % → −depreciacion → ×area}, NEG-05 §RT-002— y esta marcado
 *       <b>sin fuente identificada</b> en D-11. Aplicarlo aqui seria inventar un multiplicador.
 *   <li><b>No deprecia.</b> La obra del FUE no esta construida todavia: no tiene antiguedad ni
 *       estado de conservacion que depreciar. Una licencia de regularizacion podria tenerlos, y esa
 *       es una decision que ninguna norma leida en este repositorio resuelve.
 *   <li><b>No redondea.</b> D-03 sigue abierta en sus tres partes —escala, modo y puntos—, asi que
 *       {@link Dinero#por} devuelve el producto entero y quien presente la cifra decide, con la
 *       politica que reciba, donde recortarla.
 *   <li><b>No lleva ninguna cifra dentro.</b> Todas salen de {@link TablaDeValoresUnitarios}, que
 *       las trae del conjunto sellado (regla 5). Las celdas concretas las espera #197.
 * </ul>
 *
 * <p>Si la ordenanza de la municipalidad piloto exigiera algun factor mas —un coeficiente de
 * oficializacion, un porcentaje de actualizacion—, ese factor entra como <b>parametro sellado</b> y
 * su valor lo decide #197; no se anade aqui.
 */
public final class ValorizacionDeObra {

    private ValorizacionDeObra() {}

    /**
     * La valorizacion de esas lineas contra esa tabla.
     *
     * @param estructuras las lineas declaradas en la seccion de valorizacion
     * @param tabla el cuadro del conjunto sellado, ya filtrado por anio de construccion
     * @throws TablaDeValoresUnitarios.ValorUnitarioSinParametrizar si el cuadro no tiene alguna de
     *     las celdas que las lineas necesitan; el mensaje dice cual
     * @throws SinEstructuras si no hay ninguna linea que valorizar
     */
    public static Valorizacion valorizar(
            List<EstructuraDelProyecto> estructuras, TablaDeValoresUnitarios tabla) {

        Objects.requireNonNull(estructuras, "La lista de estructuras es vacia, no nula");
        Objects.requireNonNull(tabla, "Sin cuadro de valores unitarios no se valoriza nada");

        if (estructuras.isEmpty()) {
            throw new SinEstructuras();
        }

        List<LineaValorizada> lineas = new ArrayList<>(estructuras.size());
        Dinero total = Dinero.CERO;
        for (EstructuraDelProyecto estructura : estructuras) {
            Dinero importe =
                    new Dinero(
                                    tabla.valorPorM2(estructura.partida(), estructura.categoria())
                                            .valor())
                            .por(estructura.area().valor());
            lineas.add(
                    new LineaValorizada(
                            estructura.piso(),
                            estructura.partida(),
                            estructura.categoria(),
                            estructura.area(),
                            importe));
            total = total.mas(importe);
        }
        return new Valorizacion(List.copyOf(lineas), total, tabla.anioDeConstruccion());
    }

    // ------------------------------------------------------------------

    /**
     * Una linea valorizada.
     *
     * @param piso el piso
     * @param partida cual de las siete partidas
     * @param categoria la letra
     * @param area cuantos metros cuadrados
     * @param importe {@code area × valor unitario}, sin redondear (D-03)
     */
    public record LineaValorizada(
            int piso, PartidaDeEdificacion partida, char categoria, AreaM2 area, Dinero importe) {

        public LineaValorizada {
            Objects.requireNonNull(partida, "La linea dice de que partida es");
            Objects.requireNonNull(area, "La linea dice cuantos metros mide");
            Objects.requireNonNull(importe, "La linea dice cuanto suma");
        }
    }

    /**
     * La obra valorizada.
     *
     * @param lineas una por partida y piso declarados
     * @param total la suma, <b>sin redondear</b>: quien la presente aplica la politica que reciba
     * @param anioDeConstruccion el anio con que se eligio la fila del cuadro
     */
    public record Valorizacion(List<LineaValorizada> lineas, Dinero total, int anioDeConstruccion) {

        public Valorizacion {
            lineas = List.copyOf(lineas);
            Objects.requireNonNull(total, "La valorizacion dice cuanto suma");
        }
    }

    /** No hay ninguna linea que valorizar: la seccion de valorizacion esta sin completar. */
    public static final class SinEstructuras extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinEstructuras() {
            super(
                    "El proyecto no declara ninguna partida en ningun piso, asi que no hay nada que"
                            + " valorizar. Devolver cero seria decir que la obra no vale nada, que"
                            + " es distinto de no haberla descrito todavia");
        }
    }
}
