package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
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
 * cuánto se debe— cuánto vale cada obligación <b>a la fecha pedida</b>. La pregunta va por API
 * pública: este contexto no lee ni una tabla ajena (ARQ-01 §4).
 *
 * <h2>Hoy la respuesta está vacía, y eso es correcto</h2>
 *
 * <p>Ninguna liquidación genera cargos todavía: convertir la diferencia en deuda es la
 * transferencia a rentas (RF-054, <b>#52</b>) y el importe es D-02a (<b>#198</b>). Así que el
 * estado de cuenta enumera las <b>obligaciones fiscalizadas</b> —unidad, ejercicio, tributo— y,
 * para cada una, lo que el libro diga con su fecha. Cuando no hay asiento, la línea sale sin cifra
 * en vez de con un cero: un cero se lee como «no debe nada», y lo que pasa es que todavía no se ha
 * determinado.
 */
@Service
public class EstadoDeCuentaDeFiscalizacion {

    /** Como {@code cuentacorriente} nombra al predial. La fiscalización predial imputa ahí. */
    private static final String TRIBUTO_PREDIAL = TributoDelLibro.PREDIAL.texto();

    /** Y al patrimonio vehicular. */
    private static final String TRIBUTO_VEHICULAR = TributoDelLibro.VEHICULAR.texto();

    private final LiquidacionRepository liquidaciones;
    private final ConsultaDeDeudaPublica deuda;

    public EstadoDeCuentaDeFiscalizacion(
            LiquidacionRepository liquidaciones, ConsultaDeDeudaPublica deuda) {
        this.liquidaciones = liquidaciones;
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

        List<ObligacionPublica> enElLibro = deuda.deTodoElContribuyente(contribuyenteId, aLaFecha);

        List<LineaDelEstadoDeCuenta> lineas = new ArrayList<>();
        Set<String> yaContadas = new HashSet<>();
        for (Liquidacion liquidacion : suyas) {
            for (LineaDeLiquidacion linea : liquidaciones.lineasDe(liquidacion.identificador())) {
                String clave = claveDe(linea);
                // Dos versiones de la misma liquidacion describen la misma obligacion: contarla
                // dos veces duplicaria la deuda de la pantalla. Se conserva la primera que
                // aparece, y `deContribuyente` las devuelve de la mas reciente a la mas antigua.
                if (!yaContadas.add(clave)) {
                    continue;
                }
                lineas.add(componer(liquidacion, linea, enElLibro, aLaFecha));
            }
        }
        return new EstadoDeCuenta(contribuyenteId, aLaFecha, true, lineas);
    }

    // ------------------------------------------------------------------

    private static LineaDelEstadoDeCuenta componer(
            Liquidacion liquidacion,
            LineaDeLiquidacion linea,
            List<ObligacionPublica> enElLibro,
            LocalDate aLaFecha) {

        String tributo = linea.predioId() != null ? TRIBUTO_PREDIAL : TRIBUTO_VEHICULAR;
        Dinero asentada = null;
        for (ObligacionPublica obligacion : enElLibro) {
            if (obligacion.ejercicio().equals(linea.ejercicio())
                    && Objects.equals(obligacion.predioId(), linea.predioId())
                    && Objects.equals(obligacion.vehiculoId(), linea.vehiculoId())
                    && obligacion.tributo().equalsIgnoreCase(tributo)) {
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
     * @param deuda cuánto se debe a la fecha; {@code null} si el libro no tiene ningún asiento de
     *     esta obligación —lo que hoy es siempre, porque la transferencia a rentas es #52 y el
     *     importe es #198—
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
