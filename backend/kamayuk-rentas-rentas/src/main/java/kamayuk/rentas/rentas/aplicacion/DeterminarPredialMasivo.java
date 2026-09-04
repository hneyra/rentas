package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.CorridaDeEmision;
import kamayuk.rentas.rentas.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionPredialCalculada;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * La emision anual del predial: recorre el padron ya declarado del ejercicio y vuelve a determinar
 * a cada contribuyente con el conjunto sellado de hoy (#395, {@code POST
 * /rentas/predial/calculo-masivo}).
 *
 * <h2>Que padron recorre, y por que ese</h2>
 *
 * <p>El de los contribuyentes que <b>ya tienen una determinacion del ejercicio</b>, o sea aquellos
 * cuyos predios tienen autovaluo declarado. No es una limitacion de esta corrida sino la unica
 * lectura honesta que existe: el sistema no sabe valorizar un predio todavia (D-11, GOB-03), asi
 * que un contribuyente sin autovaluo declarado no se puede determinar ni de uno en uno ni en lote,
 * y la corrida lo dice en vez de emitir una cifra baja.
 *
 * <p><b>Y no arrastra el autovaluo de un ejercicio a otro.</b> Tomar el del ano pasado seria
 * aplicar en silencio un {@code % actualizacion} de cero, que es justo el factor que D-11 deja sin
 * fuente y que NEG-05 §0.1 advierte que <b>multiplica</b> importes: omitirlo no es neutro. Los
 * autovaluos que la corrida usa son los del <b>mismo</b> ejercicio.
 *
 * <h2>Lo que si cambia al recalcular</h2>
 *
 * <p>El conjunto sellado —de ahi salen la UIT, los tramos, el minimo, el derecho de emision y el
 * cronograma— y el <b>porcentaje de propiedad</b>, que se vuelve a leer de {@code titularidad} a la
 * fecha de la corrida: una transferencia posterior a la primera determinacion cambia quien paga que
 * parte, y congelarlo dejaria cobrando al que ya vendio.
 *
 * <h2>Una transaccion por contribuyente</h2>
 *
 * <p>Esta clase <b>no</b> abre transaccion. Cada determinacion abre la suya al entrar en {@link
 * RegistrarDeterminacionPredial}, que es lo que hace que el contribuyente que falla no se lleve por
 * delante al siguiente. Envolver el bucle —con {@code @Transactional} o con un solo {@code
 * TransactionTemplate}— es el defecto que #328 y #247 §2 documentan: la fila rechazada marca la
 * transaccion como <i>rollback-only</i> y la corrida entera revienta al confirmarla, informe
 * incluido. La lectura del padron, que si necesita transaccion para que RLS funcione, vive en
 * {@link PadronPredialDelEjercicio}.
 */
@Service
public class DeterminarPredialMasivo {

    /** Todo el padron declarado del ejercicio. */
    public static final String ALCANCE_TODOS = "TODOS";

    /** Solo los contribuyentes con al menos un predio en el sector indicado. */
    public static final String ALCANCE_SECTOR = "SECTOR";

    /**
     * Solo los contribuyentes cuyo codigo cae en un tramo (#577).
     *
     * <p>Es la forma de partir una emision anual en corridas que quepan en una tarde, y la de
     * repetir la de un tramo sin volver a recorrer el padron entero.
     */
    public static final String ALCANCE_RANGO_DE_CODIGO = "RANGO_DE_CODIGO";

    /**
     * Solo los que la <b>ultima corrida del ejercicio</b> dejo observados (#577).
     *
     * <p>Es el alcance que mas se usa en una campana —volver a correr sobre los que quedaron fuera—
     * y el que no habia manera de pedir: un observado es, por definicion, el que <b>no</b> tiene
     * determinacion de esta corrida, asi que la lista no se puede recomponer leyendo el padron.
     * Vive en {@code corrida_emision_observado} desde #523, y de ahi sale.
     *
     * <p>Sin corrida previa del ejercicio, la corrida no recorre a nadie y lo dice: no es lo mismo
     * «ninguno quedo observado» que «todavia no se ha corrido», y contestar cero a las dos seria
     * decir que la emision esta limpia cuando no ha empezado.
     */
    public static final String ALCANCE_OBSERVADOS = "OBSERVADOS";

    /**
     * Los cuatro que el manual dibuja, en un solo sitio.
     *
     * <p>El desplegable de la pantalla los rotula «TODO EL PADRON / POR SECTOR / POR RANGO DE
     * CODIGO / SOLO OBSERVADOS», y ninguno de los cuatro coincidia letra por letra con lo que el
     * backend admitia —los dos primeros se parecian, y parecerse no es serlo (#427)—. El
     * vocabulario que manda es este, y la pantalla ofrece estas palabras: al reves haria falta una
     * traduccion, que es una segunda copia de la regla.
     */
    public static final List<String> ALCANCES =
            List.of(ALCANCE_TODOS, ALCANCE_SECTOR, ALCANCE_RANGO_DE_CODIGO, ALCANCE_OBSERVADOS);

    private final PadronPredialDelEjercicio padron;
    private final DeterminarPredial individual;
    private final DirectorioDeContribuyentes directorio;
    private final LectorDeCaracteristicas caracteristicas;
    private final RegistrarCorridaDeEmision rastro;
    private final Clock reloj;

    public DeterminarPredialMasivo(
            PadronPredialDelEjercicio padron,
            DeterminarPredial individual,
            DirectorioDeContribuyentes directorio,
            LectorDeCaracteristicas caracteristicas,
            RegistrarCorridaDeEmision rastro,
            Clock reloj) {
        this.padron = padron;
        this.individual = individual;
        this.directorio = directorio;
        this.caracteristicas = caracteristicas;
        this.rastro = rastro;
        this.reloj = reloj;
    }

    /**
     * Corre la emision del ejercicio.
     *
     * @param peticion que se recalcula y con que alcance
     * @param observacion por que (regla 10); queda en cada determinacion que la corrida asiente
     */
    public Corrida ejecutar(Peticion peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "Hace falta la peticion");
        Objects.requireNonNull(observacion, "Toda modificacion exige la observacion (regla 10)");

        LocalDate hoy = LocalDate.now(reloj);
        List<PadronPredialDelEjercicio.DeterminacionConDetalle> declarados =
                padron.ultimasDe(peticion.ejercicio());

        Set<String> observadosPrevios = observadosDeLaUltimaCorrida(peticion);
        List<Observado> observados = new ArrayList<>();
        List<DeterminacionPredialCalculada> determinadas = new ArrayList<>();
        Dinero emitido = Dinero.CERO;
        String conjunto = "";

        Map<Long, ResumenDeContribuyente> nombres =
                directorio.porIds(
                        declarados.stream()
                                .map(fila -> fila.cabecera().contribuyenteId())
                                .collect(java.util.stream.Collectors.toSet()));

        for (PadronPredialDelEjercicio.DeterminacionConDetalle fila : declarados) {
            long contribuyenteId = fila.cabecera().contribuyenteId();
            ResumenDeContribuyente quien = nombres.get(contribuyenteId);
            String codigo = quien == null ? String.valueOf(contribuyenteId) : quien.codigo();
            String nombre = quien == null ? "" : quien.nombre();

            if (!peticion.recalculaYaEmitidos()
                    && fila.cabecera().estado() == EstadoDeDeterminacion.EMITIDA) {
                observados.add(
                        new Observado(
                                codigo,
                                nombre,
                                "Su determinacion del ejercicio ya esta EMITIDA. Recalcularla"
                                        + " crearia otra (ADR-0007) y dejaria dos valores en"
                                        + " circulacion por el mismo tributo; marcar «recalcula ya"
                                        + " emitidos» es decir que eso es lo que se quiere"));
                continue;
            }
            if (quien == null) {
                observados.add(
                        new Observado(
                                codigo,
                                nombre,
                                "Tiene determinacion del ejercicio y no esta en el padron de"
                                        + " contribuyentes: no se puede saber a nombre de quien"
                                        + " emitirla"));
                continue;
            }
            if (!enElAlcance(fila.detalle(), peticion, codigo, observadosPrevios, hoy)) {
                continue;
            }

            List<DeterminarPredial.PredioDeclarado> autovaluos = new ArrayList<>();
            for (DetalleDeterminacionPredio detalle : fila.detalle()) {
                autovaluos.add(
                        new DeterminarPredial.PredioDeclarado(
                                detalle.predioId(), detalle.autovaluo(), detalle.valuoExonerado()));
            }

            try {
                DeterminacionPredialCalculada calculada =
                        individual.determinar(
                                new DeterminarPredial.Peticion(
                                        peticion.ejercicio(),
                                        quien.codigo(),
                                        autovaluos,
                                        peticion.modalidad(),
                                        peticion.simulacion()),
                                observacion);
                determinadas.add(calculada);
                emitido = emitido.mas(calculada.totalAPagar());
                conjunto = calculada.nombreDelConjunto();
            } catch (DeterminarPredial.PredioSinAutovaluo
                    | DeterminarPredial.SinPrediosEnElPadron
                    | DeterminarPredial.PredioAjeno motivo) {
                // El padron cambio entre la primera determinacion y esta corrida: un predio nuevo
                // sin declarar, o uno que ya no es suyo. Se observa y la corrida sigue: es
                // exactamente lo que la pantalla llama «contribuyentes observados que quedan fuera
                // de la emision».
                observados.add(new Observado(codigo, nombre, String.valueOf(motivo.getMessage())));
            } catch (CuadroPredialParametrizado.ParametroDelPredialAusente falta) {
                // Esta le pasa a TODOS por igual —es del conjunto, no del contribuyente—, asi que
                // no se observa uno por uno: se corta la corrida. Observar 30 000 veces la misma
                // ordenanza que falta esconde el unico dato util del informe.
                throw falta;
            } catch (ParametrosSellados.ParametroAusente falta) {
                throw falta;
            }
        }

        Corrida corrida =
                new Corrida(
                        peticion.ejercicio(),
                        peticion.alcance(),
                        peticion.simulacion(),
                        conjunto,
                        determinadas.size(),
                        emitido,
                        List.copyOf(observados),
                        hoy);

        /* **Y deja rastro** (#523). Va al final, con el bucle ya terminado y sus
        determinaciones confirmadas cada una en su transaccion: si escribir el
        resumen falla, lo que se pierde es el resumen, no la emision. Antes de
        esto la corrida moria con la respuesta, y con ella la lista de
        observados —que es lo unico que NO se puede recomponer leyendo el
        padron: un observado es, por definicion, el que no tiene
        determinacion—. */
        CorridaDeEmision guardada =
                rastro.registrar(
                        new CorridaDeEmision(
                                null,
                                corrida.ejercicio(),
                                corrida.alcance(),
                                peticion.sector(),
                                peticion.codigoDesde(),
                                peticion.codigoHasta(),
                                peticion.modalidad(),
                                corrida.simulacion(),
                                corrida.nombreDelConjunto(),
                                corrida.leidos(),
                                corrida.determinados(),
                                corrida.montoEmitido(),
                                corrida.fechaCalculo(),
                                corrida.observados().stream()
                                        .map(
                                                observado ->
                                                        new CorridaDeEmision.Observado(
                                                                observado.codContribuyente(),
                                                                observado.nombre(),
                                                                observado.motivo()))
                                        .toList()),
                        observacion);

        return corrida.conRastro(guardada.id());
    }

    /**
     * Si el contribuyente entra en el alcance: con {@link #ALCANCE_SECTOR}, basta que uno de sus
     * predios este en el sector pedido.
     *
     * <p>Se determina igual sobre <b>todos</b> sus predios, tambien los de otros sectores: la base
     * es del contribuyente (NEG-05 §1) y recortarla al sector produciria el mismo error a la baja
     * que calcular predio por predio. El sector elige a quien se emite, no que se le cobra.
     */
    private boolean enElAlcance(
            List<DetalleDeterminacionPredio> detalle,
            Peticion peticion,
            String codigoContribuyente,
            Set<String> observadosDeLaCorridaAnterior,
            LocalDate hoy) {

        return switch (peticion.alcance()) {
            case ALCANCE_SECTOR -> tieneUnPredioEnElSector(detalle, peticion.sector(), hoy);
            case ALCANCE_RANGO_DE_CODIGO -> enElTramo(codigoContribuyente, peticion);
            case ALCANCE_OBSERVADOS -> observadosDeLaCorridaAnterior.contains(codigoContribuyente);
            default -> true;
        };
    }

    private boolean tieneUnPredioEnElSector(
            List<DetalleDeterminacionPredio> detalle, @Nullable String sector, LocalDate hoy) {
        if (sector == null) {
            return true;
        }
        for (DetalleDeterminacionPredio predio : detalle) {
            String suyo =
                    caracteristicas
                            .de(predio.predioId(), hoy)
                            .map(CaracteristicasDelPredio::sectorCodigo)
                            .orElse(null);
            if (sector.equalsIgnoreCase(suyo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Si el codigo del contribuyente cae en el tramo, extremos incluidos.
     *
     * <p>Se compara como <b>texto</b> y no como numero: el codigo del padron es una cadena
     * —«00000025673», «C-000007»— y ni siquiera es siempre numerica. Comparar por texto es lo mismo
     * que hace el orden con que la pantalla lista el padron, asi que «del C-000100 al C-000200» es
     * exactamente el tramo que quien pide la corrida esta viendo.
     */
    private static boolean enElTramo(String codigo, Peticion peticion) {
        String desde = peticion.codigoDesde();
        String hasta = peticion.codigoHasta();
        return desde != null
                && hasta != null
                && codigo.compareTo(desde) >= 0
                && codigo.compareTo(hasta) <= 0;
    }

    /**
     * Los codigos que la ultima corrida del ejercicio dejo observados (#577).
     *
     * <p>Se lee <b>una vez</b>, antes del bucle: preguntarlo por contribuyente serian treinta mil
     * consultas. Y solo con {@link #ALCANCE_OBSERVADOS}: las otras tres no lo miran, y leerlo igual
     * costaria una consulta por corrida a cambio de nada.
     *
     * <p>Sin corrida previa el conjunto sale vacio, y entonces la corrida no recorre a nadie. Eso
     * es lo correcto y no un caso degenerado: «ninguno quedo observado» y «todavia no se ha
     * corrido» son dos cosas distintas, y la unica que puede emitir es la primera.
     */
    private Set<String> observadosDeLaUltimaCorrida(Peticion peticion) {
        if (!ALCANCE_OBSERVADOS.equals(peticion.alcance())) {
            return Set.of();
        }
        return rastro.ultimaDe(peticion.ejercicio())
                .map(
                        corrida ->
                                corrida.observados().stream()
                                        .map(CorridaDeEmision.Observado::codContribuyente)
                                        .collect(java.util.stream.Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * Lo que se pide correr.
     *
     * @param ejercicio el ejercicio que se recalcula
     * @param alcance {@link #ALCANCE_TODOS} o {@link #ALCANCE_SECTOR}
     * @param sector obligatorio con {@link #ALCANCE_SECTOR}
     * @param modalidad el cronograma que se aplica a las cuotas
     * @param recalculaYaEmitidos si tambien entran los que ya tienen su determinacion emitida
     * @param simulacion si la corrida no guarda ninguna determinacion
     */
    public record Peticion(
            Ejercicio ejercicio,
            String alcance,
            @Nullable String sector,
            @Nullable String codigoDesde,
            @Nullable String codigoHasta,
            String modalidad,
            boolean recalculaYaEmitidos,
            boolean simulacion) {

        public Peticion {
            Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
            alcance =
                    alcance == null || alcance.isBlank()
                            ? ALCANCE_TODOS
                            : alcance.strip().toUpperCase(Locale.ROOT);
            if (!ALCANCES.contains(alcance)) {
                throw new IllegalArgumentException(
                        "Alcance desconocido: '"
                                + alcance
                                + "'. Se admite "
                                + String.join(", ", ALCANCES));
            }
            sector = sector == null || sector.isBlank() ? null : sector.strip();
            codigoDesde = codigoDesde == null || codigoDesde.isBlank() ? null : codigoDesde.strip();
            codigoHasta = codigoHasta == null || codigoHasta.isBlank() ? null : codigoHasta.strip();
            if (ALCANCE_SECTOR.equals(alcance) && sector == null) {
                throw new IllegalArgumentException(
                        "El alcance por sector necesita decir que sector: sin el, «solo el sector»"
                                + " y «todo el padron» serian la misma corrida");
            }
            if (ALCANCE_RANGO_DE_CODIGO.equals(alcance)
                    && (codigoDesde == null || codigoHasta == null)) {
                throw new IllegalArgumentException(
                        "El alcance por rango de codigo necesita sus dos extremos, «codigoDesde» y"
                                + " «codigoHasta»: con uno solo no se sabe donde acaba el tramo");
            }
            if (ALCANCE_RANGO_DE_CODIGO.equals(alcance)
                    && codigoDesde != null
                    && codigoHasta != null
                    && codigoDesde.compareTo(codigoHasta) > 0) {
                throw new IllegalArgumentException(
                        "El rango de codigo va del primero al ultimo: '"
                                + codigoDesde
                                + "' no puede ser mayor que '"
                                + codigoHasta
                                + "'");
            }
            modalidad =
                    modalidad == null || modalidad.isBlank()
                            ? DeterminarPredial.MODALIDAD_TRIMESTRAL
                            : modalidad.strip().toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Lo que la corrida hizo.
     *
     * @param ejercicio el ejercicio recalculado
     * @param alcance con que alcance
     * @param simulacion si no guardo nada
     * @param nombreDelConjunto el conjunto sellado con que se calculo; vacio si no se determino
     *     nada
     * @param determinados cuantos contribuyentes se determinaron
     * @param montoEmitido la suma de lo determinado, impuesto mas derecho de emision
     * @param observados los que quedaron fuera, cada uno con su motivo
     * @param fechaCalculo el dia al que corresponde la corrida (regla 9)
     */
    public record Corrida(
            Ejercicio ejercicio,
            String alcance,
            boolean simulacion,
            String nombreDelConjunto,
            int determinados,
            Dinero montoEmitido,
            List<Observado> observados,
            LocalDate fechaCalculo,
            @Nullable Long id) {

        /**
         * La corrida recien compuesta, todavia sin rastro en la base.
         *
         * <p>Existe para que anadir el identificador (#523) no obligara a tocar las pruebas que ya
         * componian una corrida: el {@code id} es lo ultimo que se sabe de ella, y hasta que se
         * escribe no lo tiene.
         */
        public Corrida(
                Ejercicio ejercicio,
                String alcance,
                boolean simulacion,
                String nombreDelConjunto,
                int determinados,
                Dinero montoEmitido,
                List<Observado> observados,
                LocalDate fechaCalculo) {
            this(
                    ejercicio,
                    alcance,
                    simulacion,
                    nombreDelConjunto,
                    determinados,
                    montoEmitido,
                    observados,
                    fechaCalculo,
                    null);
        }

        public Corrida {
            Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
            Objects.requireNonNull(alcance, "La corrida necesita su alcance");
            Objects.requireNonNull(nombreDelConjunto, "La corrida necesita su conjunto");
            Objects.requireNonNull(montoEmitido, "La corrida necesita lo que emitio");
            observados =
                    List.copyOf(
                            Objects.requireNonNull(observados, "La lista es vacia," + " no nula"));
            Objects.requireNonNull(
                    fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9)");
        }

        /** Cuantos contribuyentes miro la corrida en total. */
        public int leidos() {
            return determinados + observados.size();
        }

        /**
         * La misma corrida con el identificador que le dio la base (#523).
         *
         * <p>Viaja en la respuesta porque es con lo que la pantalla pide los observados despues:
         * sin el, «Ver observados» no tendria a que corrida referirse y habria que volver a correr
         * el proceso para volver a verlos.
         */
        public Corrida conRastro(@Nullable Long id) {
            return new Corrida(
                    ejercicio,
                    alcance,
                    simulacion,
                    nombreDelConjunto,
                    determinados,
                    montoEmitido,
                    observados,
                    fechaCalculo,
                    id);
        }
    }

    /**
     * Un contribuyente que queda fuera de la emision, y por que.
     *
     * <p>El motivo va en la respuesta y no en un registro que nadie mira: la pantalla lo llama «Ver
     * observados» y es lo unico que convierte una corrida que emitio menos de lo esperado en una
     * lista de cosas que arreglar.
     */
    public record Observado(String codContribuyente, String nombre, String motivo) {

        public Observado {
            Objects.requireNonNull(codContribuyente, "El observado necesita su codigo");
            Objects.requireNonNull(nombre, "El observado necesita su nombre, aunque sea vacio");
            Objects.requireNonNull(motivo, "Un observado sin motivo no se puede arreglar");
        }
    }
}
