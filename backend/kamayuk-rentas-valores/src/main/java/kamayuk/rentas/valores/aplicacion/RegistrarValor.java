package kamayuk.rentas.valores.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite una orden de pago, una resolucion de determinacion o una resolucion de multa (#37, RF-090).
 *
 * <h2>Un valor no crea deuda: la formaliza</h2>
 *
 * <p>Cada {@link SelectorDeObligacion} se cruza contra {@link
 * ConsultaDeDeudaPublica#deTodoElContribuyente}, que es la unica fuente de cuanto se debe. El
 * desglose que este servicio congela en {@link ValorDetalle} —insoluto, reajuste, interes, gasto—
 * es exactamente el que devuelve esa consulta —nunca uno calculado aqui—, y una vez guardado no se
 * vuelve a leer: reimprimir el valor dos anios despues devuelve ese mismo desglose (AC de #37),
 * aunque el saldo real haya cambiado.
 *
 * <p>Formalizar mueve la deuda de la fase ordinaria a la fase {@code VALOR} del libro, con {@link
 * MovimientoDeFase} (#21). Las tres escrituras —congelar el detalle, mover la fase, numerar—
 * ocurren en la misma transaccion: una emision a medias no puede dejar un valor sin su movimiento
 * de fase, ni un movimiento de fase sin su valor.
 *
 * <h2>Numeracion</h2>
 *
 * <p>El correlativo lo entrega {@link ValorRepository#siguienteCorrelativo}, que lo garantiza unico
 * y sin huecos bajo concurrencia real con un {@code UPDATE} atomico contra la base, no con una
 * lectura seguida de una escritura desde Java. El formato —{@code TIPO-EJERCICIO-000001}— es
 * provisional hasta que D-09 decida la mascara final; lo unico que este servicio le exige es que no
 * se repita, y eso lo exige la base, no el formateo.
 */
@Service
public class RegistrarValor {

    /** Provisional hasta D-09: con que ceros y en que orden va el correlativo. */
    private static final String FORMATO_NUMERO = "%s-%d-%06d";

    private final ValorRepository repositorio;
    private final ConsultaDeDeudaPublica deuda;
    private final MovimientoDeFase movimiento;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarValor(
            ValorRepository repositorio,
            ConsultaDeDeudaPublica deuda,
            MovimientoDeFase movimiento,
            Auditoria auditoria,
            Clock reloj) {
        this.repositorio = repositorio;
        this.deuda = deuda;
        this.movimiento = movimiento;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite el valor a la fecha de hoy: congela la deuda seleccionada, la numera y mueve su fase.
     *
     * @param tipo OP, RD o RM
     * @param contribuyenteId a quien se emite; ya resuelto por quien llama
     * @param obligaciones que obligaciones formaliza; al menos una
     * @param observacion por que se emite (regla 10)
     * @throws SinObligaciones si {@code obligaciones} llega vacia
     * @throws ObligacionSinDeuda si algun selector no coincide con ninguna obligacion con deuda del
     *     contribuyente a la fecha de hoy
     */
    @Transactional
    public Valor emitir(
            TipoValor tipo,
            long contribuyenteId,
            List<SelectorDeObligacion> obligaciones,
            Observacion observacion) {
        return emitir(tipo, contribuyenteId, obligaciones, observacion, LocalDate.now(reloj));
    }

    /**
     * Emite el valor a una fecha explicita: congela la deuda seleccionada, la numera y mueve su
     * fase.
     *
     * <p>Existe para la generacion masiva (#38): una corrida congela su {@code fechaCriterio} al
     * registrarse, y la etapa "generacion" tiene que evaluar la deuda de cada candidato a esa misma
     * fecha aunque se reanude dias despues -nunca a la fecha en que efectivamente corre-, o dos
     * ejecuciones de la misma corrida verian deuda distinta para el mismo contribuyente.
     *
     * @param fecha a que fecha se evalua la deuda disponible, y con la que nace el valor
     * @throws SinObligaciones si {@code obligaciones} llega vacia
     * @throws ObligacionSinDeuda si algun selector no coincide con ninguna obligacion con deuda del
     *     contribuyente a esa fecha
     */
    @Transactional
    public Valor emitir(
            TipoValor tipo,
            long contribuyenteId,
            List<SelectorDeObligacion> obligaciones,
            Observacion observacion,
            LocalDate fecha) {

        if (obligaciones.isEmpty()) {
            throw new SinObligaciones();
        }

        LocalDate hoy = fecha;
        List<ObligacionPublica> disponibles = deuda.deTodoElContribuyente(contribuyenteId, hoy);

        List<ValorDetalle> detalle = new ArrayList<>(obligaciones.size());
        List<ObligacionPublica> aMover = new ArrayList<>(obligaciones.size());
        for (SelectorDeObligacion selector : obligaciones) {
            ObligacionPublica obligacion =
                    buscar(disponibles, selector)
                            .orElseThrow(() -> new ObligacionSinDeuda(selector));
            detalle.add(
                    ValorDetalle.nuevo(
                            selector.tributo(),
                            obligacion.ejercicio(),
                            null,
                            selector.predioId(),
                            selector.vehiculoId(),
                            null,
                            obligacion.insoluto(),
                            obligacion.reajuste(),
                            obligacion.interes(),
                            obligacion.gasto()));
            aMover.add(obligacion);
        }

        Valor.Desglose desglose = Valor.desgloseDe(detalle);
        Ejercicio ejercicioDeEmision = Ejercicio.de(hoy);
        String numero =
                String.format(
                        Locale.ROOT,
                        FORMATO_NUMERO,
                        tipo.codigo(),
                        ejercicioDeEmision.valor(),
                        repositorio.siguienteCorrelativo(tipo, ejercicioDeEmision));

        Valor guardado =
                repositorio.insertar(
                        new Valor(
                                null,
                                tipo,
                                numero,
                                ejercicioDeEmision,
                                contribuyenteId,
                                tipo.baseLegal(),
                                desglose.insoluto(),
                                desglose.reajuste(),
                                desglose.interes(),
                                desglose.gasto(),
                                hoy,
                                EstadoDeValor.EMITIDO,
                                hoy,
                                null,
                                observacion),
                        detalle);

        for (int i = 0; i < obligaciones.size(); i++) {
            SelectorDeObligacion selector = obligaciones.get(i);
            ObligacionPublica obligacion = aMover.get(i);
            if (obligacion.total().esPositivo()) {
                movimiento.moverAValor(
                        obligacion.ejercicio(),
                        contribuyenteId,
                        selector.tributo(),
                        null,
                        selector.predioId(),
                        selector.vehiculoId(),
                        "VALOR-" + guardado.numero(),
                        obligacion.total(),
                        hoy,
                        guardado.numero(),
                        observacion);
            }
        }

        auditar(guardado, observacion);
        return guardado;
    }

    private static Optional<ObligacionPublica> buscar(
            List<ObligacionPublica> disponibles, SelectorDeObligacion selector) {
        return disponibles.stream()
                .filter(o -> o.tributo().equalsIgnoreCase(selector.tributo()))
                .filter(o -> o.ejercicio().equals(selector.ejercicio()))
                .filter(o -> java.util.Objects.equals(o.predioId(), selector.predioId()))
                .filter(o -> java.util.Objects.equals(o.vehiculoId(), selector.vehiculoId()))
                .findFirst();
    }

    private void auditar(Valor valor, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                valor.fechaEmision(),
                                "valor",
                                String.valueOf(valor.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(valor)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Valor valor) {
        return "{\"tipo\":\""
                + valor.tipo()
                + "\",\"numero\":\""
                + valor.numero()
                + "\",\"ejercicio\":"
                + valor.ejercicio().valor()
                + ",\"total\":"
                + valor.total().valor().toPlainString()
                + "}";
    }

    /** Un valor sin ninguna obligacion no formaliza nada. */
    public static final class SinObligaciones extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinObligaciones() {
            super("Un valor tiene que formalizar al menos una obligacion");
        }
    }

    /** El selector no coincide con ninguna obligacion con deuda del contribuyente, a hoy. */
    public static final class ObligacionSinDeuda extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final SelectorDeObligacion selector;

        ObligacionSinDeuda(SelectorDeObligacion selector) {
            super(
                    "No hay deuda de "
                            + selector.tributo()
                            + " del ejercicio "
                            + selector.ejercicio().valor()
                            + " para este contribuyente, a la fecha de hoy: no se puede formalizar"
                            + " lo que no se debe");
            this.selector = selector;
        }

        public SelectorDeObligacion selector() {
            return selector;
        }
    }
}
