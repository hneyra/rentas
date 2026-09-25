package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * {@code deudaActualizadaA(fecha)}: la funcion sobre la que se apoya toda la cobranza (RF-042,
 * ADR-0012 de {@code ../srtm}).
 *
 * <p><b>Funcion pura</b> (regla 6): todo lo que necesita entra como argumento. Sin base de datos,
 * sin reloj interno y sin configuracion global —la fecha de corte, la {@link PoliticaDeMora} y la
 * {@link PoliticaDeRedondeo} las trae quien llama—. Los mismos asientos y los mismos parametros dan
 * el mismo centimo hoy y dentro de diez anios.
 *
 * <p><b>Recorre el libro, no lo modifica.</b> Este contexto no tiene {@code UPDATE} ni {@code
 * DELETE} sobre {@code cuenta_corriente_asiento} (V7): el insoluto, el reajuste, el interes y el
 * gasto ya asentados salen de netear cargos contra abonos por concepto, tal como estan en el libro.
 * Lo unico que esta funcion agrega es el interes y el reajuste <b>todavia no asentados</b> —desde
 * el ultimo movimiento conocido hasta la fecha de corte—, porque «el interes se calcula, no se
 * asienta» (ADR-0012): asentarlo dia a dia produciria miles de millones de filas sin aportar
 * informacion, ya que es una funcion determinista del insoluto, la fecha y la {@link
 * PoliticaDeMora} vigente.
 */
public final class CalculoDeDeuda {

    private final PoliticaDeMora mora;

    public CalculoDeDeuda(PoliticaDeMora mora) {
        this.mora = Objects.requireNonNull(mora, "El calculo necesita su politica de mora");
    }

    /**
     * La deuda de una obligacion a una fecha de corte.
     *
     * @param asientos los de <b>una</b> obligacion —ya la resolvio quien llama, con {@link
     *     CriterioDeDeuda}—; un asiento de otra obligacion mezclaria cargos y abonos que no se
     *     corresponden
     * @param fecha la fecha de corte (regla 9, RNF-075): ningun asiento posterior al corte entra, y
     *     la deuda del futuro no existe todavia
     * @param redondeo la politica con la que {@link PoliticaDeMora} redondea lo que acumula (D-03)
     */
    public DeudaActualizada deudaActualizadaA(
            List<Asiento> asientos, LocalDate fecha, PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(fecha, "La fecha de corte entra como argumento (regla 6, RNF-075)");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (D-03)");

        List<Asiento> hastaElCorte =
                asientos.stream().filter(a -> !a.fechaValor().isAfter(fecha)).toList();

        Dinero insoluto = netear(hastaElCorte, Concepto.INSOLUTO);
        Dinero gasto = netear(hastaElCorte, Concepto.GASTO);
        Dinero reajuste = netear(hastaElCorte, Concepto.REAJUSTE);
        Dinero interes = netear(hastaElCorte, Concepto.INTERES);

        if (insoluto.esPositivo()) {
            LocalDate desde = ultimoMovimiento(hastaElCorte).orElse(fecha);
            if (desde.isBefore(fecha)) {
                reajuste = reajuste.mas(mora.reajusteAcumulado(insoluto, desde, fecha, redondeo));
                interes = interes.mas(mora.interesAcumulado(insoluto, desde, fecha, redondeo));
            }
        }

        return new DeudaActualizada(fecha, insoluto, reajuste, interes, gasto);
    }

    /**
     * Lo que debe <b>cada periodo</b> de una obligacion a una fecha de corte, del primero al ultimo
     * (#551).
     *
     * <p>Es {@link #deudaActualizadaA} aplicada por separado a cada cuota, y existe para que haya
     * <b>una sola</b> definicion de esa cuenta. La necesitan dos sitios que tienen que coincidir al
     * centimo: la lectura de {@code consulta_deuda} desglosada por periodo —lo que la pantalla
     * ensena— y el reparto de la baja de una fila agregada (#598) —lo que el acto extingue—. Si
     * cada uno la escribiera por su lado, lo que se lee en pantalla y lo que se puede dar de baja
     * podrian divergir sin que ninguna cifra pareciera mal, que es el defecto que #397 documenta
     * con las dos copias del {@code CASE} del «Estado».
     *
     * <p>Desde #445 el reparto no la llama directamente sino a traves de {@link
     * #extinguiblePorPeriodoDesde}, que esta construida <b>sobre</b> ella y da la misma cifra
     * cuando el libro no tiene nada posterior a la fecha —la baja con la fecha de hoy—. Cuando si
     * lo tiene, lo que la pantalla ensenaba a esa fecha ya no es lo que queda por extinguir, y la
     * diferencia es justo lo que un cobro posterior ya extinguio.
     *
     * <p><b>El orden es por periodo y no el que traiga la lista</b>: el reparto recorre las cuotas
     * de la primera a la ultima y ese orden es parte de lo que hace, asi que no puede depender de
     * como ordene su {@code ORDER BY} el repositorio de turno.
     *
     * <p>{@code periodo} nulo es el 0 —la obligacion anual—, igual que en {@link
     * ClaveDeSaldo#de(Asiento)}: es la unica traduccion, y aqui se respeta.
     *
     * @param asientos los de <b>una</b> obligacion, con todos sus periodos dentro
     * @param fecha la fecha de corte (regla 9, RNF-075)
     * @param redondeo la politica con la que {@link PoliticaDeMora} redondea lo que acumula (D-03)
     * @return una entrada por periodo con al menos un asiento; un periodo sin ninguno no aparece,
     *     porque «no hay obligacion» y «se debe 0,00» no son lo mismo y quien pregunta los
     *     distingue
     */
    public Map<Integer, DeudaActualizada> deudaPorPeriodoA(
            List<Asiento> asientos, LocalDate fecha, PoliticaDeRedondeo redondeo) {
        return porPeriodo(asientos, delPeriodo -> deudaActualizadaA(delPeriodo, fecha, redondeo));
    }

    /**
     * Lo que todavia se puede <b>extinguir</b> de una obligacion con un acto de fecha valor {@code
     * fecha}: una baja de ventanilla o la extincion por resolucion de gerencia (#445).
     *
     * <h2>No es {@link #deudaActualizadaA}, y confundirlas fue el defecto</h2>
     *
     * <p>{@code deudaActualizadaA(fecha)} contesta «¿cuanto se debia ese dia?», y para contestarlo
     * descarta todo asiento posterior al corte. Un acto que extingue deuda con una fecha valor en
     * el pasado pregunta otra cosa: «¿cuanto de lo que se debia ese dia no ha extinguido todavia
     * nadie?». Si el libro ya tiene un abono con fecha valor <b>posterior</b> —un cobro que entro
     * entre la fecha que la baja declara y el dia en que se registra—, ese abono ya extinguio
     * deuda, y medirla antes de el la cuenta dos veces: la baja pasa, el cobro sigue ahi, y la
     * cuota queda en negativo, que es el estado que {@code BajaMayorQueLaDeuda} declara imposible.
     * La fecha valor de una baja llega del cuerpo sin cota, y la de una resolucion de gerencia es
     * retroactiva a proposito, asi que el caso no es raro: es el de cualquier baja fechada antes de
     * un pago.
     *
     * <h2>La cuenta: el minimo, parte por parte</h2>
     *
     * <p>Un acto que abona {@code b} con fecha {@code f} resta {@code b} de la deuda en {@code f} y
     * en <b>cada</b> fecha posterior. Que ninguna quede en negativo exige que {@code b} no pase del
     * minimo de la deuda evaluada en {@code f} y en cada fecha valor posterior que ya tenga el
     * libro: entre dos fechas del libro lo neteado no cambia, asi que esos puntos bastan. Sin mora,
     * la deuda solo baja en un abono, y ese minimo es exactamente lo que ningun movimiento
     * posterior ha consumido.
     *
     * <p>Se toma <b>parte por parte</b> —insoluto, reajuste, interes, gasto—, por el mismo motivo
     * por el que la baja se compara parte por parte: un cobro de insoluto no extinguio ni un
     * centimo del gasto.
     *
     * <p>Un <b>cargo</b> posterior no sube lo extinguible: el minimo se queda con lo de antes de
     * el, porque una baja del 1 de marzo no puede extinguir una deuda que nace el 1 de abril. Es lo
     * que la baja ya hacia antes de #445.
     *
     * <p>Sin nada posterior a {@code fecha} —la baja con la fecha de hoy, que es el caso de todos
     * los dias— el minimo es de un solo punto y la cifra es <b>la misma</b> que {@link
     * #deudaActualizadaA}: lo que la pantalla ensena y lo que el acto puede extinguir siguen
     * coincidiendo al centimo (#551).
     *
     * <p><b>Lo que esta funcion no resuelve</b> es el reajuste y el interes todavia no asentados:
     * {@code deudaActualizadaA} los proyecta hasta cada punto, y aqui se toman como la baja los
     * tomaba antes. Que el acto los cargue antes de abonarlos lo resuelve {@link
     * CristalizacionDelDevengo} (#365), que esta construida sobre esta funcion: el cargo que
     * cristaliza es lo extinguible menos lo asentado, asi que un abono que no pase de lo
     * extinguible no deja ninguna parte en negativo.
     *
     * <p>Es una funcion pura (regla 6), como las demas de esta clase: los tres sitios que preguntan
     * cuanto se puede extinguir —la baja, su reparto y la extincion— la llaman a ella, y ninguno lo
     * cuenta por su lado.
     *
     * @param asientos los de <b>una</b> obligacion —o de una cuota—, con los posteriores a {@code
     *     fecha} dentro: son los que dicen que parte ya se extinguio
     * @param fecha la fecha valor del acto que extingue
     * @param redondeo la politica con la que {@link PoliticaDeMora} redondea lo que acumula (D-03)
     * @return lo extinguible, con la fecha del acto (regla 9)
     */
    public DeudaActualizada extinguibleDesde(
            List<Asiento> asientos, LocalDate fecha, PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6, RNF-075)");

        DeudaActualizada minimo = deudaActualizadaA(asientos, fecha, redondeo);
        for (LocalDate posterior : fechasPosterioresA(asientos, fecha)) {
            DeudaActualizada entonces = deudaActualizadaA(asientos, posterior, redondeo);
            minimo =
                    new DeudaActualizada(
                            fecha,
                            loMenor(minimo.insoluto(), entonces.insoluto()),
                            loMenor(minimo.reajuste(), entonces.reajuste()),
                            loMenor(minimo.interes(), entonces.interes()),
                            loMenor(minimo.gasto(), entonces.gasto()));
        }
        return minimo;
    }

    /**
     * {@link #extinguibleDesde} por separado para cada cuota, del primero al ultimo periodo (#445).
     *
     * <p>Es lo que el reparto de la baja de una fila agregada (#598) necesita: recorre las cuotas
     * en orden y le asigna a cada una lo menor entre lo que queda por repartir y lo que <b>esa</b>
     * cuota tiene por extinguir. Con {@link #deudaPorPeriodoA} le asignaba lo que la cuota debia a
     * la fecha valor, y una cuota cobrada despues de esa fecha se llevaba la baja entera mientras
     * la siguiente, que si se debia, se quedaba viva.
     *
     * @param asientos los de <b>una</b> obligacion, con todos sus periodos dentro
     * @param fecha la fecha valor del acto que extingue
     * @param redondeo la politica con la que {@link PoliticaDeMora} redondea lo que acumula (D-03)
     * @return una entrada por periodo con al menos un asiento, igual que {@link #deudaPorPeriodoA}
     */
    public Map<Integer, DeudaActualizada> extinguiblePorPeriodoDesde(
            List<Asiento> asientos, LocalDate fecha, PoliticaDeRedondeo redondeo) {
        return porPeriodo(asientos, delPeriodo -> extinguibleDesde(delPeriodo, fecha, redondeo));
    }

    /**
     * Agrupa por periodo —{@code null} es el 0, como en {@link ClaveDeSaldo#de(Asiento)}— y aplica
     * la cuenta a cada grupo, del primero al ultimo. Una sola agrupacion para las dos lecturas por
     * periodo, para que no puedan ordenar ni traducir el periodo de dos maneras.
     */
    private static Map<Integer, DeudaActualizada> porPeriodo(
            List<Asiento> asientos, Function<List<Asiento>, DeudaActualizada> cuenta) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");

        Map<Integer, List<Asiento>> porPeriodo = new TreeMap<>();
        for (Asiento asiento : asientos) {
            porPeriodo
                    .computeIfAbsent(
                            asiento.periodo() == null ? 0 : asiento.periodo(),
                            cual -> new ArrayList<>())
                    .add(asiento);
        }

        Map<Integer, DeudaActualizada> deudas = new LinkedHashMap<>();
        porPeriodo.forEach((periodo, delPeriodo) -> deudas.put(periodo, cuenta.apply(delPeriodo)));
        return deudas;
    }

    /** Las fechas valor del libro posteriores a {@code fecha}, sin repetir y en orden. */
    private static List<LocalDate> fechasPosterioresA(List<Asiento> asientos, LocalDate fecha) {
        return asientos.stream()
                .map(Asiento::fechaValor)
                .filter(cual -> cual.isAfter(fecha))
                .distinct()
                .sorted()
                .toList();
    }

    private static Dinero loMenor(Dinero uno, Dinero otro) {
        return uno.esMayorQue(otro) ? otro : uno;
    }

    /**
     * Lo que el libro <b>ya tiene asentado</b> a esa fecha: las mismas cuatro partes, neteadas,
     * pero <b>sin</b> agregar el reajuste ni el interes que todavia no se asentaron.
     *
     * <p>Existe por la cobranza (#33). {@link #deudaActualizadaA} devuelve lo que hay que cobrar, y
     * ahi dentro va una parte que <b>no esta en el libro</b> —«el interes se calcula, no se
     * asienta»—. Cuando el dinero entra por ventanilla eso deja de ser una proyeccion y pasa a ser
     * un hecho: hay que asentar el cargo de lo devengado antes de abonar el pago, o el abono del
     * interes dejaria {@code netear(INTERES)} en negativo para siempre y la obligacion quedaria con
     * deuda negativa.
     *
     * <p>La diferencia entre las dos funciones es exactamente lo que hay que cristalizar, y desde
     * #365 la calcula {@link CristalizacionDelDevengo} para los seis caminos que ese issue recorre
     * —no todavia para todo escritor: ver su javadoc—. Que sean dos metodos de la misma clase pura,
     * sobre los mismos asientos, es lo que garantiza que se netee igual en los dos: calcular una en
     * el dominio y la otra en un {@code SUM} de SQL seria volver a tener dos definiciones de lo
     * mismo.
     *
     * @param asientos los de <b>una</b> obligacion
     * @param fecha la fecha de corte; ningun asiento posterior entra
     */
    public DeudaActualizada asentadoA(List<Asiento> asientos, LocalDate fecha) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(fecha, "La fecha de corte entra como argumento (regla 6, RNF-075)");

        List<Asiento> hastaElCorte =
                asientos.stream().filter(a -> !a.fechaValor().isAfter(fecha)).toList();

        return new DeudaActualizada(
                fecha,
                netear(hastaElCorte, Concepto.INSOLUTO),
                netear(hastaElCorte, Concepto.REAJUSTE),
                netear(hastaElCorte, Concepto.INTERES),
                netear(hastaElCorte, Concepto.GASTO));
    }

    /**
     * Cargos suman, abonos restan: el mismo signo que fija {@link TipoAsiento} en todo el libro.
     */
    private static Dinero netear(List<Asiento> asientos, Concepto concepto) {
        Dinero total = Dinero.CERO;
        for (Asiento asiento : asientos) {
            if (asiento.concepto() != concepto) {
                continue;
            }
            total =
                    asiento.tipo() == TipoAsiento.CARGO
                            ? total.mas(asiento.monto())
                            : total.menos(asiento.monto());
        }
        return total;
    }

    /**
     * El punto desde el que todavia no hay nada asentado: la fecha valor mas reciente que ya se
     * conoce. Desde ahi hasta el corte es el tramo que {@link PoliticaDeMora} tiene que acumular.
     */
    private static Optional<LocalDate> ultimoMovimiento(List<Asiento> asientos) {
        return asientos.stream().map(Asiento::fechaValor).max(Comparator.naturalOrder());
    }
}
