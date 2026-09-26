package kamayuk.rentas.licencias.dominio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;

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
 * <p>{@code area × valor unitario} por linea, redondeado en {@link #PUNTO_DE_REDONDEO}, y la suma
 * de esas lineas. <b>Nada mas.</b> Y la lista de lo que <b>no</b> hace importa mas que lo que hace:
 *
 * <ul>
 *   <li><b>No aplica el incremento del 5 %.</b> Ese factor es de la secuencia del autovaluo predial
 *       —{@code valor unitario → +5 % → −depreciacion → ×area}, NEG-05 §RT-002— y esta marcado
 *       <b>sin fuente identificada</b> en D-11. Aplicarlo aqui seria inventar un multiplicador.
 *   <li><b>No deprecia.</b> La obra del FUE no esta construida todavia: no tiene antiguedad ni
 *       estado de conservacion que depreciar. Una licencia de regularizacion podria tenerlos, y esa
 *       es una decision que ninguna norma leida en este repositorio resuelve.
 *   <li><b>No redondea con una politica propia</b> (#378). Hasta #378 no redondeaba en absoluto:
 *       este renglon decia que D-03 seguia abierta en sus tres partes, y ADR-0018 de {@code
 *       normativa} ya la habia cerrado —escala 2, {@code HALF_UP}, al cierre de cada regla—. El
 *       papel de la licencia, que queda en {@code documento_emitido}, imprimia «Valor de obra (S/)
 *       103320.31500000» con 120,50 m² a 857,43. Ahora cada linea se redondea con la politica que
 *       el conjunto sellado publica para {@link #PUNTO_DE_REDONDEO}, que llega como argumento; y el
 *       total es la suma de esas lineas, o sea lo mismo que suman los renglones que el papel
 *       imprime.
 *   <li><b>No lleva ninguna cifra dentro.</b> Todas salen de {@link TablaDeValoresUnitarios}, que
 *       las trae del conjunto sellado (regla 5). Las celdas concretas las espera #197.
 * </ul>
 *
 * <p>Si la ordenanza de la municipalidad piloto exigiera algun factor mas —un coeficiente de
 * oficializacion, un porcentaje de actualizacion—, ese factor entra como <b>parametro sellado</b> y
 * su valor lo decide #197; no se anade aqui.
 */
public final class ValorizacionDeObra {

    /**
     * Donde cierra esta regla: el importe de cada linea. <b>No es {@code VALOR_DE_OBRA}</b>, que es
     * la obra complementaria del autovaluo predial (RT-005).
     */
    public static final PuntoDeRedondeo PUNTO_DE_REDONDEO = PuntoDeRedondeo.VALOR_DE_OBRA_DEL_FUE;

    private ValorizacionDeObra() {}

    /**
     * La valorizacion de esas lineas contra esa tabla.
     *
     * @param estructuras las lineas declaradas en la seccion de valorizacion
     * @param tabla el cuadro del conjunto sellado, ya filtrado por anio de construccion
     * @param redondeo la politica del conjunto sellado para {@link #PUNTO_DE_REDONDEO} (#378)
     * @throws TablaDeValoresUnitarios.ValorUnitarioSinParametrizar si el cuadro no tiene alguna de
     *     las celdas que las lineas necesitan; el mensaje dice cual
     * @throws SinEstructuras si no hay ninguna linea que valorizar
     */
    public static Valorizacion valorizar(
            List<EstructuraDelProyecto> estructuras,
            TablaDeValoresUnitarios tabla,
            PoliticaDeRedondeo redondeo) {

        Objects.requireNonNull(estructuras, "La lista de estructuras es vacia, no nula");
        Objects.requireNonNull(tabla, "Sin cuadro de valores unitarios no se valoriza nada");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (#378)");

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
                            .por(estructura.area().valor())
                            .redondeadoCon(redondeo);
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
     * @param importe {@code area × valor unitario}, redondeado en {@code VALOR_DE_OBRA_DEL_FUE}
     *     (#378)
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
     * @param total la suma de las lineas ya redondeadas: la cifra que el papel imprime es la suma
     *     de los renglones que imprime (#378)
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
