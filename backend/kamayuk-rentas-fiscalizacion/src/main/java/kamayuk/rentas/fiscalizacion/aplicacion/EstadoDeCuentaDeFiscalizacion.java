package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.ConsultaDeLoOriginado;
import kamayuk.rentas.cuentacorriente.ObligacionOriginada;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El estado de cuenta de fiscalización: qué deudas de un contribuyente nacieron de un proceso
 * fiscalizador ({@code fisc_estado_cuenta}, RF-056).
 *
 * <h2>La deuda se pregunta, no se suma de lo liquidado</h2>
 *
 * <p>Mismo razonamiento, y mismo precedente, que {@code ConsultaDeExpedientes} en coactiva (#40).
 * Una liquidación congela el contraste del día en que se emitió; sumar eso y pintarlo como «Deuda
 * S/» daría la cifra de un día pasado con la etiqueta de hoy, que es lo que la regla 9 prohíbe.
 *
 * <p>Lo que se hace es: se toman de las liquidaciones del contribuyente <b>qué unidades y qué
 * ejercicios</b> se fiscalizaron, y se le pregunta a {@code cuentacorriente} —la única fuente de
 * cuánto se debe— cuánto vale <b>a la fecha pedida</b> lo que la fiscalización originó. La pregunta
 * va por API pública: este contexto no lee ni una tabla ajena (ARQ-01 §4).
 *
 * <h2>Lo que nació de la fiscalización es lo que originó su RDF, y nada más (#342)</h2>
 *
 * <p>Lo único que el libro guarda del origen de una deuda es el <b>documento de origen</b> de su
 * cargo, y la transferencia a rentas escribe en cada cargo que asienta el número de la resolución
 * de determinación. Esa es la identidad de «lo fiscalizado» en el libro. Hasta #342 se casaba otra
 * cosa: toda obligación del libro con el mismo ejercicio, la misma unidad y el tributo PREDIAL o
 * VEHICULAR. La deuda ordinaria del vehículo o del predio —un alta, una importación— tiene esa
 * misma clave, así que el fiscalizador le enseñaba al contribuyente como determinada de oficio
 * deuda que era ordinaria, y un total que la sumaba; y la multa, que la transferencia asienta como
 * {@code MULTA_TRIBUTARIA}, no entraba nunca.
 *
 * <p>Ahora se pregunta por las RDF de las liquidaciones del contribuyente, con {@link
 * ConsultaDeLoOriginado#deLoOriginadoPor}, y solo por ellas; si no tiene ninguna, sin consultar el
 * libro: no se transfirió nada. Cada línea suma las claves de saldo que <b>esas</b> RDF originaron
 * en su ejercicio y su unidad, sea cual sea el tributo.
 *
 * <p>Las RDF del contribuyente, y no solo la de la liquidación que la línea muestra: la línea es
 * una por ejercicio y unidad y enseña la liquidación más reciente, y la más reciente puede no ser
 * la transferida —una reliquidación posterior a la transferencia, o la liquidación de una segunda
 * visita, que {@code UnidadYaDeterminada} (#462) ya no deja transferir—. La deuda que la RDF viva
 * originó sigue siendo lo determinado de oficio sobre esa obligación, y esconderla porque la
 * versión que se pinta no tiene papel sería el defecto al revés. Como solo puede haber una RDF viva
 * por unidad y ejercicio (#462), sumar por ejercicio y unidad no mezcla dos.
 *
 * <p>Cuando la RDF existe pero no asentó ningún cargo —lo que hace hoy la transferencia, con D-02a
 * abierta (<b>#198</b>)—, la línea también sale sin cifra en vez de con un cero: un cero se lee
 * como «no debe nada», y lo que pasa es que todavía no se ha determinado ninguna cifra.
 */
@Service
public class EstadoDeCuentaDeFiscalizacion {

    /**
     * Como {@code cuentacorriente} nombra al predial. Es el <b>rótulo</b> de la línea: la cifra ya
     * no se filtra por tributo, porque la multa de la misma RDF va a otro (#342).
     */
    private static final String TRIBUTO_PREDIAL = TributoDelLibro.PREDIAL.texto();

    /** Y al patrimonio vehicular. */
    private static final String TRIBUTO_VEHICULAR = TributoDelLibro.VEHICULAR.texto();

    private final LiquidacionRepository liquidaciones;
    private final ResolucionDeDeterminacionRepository resoluciones;
    private final ConsultaDeLoOriginado deuda;

    public EstadoDeCuentaDeFiscalizacion(
            LiquidacionRepository liquidaciones,
            ResolucionDeDeterminacionRepository resoluciones,
            ConsultaDeLoOriginado deuda) {
        this.liquidaciones = liquidaciones;
        this.resoluciones = resoluciones;
        this.deuda = deuda;
    }

    /**
     * Las obligaciones que la fiscalización de este contribuyente originó, con su deuda a la fecha.
     *
     * @param contribuyenteId el fiscalizado
     * @param aLaFecha a qué día se actualiza la deuda (regla 9)
     */
    @Transactional(readOnly = true)
    public EstadoDeCuenta de(long contribuyenteId, LocalDate aLaFecha) {
        Objects.requireNonNull(aLaFecha, "Toda cifra de deuda indica su fecha (regla 9)");

        List<Liquidacion> suyas = liquidaciones.deContribuyente(contribuyenteId);
        if (suyas.isEmpty()) {
            return new EstadoDeCuenta(contribuyenteId, aLaFecha, false, List.of());
        }

        // La RDF de cada liquidacion, si se transfirio: es el documento de origen que la
        // transferencia escribio en cada cargo, y lo unico por lo que se pregunta al libro (#342).
        Set<String> rdfs = new LinkedHashSet<>();
        for (Liquidacion liquidacion : suyas) {
            resoluciones
                    .deLiquidacion(liquidacion.identificador())
                    .map(ResolucionDeDeterminacion::numero)
                    .ifPresent(rdfs::add);
        }

        // TODAS las claves que originaron, tambien las saldadas, y a sabiendas (#401): una deuda
        // ya cobrada tiene que salir «asentada» en 0,00, y no como si nunca hubiera llegado al
        // libro. Sin ninguna RDF no hay nada que preguntar.
        List<ObligacionOriginada> originadas =
                rdfs.isEmpty()
                        ? List.of()
                        : deuda.deLoOriginadoPor(contribuyenteId, rdfs, aLaFecha);

        List<LineaDelEstadoDeCuenta> lineas = new ArrayList<>();
        Set<String> yaContadas = new HashSet<>();
        for (Liquidacion liquidacion : suyas) {
            for (LineaDeLiquidacion linea : liquidaciones.lineasDe(liquidacion.identificador())) {
                String clave = claveDe(linea);
                // Dos versiones de la misma liquidacion, o dos actas sobre la misma unidad,
                // describen la misma obligacion: contarla dos veces duplicaria la deuda de la
                // pantalla. Se conserva la primera que aparece, y `deContribuyente` las devuelve
                // de la mas reciente a la mas antigua —acta y version— (#342).
                if (!yaContadas.add(clave)) {
                    continue;
                }
                lineas.add(componer(liquidacion, linea, originadas, aLaFecha));
            }
        }
        return new EstadoDeCuenta(contribuyenteId, aLaFecha, true, lineas);
    }

    // ------------------------------------------------------------------

    /**
     * La línea, con lo que las RDF del contribuyente originaron en su ejercicio y su unidad.
     *
     * <p>La suma de <b>todas</b> las claves de saldo que originaron ahí —el tributo y la multa—, o
     * sin cifra si ninguna RDF originó nada en esa obligación: no se transfirió, o se transfirió
     * sin cargos.
     */
    private static LineaDelEstadoDeCuenta componer(
            Liquidacion liquidacion,
            LineaDeLiquidacion linea,
            List<ObligacionOriginada> originadas,
            LocalDate aLaFecha) {

        String tributo = linea.predioId() != null ? TRIBUTO_PREDIAL : TRIBUTO_VEHICULAR;
        Dinero asentada = null;
        for (ObligacionOriginada originada : originadas) {
            ObligacionPublica obligacion = originada.obligacion();
            if (obligacion.ejercicio().equals(linea.ejercicio())
                    && Objects.equals(obligacion.predioId(), linea.predioId())
                    && Objects.equals(obligacion.vehiculoId(), linea.vehiculoId())) {
                asentada = asentada == null ? obligacion.total() : asentada.mas(obligacion.total());
            }
        }
        return new LineaDelEstadoDeCuenta(
                liquidacion.numero(),
                linea.ejercicio(),
                tributo,
                linea.predioId(),
                linea.vehiculoId(),
                linea.condicion().name(),
                asentada,
                aLaFecha);
    }

    private static String claveDe(LineaDeLiquidacion linea) {
        return linea.ejercicio()
                + "|"
                + (linea.predioId() == null ? "v" + linea.vehiculoId() : "p" + linea.predioId());
    }

    /**
     * El estado de cuenta completo.
     *
     * @param contribuyenteId el fiscalizado
     * @param aLaFecha el día al que están actualizadas todas las cifras (regla 9)
     * @param fiscalizado si a este contribuyente se le abrió alguna vez una liquidación
     * @param lineas una por obligación fiscalizada
     */
    public record EstadoDeCuenta(
            long contribuyenteId,
            LocalDate aLaFecha,
            boolean fiscalizado,
            List<LineaDelEstadoDeCuenta> lineas) {

        public EstadoDeCuenta {
            Objects.requireNonNull(aLaFecha, "Toda cifra indica a que fecha esta (regla 9)");
            lineas = List.copyOf(lineas);
        }

        /**
         * El total, si a este contribuyente se le fiscalizó y <b>todas</b> las líneas tienen cifra.
         *
         * <p>{@code null} si alguna no la tiene, y no la suma de las que sí: un total parcial
         * presentado como total es peor que ningún total, porque nadie lo distingue del completo.
         *
         * <p><b>Y {@code null} también cuando no hay ninguna línea</b> (#546). Sumar sobre la lista
         * vacía da {@link Dinero#CERO}, y ese cero salía por HTTP como {@code "importe":"0"} para
         * quien <b>nunca fue fiscalizado</b> — indistinguible del cero de quien sí lo fue y no debe
         * nada, que es exactamente lo que el javadoc de {@code EstadoDeCuentaResource} dice de sí
         * mismo que hay que evitar: «un cero se lee como *no debe nada*». No hay un total de un
         * procedimiento que no existe; lo que hay es {@link #fiscalizado} en {@code false}, y la
         * pantalla lo dice con palabras en vez de con una cifra.
         */
        public @Nullable Dinero total() {
            if (!fiscalizado) {
                return null;
            }
            Dinero acumulado = Dinero.CERO;
            for (LineaDelEstadoDeCuenta linea : lineas) {
                Dinero suya = linea.deuda();
                if (suya == null) {
                    return null;
                }
                acumulado = acumulado.mas(suya);
            }
            return acumulado;
        }
    }

    /**
     * Una obligación originada en fiscalización, con lo que el libro dice de ella.
     *
     * @param numeroLiquidacion de qué liquidación viene
     * @param ejercicio el ejercicio fiscalizado
     * @param tributo el tributo al que imputa
     * @param predioId la unidad, si es predial
     * @param vehiculoId la unidad, si es vehicular
     * @param condicion la del contraste
     * @param deuda cuánto se debe a la fecha de lo que las RDF del contribuyente originaron en esta
     *     obligación; {@code null} si ninguna originó nada en ella —no se transfirió, o la RDF no
     *     asentó ningún cargo, que es lo que hoy hace la transferencia mientras el importe siga en
     *     #198— (#342)
     * @param aLaFecha el día al que está la cifra (regla 9)
     */
    public record LineaDelEstadoDeCuenta(
            String numeroLiquidacion,
            Ejercicio ejercicio,
            String tributo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String condicion,
            @Nullable Dinero deuda,
            LocalDate aLaFecha) {

        public LineaDelEstadoDeCuenta {
            Objects.requireNonNull(numeroLiquidacion, "La linea dice de que liquidacion viene");
            Objects.requireNonNull(ejercicio, "La linea necesita su ejercicio");
            Objects.requireNonNull(tributo, "La linea necesita su tributo");
            Objects.requireNonNull(condicion, "La linea necesita su condicion");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica a que fecha esta (regla 9)");
        }

        /** Si el libro todavía no tiene nada de esta obligación. */
        public boolean sinAsentar() {
            return deuda == null;
        }
    }
}
