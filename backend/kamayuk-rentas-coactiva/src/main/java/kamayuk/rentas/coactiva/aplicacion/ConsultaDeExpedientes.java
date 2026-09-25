package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteEnConsulta;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.ObligacionDeCostas;
import kamayuk.rentas.coactiva.dominio.ObligacionDelExpediente;
import kamayuk.rentas.coactiva.dominio.ResumenDeLaCartera;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla de {@code coactiva_expedientes} y la ficha de un expediente (#40, RF-100).
 *
 * <h2>La deuda del expediente se pregunta, no se suma de lo congelado</h2>
 *
 * <p>Un valor guarda su desglose tal como estaba el dia de la emision (AC de #37). Sumar eso y
 * pintarlo como «Deuda S/» daria la cifra de un dia pasado con la etiqueta de hoy, que es
 * exactamente lo que la regla 9 prohibe.
 *
 * <p>Lo que se hace es: se piden al modulo de valores <b>que obligaciones</b> formalizan los
 * valores del expediente, y se le pregunta a {@code cuentacorriente} —la unica fuente de cuanto se
 * debe— cuanto vale cada una <b>a la fecha pedida</b>. Las dos preguntas van por API publica: este
 * contexto no lee ni una tabla ajena (ARQ-01 §4).
 *
 * <p><b>Las obligaciones se deduplican.</b> Dos valores del mismo expediente pueden formalizar la
 * misma obligacion —una orden de pago y, mas tarde, una resolucion de determinacion sobre el mismo
 * predial de 2025—, y contarla dos veces duplicaria la deuda del procedimiento.
 *
 * <p><b>Las costas se preguntan igual que lo demas</b> (#42). Desde que un expediente tiene costas
 * liquidadas, {@code costa_obligacion} dice en que obligaciones del libro viven —las suyas, no las
 * del contribuyente entero— y su importe sale de la <b>misma</b> lectura del libro y a la
 * <b>misma</b> fecha que las otras cuatro cifras. No hay ninguna columna de costas en el expediente
 * y ningun importe recompuesto aqui: si la hubiera, la grilla y la ventanilla podrian discrepar.
 *
 * <h2>Lo acogido a un convenio no es exigible (#403)</h2>
 *
 * <p>Una obligacion del expediente que el libro tiene en fase {@code CONVENIO} no suma a la deuda
 * del expediente: va a {@link DeudaDelExpediente#enConvenio()}, aparte. Acogerse no cambia el total
 * del libro, asi que sin mirar la fase —que {@link ObligacionPublica#acogidaAConvenio()} publica
 * desde #403— la deuda fraccionada salia como materia de cobranza, la guarda de los actos la veia
 * viva y la REC-2 la imprimia como «total exigible». La fase la decide {@code cuentacorriente}, que
 * es quien la sabe; aqui solo se pregunta.
 *
 * <p>Por {@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL}, y sin
 * el la politica RLS falla en vez de devolver filas.
 */
@Service
public class ConsultaDeExpedientes {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final ValoresEnCoactiva valores;
    private final ConsultaDeDeudaPublica deuda;
    private final LiquidacionDeCostasRepository costas;

    public ConsultaDeExpedientes(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            ValoresEnCoactiva valores,
            ConsultaDeDeudaPublica deuda,
            LiquidacionDeCostasRepository costas) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.valores = valores;
        this.deuda = deuda;
        this.costas = costas;
    }

    /**
     * La grilla, con la deuda de cada expediente actualizada a la fecha.
     *
     * @param aLaFecha a que dia se actualiza la deuda de cada fila (regla 9). No afecta al estado:
     *     el estado es el ultimo movimiento del historial, y eso no depende de cuando se mire
     */
    @Transactional(readOnly = true)
    public Pagina<ExpedienteConDeuda> buscar(
            CriterioDeExpedientes criterio, LocalDate aLaFecha, Paginacion paginacion) {

        Pagina<ExpedienteEnConsulta> pagina = expedientes.consultar(criterio, paginacion);
        // Una sola lectura del padron y del libro por contribuyente de la pagina, no por fila:
        // varios expedientes del mismo obligado son lo corriente en esta pantalla.
        Map<Long, List<ObligacionPublica>> obligacionesPorContribuyente = new HashMap<>();
        Map<Long, List<ValorParaCoactiva>> valoresPorContribuyente = new HashMap<>();

        return pagina.mapear(
                fila ->
                        new ExpedienteConDeuda(
                                fila,
                                deudaDe(
                                        fila.expediente(),
                                        aLaFecha,
                                        obligacionesPorContribuyente,
                                        valoresPorContribuyente)));
    }

    /**
     * El resumen de la cartera: cuantos expedientes hay en cada etapa (#272, RF-100).
     *
     * <p><b>Sin deuda.</b> Este resumen cuenta carpetas y no cifra ninguna, y eso no es un
     * descuido: la deuda de un expediente se compone leyendo el libro del obligado a la fecha
     * —{@link #deudaDe}— , asi que cifrar la cartera entera costaria una lectura del libro por
     * expediente. Es la misma decision que {@code ExpedientesSinRec} tomo para el frente de
     * aterrizaje, y ademas la cifra no seria segura: dos expedientes del mismo obligado pueden
     * formalizar la <b>misma</b> obligacion por dos valores distintos —la deduplicacion de {@code
     * componerDeuda} existe justamente porque eso pasa dentro de uno—, y sumarlos contaria esa
     * obligacion dos veces. {@code expediente_valor_unico_uq} impide que un <b>valor</b> viva en
     * dos expedientes; no impide que dos valores formalicen la misma obligacion.
     *
     * <p>{@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL} y la
     * politica RLS no puede evaluar {@code app.municipalidad_id}.
     */
    @Transactional(readOnly = true)
    public ResumenDeLaCartera resumenDeLaCartera(CriterioDeExpedientes criterio) {
        return new ResumenDeLaCartera(expedientes.contarPorEstado(criterio));
    }

    /** Un expediente por su numero, con su historial, su direccion vigente y su deuda. */
    @Transactional(readOnly = true)
    public Optional<FichaDelExpediente> porNumero(String numero, LocalDate aLaFecha) {
        return expedientes.porNumero(numero).map(expediente -> fichaDe(expediente, aLaFecha));
    }

    /** La deuda del expediente actualizada a esa fecha. */
    @Transactional(readOnly = true)
    public DeudaDelExpediente deudaDe(ExpedienteCoactivo expediente, LocalDate aLaFecha) {
        return deudaDe(expediente, aLaFecha, new HashMap<>(), new HashMap<>());
    }

    /**
     * La misma deuda, <b>obligación por obligación</b> (#426).
     *
     * <p>Es la lectura de la que {@code fraccionamiento_coactivo} saca sus filas: {@code
     * PeticionDeConvenioCoactivo.obligaciones[]} pide {@code tributo}, {@code ejercicio} y {@code
     * predioId}/{@code vehiculoId} por fila, y una suma no los tiene. Sale de la <b>misma</b>
     * composición que {@link #deudaDe} y a la misma fecha, así que la grilla y el total no pueden
     * discrepar: el total se calcula sumando exactamente estas filas.
     *
     * <p>Las obligaciones acogidas a un convenio <b>no</b> son filas (#403): no son exigibles ni se
     * pueden volver a acoger, y la pantalla que lee esta grilla es la que elige que fraccionar.
     * Estan en {@code total().enConvenio()}, fuera de la suma de las filas.
     *
     * @return vacío si no hay ningún expediente con ese número
     */
    @Transactional(readOnly = true)
    public Optional<DeudaPorObligacion> obligacionesDe(String numero, LocalDate aLaFecha) {
        return expedientes
                .porNumero(numero)
                .map(
                        expediente -> {
                            Composicion composicion =
                                    componerDeuda(
                                            expediente, aLaFecha, new HashMap<>(), new HashMap<>());
                            return new DeudaPorObligacion(
                                    expediente,
                                    EstadoDelExpediente.delHistorial(
                                            movimientos.deExpediente(expediente.identificador())),
                                    composicion.lineas(),
                                    composicion.total(),
                                    aLaFecha);
                        });
    }

    // ------------------------------------------------------------------

    private FichaDelExpediente fichaDe(ExpedienteCoactivo expediente, LocalDate aLaFecha) {
        List<MovimientoDelExpediente> historial =
                movimientos.deExpediente(expediente.identificador());
        List<ValorDelExpediente> susValores = expedientes.valoresDe(expediente.identificador());
        String vigente =
                historial.stream()
                        .filter(m -> m.direccionReferencial() != null)
                        .reduce((primero, segundo) -> segundo)
                        .map(MovimientoDelExpediente::direccionNueva)
                        .orElseGet(expediente::direccionReferencial);

        return new FichaDelExpediente(
                expediente,
                EstadoDelExpediente.delHistorial(historial),
                vigente,
                susValores,
                historial,
                deudaDe(expediente, aLaFecha));
    }

    private DeudaDelExpediente deudaDe(
            ExpedienteCoactivo expediente,
            LocalDate aLaFecha,
            Map<Long, List<ObligacionPublica>> obligacionesPorContribuyente,
            Map<Long, List<ValorParaCoactiva>> valoresPorContribuyente) {
        return componerDeuda(
                        expediente, aLaFecha, obligacionesPorContribuyente, valoresPorContribuyente)
                .total();
    }

    /**
     * La composición completa: las filas y su suma, en <b>un solo recorrido</b>.
     *
     * <p>Las dos salen de aquí y no de dos métodos, y eso es la decisión: el total se calcula
     * sumando exactamente las filas que se publican. Componerlos por separado sería tener dos
     * definiciones de «la deuda del expediente», y la que se lea en la grilla podría no ser la que
     * imprime la REC-2 (RNF-083).
     */
    private Composicion componerDeuda(
            ExpedienteCoactivo expediente,
            LocalDate aLaFecha,
            Map<Long, List<ObligacionPublica>> obligacionesPorContribuyente,
            Map<Long, List<ValorParaCoactiva>> valoresPorContribuyente) {

        long contribuyente = expediente.contribuyenteId();

        Set<Long> delExpediente = new HashSet<>();
        for (ValorDelExpediente valor : expedientes.valoresDe(expediente.identificador())) {
            delExpediente.add(valor.valorId());
        }

        Set<ClaveDeObligacionPublica> claves = new HashSet<>();
        if (!delExpediente.isEmpty()) {
            List<ValorParaCoactiva> susValores =
                    valoresPorContribuyente.computeIfAbsent(
                            contribuyente, id -> valores.delContribuyente(id, aLaFecha));
            for (ValorParaCoactiva valor : susValores) {
                if (!delExpediente.contains(valor.id())) {
                    continue;
                }
                for (ObligacionDelValor obligacion : valor.obligaciones()) {
                    claves.add(obligacion.clave());
                }
            }
        }

        // Las obligaciones en las que viven las costas DE ESTE expediente (#42, V35). No las del
        // contribuyente: `costa_obligacion` es lo que las distingue, porque la clave del libro no
        // incluye el expediente.
        Set<ClaveDeObligacionPublica> deCostas = new HashSet<>();
        for (ObligacionDeCostas obligacion : costas.obligacionesDe(expediente.identificador())) {
            deCostas.add(claveDe(obligacion));
        }

        if (claves.isEmpty() && deCostas.isEmpty()) {
            return new Composicion(List.of(), DeudaDelExpediente.ninguna(aLaFecha));
        }

        // Todas, a sabiendas (#401): la composicion del expediente lista las obligaciones de SUS
        // valores, y la que ya se cobro sigue siendo suya —en 0,00, que es lo que dice que se
        // cobro—. Solo las pendientes harian desaparecer lineas del expediente al pagarlas.
        List<ObligacionPublica> obligaciones =
                obligacionesPorContribuyente.computeIfAbsent(
                        contribuyente, id -> deuda.todasDe(id, aLaFecha));

        List<ObligacionDelExpediente> lineas = new ArrayList<>();
        DeudaDelExpediente acumulada = DeudaDelExpediente.ninguna(aLaFecha);
        Dinero delProcedimiento = Dinero.de("0.00");
        Set<ClaveDeObligacionPublica> contadas = new HashSet<>();
        for (ObligacionPublica obligacion : obligaciones) {
            ClaveDeObligacionPublica clave = obligacion.clave();
            if (!contadas.add(clave)) {
                continue;
            }
            boolean esCosta = deCostas.contains(clave);
            if (!esCosta && !claves.contains(clave)) {
                continue;
            }
            if (obligacion.acogidaAConvenio()) {
                // Acogida a un convenio no es exigible (#403): la cobra el cronograma, y un
                // embargo sobre ella cobraria dos veces lo mismo. Va a su propia cifra -ni a las
                // cuatro partes ni a las costas- y no es una linea que se pueda volver a acoger.
                // Vale igual para una costa fraccionada: tambien la cobra el convenio.
                acumulada = acumulada.masEnConvenio(obligacion.total());
                continue;
            }
            if (esCosta) {
                // Las costas se cuentan aparte y ENTERAS -las cuatro partes de su obligacion-,
                // porque el cargo se asento con concepto GASTO y no devenga insoluto ni interes.
                // Contarlas ademas en `gasto` las sumaria dos veces al total.
                delProcedimiento = delProcedimiento.mas(obligacion.total());
                lineas.add(lineaDe(obligacion, true, aLaFecha));
                continue;
            }
            acumulada =
                    acumulada.mas(
                            obligacion.insoluto(),
                            obligacion.reajuste(),
                            obligacion.interes(),
                            obligacion.gasto());
            lineas.add(lineaDe(obligacion, false, aLaFecha));
        }
        return new Composicion(List.copyOf(lineas), acumulada.conCostas(delProcedimiento));
    }

    private static ObligacionDelExpediente lineaDe(
            ObligacionPublica obligacion, boolean esCosta, LocalDate aLaFecha) {
        return new ObligacionDelExpediente(
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId(),
                obligacion.insoluto(),
                obligacion.reajuste(),
                obligacion.interes(),
                obligacion.gasto(),
                esCosta,
                aLaFecha);
    }

    /**
     * La clave de una obligacion de costas (#42): sin unidad, porque una costa no es de un predio
     * ni de un vehiculo sino del procedimiento. Las demas llegan con la suya ({@link
     * ClaveDeObligacionPublica}, #407).
     */
    private static ClaveDeObligacionPublica claveDe(ObligacionDeCostas obligacion) {
        return new ClaveDeObligacionPublica(
                obligacion.tributo(), obligacion.ejercicio(), null, null);
    }

    /** Las filas del expediente y su suma, compuestas de una vez. */
    private record Composicion(List<ObligacionDelExpediente> lineas, DeudaDelExpediente total) {}

    /**
     * La deuda de un expediente, obligación por obligación y con su suma (#426).
     *
     * @param expediente la cabecera, para poder decir de quién es la deuda
     * @param estado en qué punto está el procedimiento
     * @param obligaciones una fila por obligación, sin sumar nada
     * @param total la suma de esas filas, con sus costas aparte
     * @param aLaFecha el día al que están todas las cifras (regla 9)
     */
    public record DeudaPorObligacion(
            ExpedienteCoactivo expediente,
            EstadoDelExpediente estado,
            List<ObligacionDelExpediente> obligaciones,
            DeudaDelExpediente total,
            LocalDate aLaFecha) {

        public DeudaPorObligacion {
            Objects.requireNonNull(expediente, "La deuda es la de un expediente");
            Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
            obligaciones = List.copyOf(obligaciones);
            Objects.requireNonNull(total, "Toda cifra viaja con su fecha (regla 9)");
            Objects.requireNonNull(aLaFecha, "Toda cifra viaja con su fecha (regla 9)");
        }
    }

    /**
     * Una fila de la grilla con su deuda actualizada.
     *
     * @param fila la cabecera y lo que la pantalla muestra
     * @param deuda cuanto se debe, con la fecha a la que esta (regla 9)
     */
    public record ExpedienteConDeuda(ExpedienteEnConsulta fila, DeudaDelExpediente deuda) {

        public ExpedienteConDeuda {
            Objects.requireNonNull(fila, "La fila es obligatoria");
            Objects.requireNonNull(deuda, "Toda cifra viaja con su fecha (regla 9)");
        }
    }

    /**
     * Un expediente con todo lo que sus tres pantallas necesitan.
     *
     * @param expediente la cabecera
     * @param estado el estado derivado del historial
     * @param direccionReferencialVigente la del ultimo cambio, o la de apertura
     * @param valores los valores que agrupa
     * @param historial la traza completa, del primero al ultimo
     * @param deuda cuanto se debe, con su fecha
     */
    public record FichaDelExpediente(
            ExpedienteCoactivo expediente,
            EstadoDelExpediente estado,
            @Nullable String direccionReferencialVigente,
            List<ValorDelExpediente> valores,
            List<MovimientoDelExpediente> historial,
            DeudaDelExpediente deuda) {

        public FichaDelExpediente {
            Objects.requireNonNull(expediente, "La ficha es la de un expediente");
            Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
            valores = List.copyOf(valores);
            historial = List.copyOf(historial);
            Objects.requireNonNull(deuda, "Toda cifra viaja con su fecha (regla 9)");
        }

        /** El ejercicio del expediente, que la pantalla pinta como «Año». */
        public Ejercicio ejercicio() {
            return expediente.ejercicio();
        }
    }
}
