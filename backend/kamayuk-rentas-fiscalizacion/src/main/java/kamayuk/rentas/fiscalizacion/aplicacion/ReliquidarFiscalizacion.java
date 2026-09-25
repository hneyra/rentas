package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.CorreccionDeLinea;
import kamayuk.rentas.fiscalizacion.dominio.DiferenciaEntreLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.PlantillaDeNumeroDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrige una liquidación emitiendo <b>otra</b>, que la referencia y explica la diferencia (#49, AC
 * 2).
 *
 * <h2>No pisa</h2>
 *
 * <p>La liquidación anterior no cambia ni una columna. No hay ningún {@code UPDATE} en este camino:
 * {@code liquidacion_fiscalizacion} no lo admite desde V39, y el escáner del código fuente lo
 * vigila además en {@code TABLAS_INMUTABLES}. Las dos versiones quedan, y las dos se pueden leer:
 * la anterior explica por qué se notificó lo que se notificó, y la nueva por qué ya no vale.
 *
 * <p>Ni siquiera se marca la anterior como sustituida. La sustitución se <b>lee</b> de que exista
 * otra versión que la referencia, y esa lectura no puede desincronizarse con una columna que
 * alguien tendría que actualizar. Es el precedente de {@code ficha_catastral} (#18) llevado hasta
 * el final.
 *
 * <h2>Hereda el conjunto sellado de cada línea</h2>
 *
 * <p>Las líneas nuevas reutilizan el {@code conjuntoId} que la versión anterior fijó para ese
 * ejercicio. Una reliquidación corrige el contraste —un área mal medida, un ejercicio de más—, no
 * el marco normativo: resolverlo otra vez mezclaría dos correcciones en una y haría imposible
 * explicarle al contribuyente por qué cambió su deuda. Un ejercicio que la versión anterior no
 * cubría sí resuelve el suyo, porque para él no hay nada que heredar.
 *
 * <h2>Y explica la diferencia</h2>
 *
 * <p>Dejar las dos versiones satisface la mitad del criterio; la otra mitad es poder decir
 * <b>qué</b> cambió sin comparar dos pantallas a ojo. Eso lo produce {@link
 * DiferenciaEntreLiquidaciones}, que es una función pura, y esta clase lo devuelve junto con la
 * liquidación nueva.
 */
@Service
public class ReliquidarFiscalizacion {

    /**
     * Solo para leer el acta (#339): la reliquidacion no la escribe, pero una version nueva sobre
     * una visita anulada es la misma determinacion sin sustento que liquidarla.
     */
    private final ActaFiscalizacionRepository actas;

    private final LiquidacionRepository liquidaciones;
    private final LiquidarFiscalizacion liquidar;

    public ReliquidarFiscalizacion(
            ActaFiscalizacionRepository actas,
            LiquidacionRepository liquidaciones,
            LiquidarFiscalizacion liquidar) {
        this.actas = actas;
        this.liquidaciones = liquidaciones;
        this.liquidar = liquidar;
    }

    /**
     * Emite la reliquidación.
     *
     * @param numeroAnterior el «Nº Liquidación» de la que se corrige
     * @param correcciones el contraste corregido, una entrada por ejercicio. Los ejercicios que no
     *     se nombran se copian tal cual de la versión anterior: reliquidar un ejercicio no borra
     *     los demás
     * @param observacion por qué se reliquida (regla 10)
     * @throws ActaFiscalizacion.ActaAnulada si la visita que la sustenta esta anulada (#339)
     */
    @Transactional
    public Resultado reliquidar(
            String numeroAnterior,
            Ejercicio desde,
            Ejercicio hasta,
            TipoDeFiscalizacion tipo,
            String motivoDeterminante,
            List<CorreccionDeLinea> correcciones,
            LocalDate fecha,
            Observacion observacion) {

        Liquidacion anterior =
                liquidaciones
                        .porNumero(numeroAnterior)
                        .orElseThrow(() -> new LiquidacionInexistente(numeroAnterior));

        // Hasta #339 este camino ni leia el acta: recomponia las lineas desde la version anterior,
        // y el consejo de `CambiarEstadoDeLaLiquidacion` —«corregir una anulada es reliquidar»—
        // hacia nacer una v2 ABIERTA sobre una visita ya anulada. Se lee con la fila bloqueada
        // por lo mismo que al liquidar: una anulacion simultanea leeria «ultima version ANULADA»
        // mientras esta v2 todavia no esta confirmada.
        ActaFiscalizacion acta =
                actas.findByIdParaActualizar(anterior.actaId())
                        .orElseThrow(
                                () -> new LiquidarFiscalizacion.ActaInexistente(anterior.actaId()));
        acta.exigirViva();

        List<Liquidacion> versiones = liquidaciones.versionesDeActa(anterior.actaId());
        Liquidacion ultima = versiones.get(versiones.size() - 1);
        if (ultima.version() != anterior.version()) {
            throw new NoEsLaUltimaVersion(numeroAnterior, ultima.numero());
        }

        List<LineaDeLiquidacion> lineasAnteriores =
                liquidaciones.lineasDe(anterior.identificador());
        List<LineaDeLiquidacion> lineasNuevas =
                recomponer(lineasAnteriores, correcciones, desde, hasta);

        Ejercicio deLaNumeracion = Ejercicio.de(fecha);
        long correlativo = liquidar.siguienteCorrelativoPara(deLaNumeracion);

        Liquidacion nueva =
                anterior.reliquidadaPor(
                        PlantillaDeNumeroDeLiquidacion.POR_OMISION.componer(
                                deLaNumeracion, correlativo),
                        deLaNumeracion,
                        correlativo,
                        desde,
                        hasta,
                        tipo,
                        motivoDeterminante,
                        fecha,
                        observacion);

        Liquidacion guardada = liquidar.guardar(nueva, lineasNuevas, observacion);

        return new Resultado(
                guardada,
                DiferenciaEntreLiquidaciones.entre(
                        anterior,
                        lineasAnteriores,
                        guardada,
                        liquidaciones.lineasDe(guardada.identificador())));
    }

    // ------------------------------------------------------------------

    /**
     * Las líneas de la versión nueva: las corregidas donde las hay, las anteriores donde no.
     *
     * <p>Un ejercicio del periodo nuevo que la versión anterior no cubría y que nadie corrige no se
     * puede inventar: se rechaza nombrándolo. Rellenarlo con una línea vacía diría que se fiscalizó
     * y no se encontró nada, que es una afirmación y no una ausencia.
     */
    private static List<LineaDeLiquidacion> recomponer(
            List<LineaDeLiquidacion> anteriores,
            List<CorreccionDeLinea> correcciones,
            Ejercicio desde,
            Ejercicio hasta) {

        Map<Integer, LineaDeLiquidacion> porEjercicio = new LinkedHashMap<>();
        for (LineaDeLiquidacion linea : anteriores) {
            porEjercicio.put(linea.ejercicio().valor(), linea);
        }
        Map<Integer, CorreccionDeLinea> corregidas = new LinkedHashMap<>();
        for (CorreccionDeLinea correccion : correcciones) {
            corregidas.put(correccion.ejercicio().valor(), correccion);
        }

        List<LineaDeLiquidacion> nuevas = new ArrayList<>();
        for (Ejercicio ejercicio = desde;
                ejercicio.compareTo(hasta) <= 0;
                ejercicio = ejercicio.siguiente()) {

            LineaDeLiquidacion base = porEjercicio.get(ejercicio.valor());
            if (base == null) {
                throw new EjercicioSinLineaAnterior(ejercicio);
            }
            CorreccionDeLinea correccion = corregidas.get(ejercicio.valor());
            // La linea se corrige a si misma (#340): hasta entonces este caso de uso recalculaba la
            // condicion reinventando los lados de la comparacion a partir de los nulos de la
            // linea, y un vehiculo CONFORME salia OMISO. Lo que la condicion necesita y la linea
            // no guarda lo deriva `corregidaCon` de la condicion guardada.
            nuevas.add(correccion == null ? base : base.corregidaCon(correccion));
        }
        return nuevas;
    }

    /**
     * La reliquidación y la explicación de qué cambió.
     *
     * @param liquidacion la versión nueva
     * @param diferencia qué cambió respecto de la anterior (AC 2)
     */
    public record Resultado(Liquidacion liquidacion, DiferenciaEntreLiquidaciones diferencia) {

        public Resultado {
            Objects.requireNonNull(liquidacion, "El resultado es una liquidacion");
            Objects.requireNonNull(
                    diferencia,
                    "Una reliquidacion que no explica la diferencia deja al contribuyente sin"
                            + " saber por que cambio su deuda (AC 2 de #49)");
        }
    }

    /** No hay ninguna liquidacion con ese numero. */
    public static final class LiquidacionInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionInexistente(String numero) {
            super("No hay ninguna liquidacion de fiscalizacion con el numero '" + numero + "'");
        }
    }

    /** Se pidio reliquidar una version que ya fue sustituida por otra. */
    public static final class NoEsLaUltimaVersion extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NoEsLaUltimaVersion(String pedida, String ultima) {
            super(
                    "La liquidacion "
                            + pedida
                            + " ya fue sustituida por "
                            + ultima
                            + ": se reliquida la ultima version, o la cadena de versiones se"
                            + " bifurca y el historico deja de poder reconstruir el proceso");
        }
    }

    /** El periodo nuevo abarca un ejercicio que la version anterior no cubria. */
    public static final class EjercicioSinLineaAnterior extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EjercicioSinLineaAnterior(Ejercicio ejercicio) {
            super(
                    "El ejercicio "
                            + ejercicio
                            + " no estaba en la liquidacion anterior: una reliquidacion corrige lo"
                            + " liquidado, y ampliar el periodo exige liquidar ese ejercicio con su"
                            + " propio contraste, no rellenarlo con una linea vacia");
        }
    }
}
