package kamayuk.rentas.valores.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.ObligacionYaFormalizada;
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
 * <p>Cada {@link SelectorDeObligacion} se cruza contra {@link ConsultaDeDeudaPublica#pendientesDe},
 * que es la unica fuente de cuanto se debe. El desglose que este servicio congela en {@link
 * ValorDetalle} —insoluto, reajuste, interes, gasto— es exactamente el que devuelve esa consulta
 * —nunca uno calculado aqui—, y una vez guardado no se vuelve a leer: reimprimir el valor dos anios
 * despues devuelve ese mismo desglose (AC de #37), aunque el saldo real haya cambiado.
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
 *
 * <h2>Una obligacion se formaliza una vez por tipo (#366)</h2>
 *
 * <p>La deuda que lee este servicio no dice la fase: despues de emitir, la obligacion sigue
 * enseñando lo mismo, y hasta #366 un doble envio emitia un segundo valor por la deuda que el
 * primero ya estaba cobrando, y un selector repetido en la misma peticion congelaba dos lineas de
 * la misma obligacion —un acto de 1 600,00 sobre una deuda de 800,00—. Las dos defensas viven aqui
 * y no en el controlador porque la masiva y las multas entran por este mismo metodo:
 *
 * <ul>
 *   <li>el mismo selector dos veces se rechaza con {@link ObligacionRepetida}, como ya hacen la
 *       orden de cobro, el convenio y el registro de abonos;
 *   <li>la regla {@link ObligacionYaFormalizada}, evaluada <b>despues</b> de {@link
 *       ValorRepository#bloquearLasObligaciones}, rechaza con {@link YaFormalizada} el segundo
 *       valor vivo del mismo tipo, y deja emitir uno de otro tipo —una RD tras una OP— sin volver a
 *       mover una fase que ya esta en VALOR.
 * </ul>
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
    private final ObligacionYaFormalizada yaFormalizada;

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
        this.yaFormalizada = new ObligacionYaFormalizada(repositorio);
    }

    /**
     * Emite el valor a la fecha de hoy: congela la deuda seleccionada, la numera y mueve su fase.
     *
     * @param tipo OP, RD o RM
     * @param contribuyenteId a quien se emite; ya resuelto por quien llama
     * @param obligaciones que obligaciones formaliza; al menos una
     * @param observacion por que se emite (regla 10)
     * @throws SinObligaciones si {@code obligaciones} llega vacia
     * @throws ObligacionRepetida si la misma obligacion llega dos veces
     * @throws YaFormalizada si un valor vivo del mismo tipo ya formaliza alguna de ellas
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
     * @throws ObligacionRepetida si la misma obligacion llega dos veces
     * @throws YaFormalizada si un valor vivo del mismo tipo ya formaliza alguna de ellas
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
        // El selector es un record que ya normaliza el tributo: su igualdad es la de la clave.
        if (new LinkedHashSet<>(obligaciones).size() != obligaciones.size()) {
            throw new ObligacionRepetida(obligaciones);
        }

        // El candado ANTES de la regla: sin el, dos peticiones simultaneas la pasan las dos.
        repositorio.bloquearLasObligaciones(contribuyenteId, obligaciones);
        List<ObligacionYaFormalizada.Formalizacion> formalizaciones =
                new ArrayList<>(obligaciones.size());
        for (SelectorDeObligacion selector : obligaciones) {
            ObligacionYaFormalizada.Formalizacion formalizacion =
                    yaFormalizada.de(contribuyenteId, selector);
            Optional<Valor> delMismoTipo = formalizacion.porUnValorDe(tipo);
            if (delMismoTipo.isPresent()) {
                throw new YaFormalizada(tipo, selector, delMismoTipo.get());
            }
            formalizaciones.add(formalizacion);
        }

        LocalDate hoy = fecha;
        // Solo las que deben (#401). Con todas las del libro, una obligacion pagada o dada de baja
        // se encontraba en 0,00 y salia un valor de 0,00 en EMITIDO, con su correlativo
        // consumido, que se podia notificar y pasar a coactiva.
        List<ObligacionPublica> disponibles = deuda.pendientesDe(contribuyenteId, hoy);

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
            // Con un valor vivo de otro tipo la deuda ya salio de ORDINARIA: moverla otra vez
            // dejaria en el libro dos salidas por una sola deuda. Que haya algo que mover ya no se
            // pregunta aqui: solo llega lo que `pendientesDe` devolvio (#401).
            if (!formalizaciones.get(i).yaEstaEnFaseValor()) {
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

    /**
     * La obligacion con deuda que el selector nombra, cruzada con la clave del libro (#407): la
     * misma {@link ClaveDeObligacionPublica} con que coactiva compone y deduplica la deuda del
     * expediente, y no un filtro de cuatro campos propio.
     */
    private static Optional<ObligacionPublica> buscar(
            List<ObligacionPublica> disponibles, SelectorDeObligacion selector) {
        ClaveDeObligacionPublica buscada =
                new ClaveDeObligacionPublica(
                        selector.tributo(),
                        selector.ejercicio(),
                        selector.predioId(),
                        selector.vehiculoId());
        return disponibles.stream().filter(o -> o.clave().equals(buscada)).findFirst();
    }

    private void auditar(Valor valor, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "valor", String.valueOf(valor.id()), Operacion.ALTA, observacion)
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

    /**
     * La misma obligacion llega dos veces en la peticion (#366).
     *
     * <p>Sin este rechazo, cada selector congelaba su linea y movia su fase: la misma deuda de
     * 800,00 salia como un valor de 1 600,00, y el acto que se notifica exigia el doble de lo
     * debido.
     */
    public static final class ObligacionRepetida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient SelectorDeObligacion repetida;

        ObligacionRepetida(List<SelectorDeObligacion> obligaciones) {
            this(primeraRepetida(obligaciones));
        }

        private ObligacionRepetida(SelectorDeObligacion repetida) {
            super(
                    "La obligacion de "
                            + descripcionDe(repetida)
                            + " llega mas de una vez: un valor formaliza cada obligacion una sola"
                            + " vez");
            this.repetida = repetida;
        }

        public SelectorDeObligacion repetida() {
            return repetida;
        }

        private static SelectorDeObligacion primeraRepetida(List<SelectorDeObligacion> todas) {
            Set<SelectorDeObligacion> vistas = new HashSet<>();
            for (SelectorDeObligacion selector : todas) {
                if (!vistas.add(selector)) {
                    return selector;
                }
            }
            throw new IllegalArgumentException("Ninguna obligacion se repite");
        }
    }

    /**
     * Un valor vivo del mismo tipo ya formaliza la obligacion (#366): la regla {@link
     * ObligacionYaFormalizada} se cumple.
     *
     * <p>Nombra ese valor, que es lo que quien pidio la emision necesita para seguir: si fue un
     * reintento, el valor que busca ya existe.
     */
    public static final class YaFormalizada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient SelectorDeObligacion selector;
        private final String numero;

        YaFormalizada(TipoValor tipo, SelectorDeObligacion selector, Valor vivo) {
            super(
                    "La obligacion de "
                            + descripcionDe(selector)
                            + " ya esta formalizada por "
                            + vivo.numero()
                            + ", que sigue vivo: otra "
                            + tipo.codigo()
                            + " seria un segundo titulo por la misma deuda");
            this.selector = selector;
            this.numero = vivo.numero();
        }

        public SelectorDeObligacion selector() {
            return selector;
        }

        /** El numero impreso del valor que ya formaliza la obligacion. */
        public String numero() {
            return numero;
        }
    }

    /** Tributo, ejercicio y unidad, sin datos personales: acaba en el cuerpo de un 409 o un 422. */
    private static String descripcionDe(SelectorDeObligacion selector) {
        String unidad =
                selector.predioId() != null
                        ? " del predio " + selector.predioId()
                        : selector.vehiculoId() != null
                                ? " del vehiculo " + selector.vehiculoId()
                                : "";
        return selector.tributo() + " del ejercicio " + selector.ejercicio().valor() + unidad;
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
