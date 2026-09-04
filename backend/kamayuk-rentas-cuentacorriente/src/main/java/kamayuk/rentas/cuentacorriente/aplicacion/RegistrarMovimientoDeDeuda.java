package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.RangoDeCuotas;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Altas (nota de abono) y bajas (nota de cargo) de deuda (RF-043, RF-044, #24).
 *
 * <p>Las dos producen <b>asientos</b>, nunca un {@code UPDATE} de deuda existente: la traduccion la
 * hace {@link MovimientoDeDeuda#enAsientos}, y el libro no admite otra cosa (V7). Cada asiento pasa
 * por {@link RegistrarAsiento}, que es lo que mantiene la auditoria, el {@code motivo} del asiento
 * y el saldo proyectado en la misma transaccion.
 *
 * <h2>Una baja no puede quitar mas de lo que hay</h2>
 *
 * <p>{@link #registrar} comprueba, parte por parte, que la baja no exceda la deuda vigente <b>a su
 * fecha valor</b>, usando {@link CalculoDeDeuda#deudaActualizadaA} —la funcion de #22—. Comparar
 * contra «la deuda» sin fecha no significaria nada (regla 9), y comparar solo el total dejaria
 * pasar una baja que extingue S/ 500 de interes inexistente compensandolos con insoluto que si
 * existe: el total cuadraria y el desglose quedaria mal.
 *
 * <p>Un alta no tiene ese limite: incorporar deuda que no estaba es exactamente para lo que existe.
 *
 * <h2>Lo que si tiene el alta: no se puede hacer dos veces (#588)</h2>
 *
 * <p>Y hasta #588 tampoco lo tenia. Sin ese limite y sin ninguna otra guarda, reenviar el mismo
 * intento tras un tiempo de espera agotado dejaba <b>dos cargos identicos</b> sobre la misma
 * obligacion, con {@code 201} las dos veces. No se ve en ninguna cifra —la deuda existe y el
 * importe es correcto— y solo aparece cuando alguien paga y el saldo no queda en cero.
 *
 * <p>Lo garantiza {@code asiento_alta_unica_uq} (V75) y no ningun {@code if} de aqui: la clave es
 * la obligacion mas el {@code documentoOrigen} que la sustenta mas el {@code concepto}, de modo que
 * un rango de cuotas sigue produciendo {@code n} asientos, un desglose sigue produciendo hasta
 * cuatro, y dos actos con distinto sustento siguen siendo dos actos. El choque llega como {@link
 * kamayuk.rentas.cuentacorriente.dominio.AsientoRepository.AltaYaAsentada}, que el borde contesta
 * como {@code 409}.
 *
 * <p>Se escribe todo o nada, como con cualquier otra parte de este acto: el {@code @Transactional}
 * de {@link #registrar} deshace las cuotas que si entraron, asi que un alta de «cuotas 1 a 4» cuya
 * tercera cuota choca no deja media alta asentada.
 *
 * <h2>El formato impreso se emite al registrar, no al pedirlo</h2>
 *
 * <p>La nota de abono o de cargo se emite en la misma transaccion, con {@code EmitirDocumento}
 * (#15). Emitirla despues, cuando alguien la pidiera, significaria dibujarla con los datos de
 * <b>ese</b> dia: la deuda ya seria otra y el papel no coincidiria con el movimiento que dice
 * sustentar. Emitiendola aqui queda guardada con los datos con que se dibujo y con su resumen
 * SHA-256, que es lo que permite reimprimirla identica meses despues —y lo que hace que la
 * reimpresion <b>falle</b> en vez de entregar un papel distinto si alguien cambia el renderizador—.
 */
@Service
public class RegistrarMovimientoDeDeuda {

    private final AsientoRepository asientos;
    private final RegistrarAsiento registrarAsiento;
    private final CalculoDeDeuda calculo;
    private final PoliticaDeRedondeo redondeo;
    private final EmitirDocumento documentos;
    private final TitularesDeLaUnidad titulares;

    public RegistrarMovimientoDeDeuda(
            AsientoRepository asientos,
            RegistrarAsiento registrarAsiento,
            CalculoDeDeuda calculo,
            PoliticaDeRedondeo redondeo,
            EmitirDocumento documentos,
            TitularesDeLaUnidad titulares) {
        this.asientos = asientos;
        this.registrarAsiento = registrarAsiento;
        this.calculo = calculo;
        this.redondeo = redondeo;
        this.documentos = documentos;
        this.titulares = titulares;
    }

    /**
     * Registra el movimiento sobre <b>una</b> obligacion: la que su propia clave identifica.
     *
     * <p>Atajo de {@link #registrar(MovimientoDeDeuda, RangoDeCuotas, String, Observacion)} para
     * quien no abarca ningun rango: los contextos que generan cargos por su cuenta —licencias,
     * anuncios, tesoreria, coactiva— y que ya traen su propia transaccion.
     *
     * <p><b>Lleva su propio {@code @Transactional} y no lo hereda del metodo al que delega</b>: una
     * llamada de un metodo de la clase a otro <b>no pasa por el proxy</b>, asi que la anotacion del
     * otro seria inerte y este camino correria sin transaccion —y sin transaccion no hay {@code SET
     * LOCAL}, de modo que la politica RLS no devuelve vacio: revienta (#486)—. Es el mismo defecto
     * de auto-invocacion que #400 encontro en el importador de fichas.
     */
    @Transactional
    public Registro registrar(
            MovimientoDeDeuda movimiento, String codigoContribuyente, Observacion observacion) {
        return registrar(
                movimiento,
                RangoDeCuotas.deUnaSola(movimiento.clave().periodo()),
                codigoContribuyente,
                observacion);
    }

    /**
     * Registra el acto sobre las cuotas que abarca y devuelve <b>todos</b> los asientos que
     * produjo.
     *
     * <p>{@code @Transactional} aqui y no solo en {@link RegistrarAsiento}: un movimiento con
     * desglose produce varios asientos, y o entran todos o no entra ninguno. Media baja asentada
     * —el insoluto si, el interes no— dejaria una deuda que no corresponde ni a antes ni a despues,
     * y sin nada que dijera que falto la otra mitad. Con un rango de cuotas la exigencia es la
     * misma un escalon mas arriba: media baja de «cuotas 1 a 4» —tres si y la cuarta no, porque no
     * cabia— dejaria un acto que ningun papel explica.
     *
     * <h2>Un acto, n obligaciones, un solo documento</h2>
     *
     * <p>El rango se expande a las {@code n} claves que de verdad se mueven ({@link
     * MovimientoDeDeuda#enCadaCuota}) y cada una se comprueba y se asienta por separado —son
     * obligaciones distintas y la deuda vigente de una no dice nada de la otra—. Lo que <b>no</b>
     * se multiplica es el papel: la nota de abono o de cargo es <b>una</b>, la del acto, y lleva
     * dentro las cuotas que cubre y sus asientos. Emitir una por cuota daria {@code n} numeros
     * correlativos para un solo sustento documental y ninguna respuesta podria decir cual devolver.
     *
     * @param movimiento el desglose, la fase, la fecha valor y el sustento del acto; su clave
     *     identifica el tributo, el ejercicio y la unidad, y el rango dice sobre que cuotas cae
     * @param cuotas las cuotas que el acto abarca; {@link RangoDeCuotas#ANUAL} para la obligacion
     *     que no se divide
     * @param codigoContribuyente el codigo que se imprime en el formato; el identificador ya viaja
     *     dentro del movimiento, y el codigo es lo que el papel tiene que mostrar
     * @param observacion por que se registra; sin ella no se guarda (regla 10, RNF-052). Es
     *     <b>una</b> para el acto y queda copiada en los {@code n} asientos: lo que se explica es
     *     por que se dio de alta la deuda, no por que se dio de alta cada cuota
     */
    @Transactional
    public Registro registrar(
            MovimientoDeDeuda movimiento,
            RangoDeCuotas cuotas,
            String codigoContribuyente,
            Observacion observacion) {
        return registrar(
                movimiento,
                cuotas,
                ComprobacionDeUnidad.NO_APLICA,
                codigoContribuyente,
                observacion);
    }

    /**
     * El mismo acto, diciendo si hay que comprobar que la unidad sea del contribuyente (#635).
     *
     * <p>Ver {@link ComprobacionDeUnidad}. Las dos sobrecargas de arriba conservan las firmas que
     * usan los contextos que generan sus propios cargos, y con ellas su comportamiento.
     */
    @Transactional
    public Registro registrar(
            MovimientoDeDeuda movimiento,
            RangoDeCuotas cuotas,
            ComprobacionDeUnidad comprobacion,
            String codigoContribuyente,
            Observacion observacion) {

        // Antes que nada, y antes en particular que la comprobacion de que la baja no
        // excede la deuda: en un ejercicio sin particion no hay ningun asiento, de modo
        // que aquella diria «a esa fecha solo se deben 0.00» —cierto, y por la razon
        // equivocada— sobre una deuda que ni siquiera se puede escribir (#597).
        registrarAsiento.exigirEjercicioAsentable(movimiento.clave().ejercicio());
        exigirQueLaUnidadSeaDelContribuyente(movimiento, comprobacion);

        List<MovimientoDeDeuda> porCuota = movimiento.enCadaCuota(cuotas);
        for (MovimientoDeDeuda deLaCuota : porCuota) {
            if (deLaCuota.sentido() == SentidoDelMovimiento.BAJA) {
                verificarQueNoExcedeLaDeuda(deLaCuota);
            }
        }
        return asentarYEmitir(
                movimiento,
                porCuota,
                cuotas.etiqueta(),
                codigoContribuyente,
                comprobacion,
                observacion);
    }

    /**
     * La baja de <b>una fila de la grilla</b>, repartida entre los periodos que la componen (#598).
     *
     * <h2>Por que hacia falta, y por que repartir es cosa del servidor</h2>
     *
     * <p>Las filas de {@code consulta_deuda} <b>agregan varios periodos</b>: {@code
     * periodoDesde}/{@code periodoHasta} son el minimo y el maximo del grupo, no una obligacion. Un
     * contribuyente aparece como «PREDIAL 2026 · periodos 0 - 9 · S/ 444,90» cuando la deuda esta
     * en las cuotas 1, 2 y 3 a 148,30 cada una y las demas deben 0,00.
     *
     * <p>Con lo que habia, esa fila <b>no se podia dar de baja</b>:
     *
     * <ul>
     *   <li>mandar {@code cuota = periodoDesde} con el importe agregado carga los 444,90 sobre la
     *       cuota 0, que suele deber 0,00, y el servidor contesta «solo se deben 0.00» — un mensaje
     *       que habla de importes cuando la causa es que la fila es un agregado;
     *   <li>y el rango de #538 no sirve: el desglose se <b>repite</b> en cada cuota, no se reparte,
     *       asi que «cuotas 0 a 9 por 444,90» intentaria extinguir 444,90 <b>diez veces</b>.
     * </ul>
     *
     * <p>Repartir en la pantalla tampoco se puede: {@code ObligacionConDeudaResource} publica el
     * total del grupo y <b>no el importe de cada periodo</b>, asi que quien atiende tendria que
     * adivinar el reparto de un acto que extingue deuda del municipio. Lo sabe el servidor, y solo
     * el, porque el reparto depende de cuanto queda vivo en cada cuota a la fecha valor.
     *
     * <p>Lo que se declara es el <b>total del acto</b>. Se recorren las cuotas de la primera a la
     * ultima y a cada una se le asigna, por cada parte del desglose, lo menor entre lo que queda
     * por repartir y lo que esa cuota debe. Las que no deben nada no producen asiento: un abono de
     * cero deja el papel con lineas vacias y no dice nada. Y si al terminar sobra algo, el acto
     * <b>no se hace</b>: es la misma guarda de {@link #verificarQueNoExcedeLaDeuda} un escalon mas
     * arriba, con el mismo mensaje.
     *
     * @param cuotas acota el reparto a un tramo de la fila; {@code null} es la fila entera, que es
     *     lo que la pantalla necesita — y lo unico que puede expresar cuando el grupo empieza en la
     *     obligacion anual, porque {@link RangoDeCuotas} rechaza a proposito un rango que empiece
     *     en 0
     */
    @Transactional
    public Registro registrarRepartido(
            MovimientoDeDeuda movimiento,
            @Nullable RangoDeCuotas cuotas,
            ComprobacionDeUnidad comprobacion,
            String codigoContribuyente,
            Observacion observacion) {

        if (movimiento.sentido() != SentidoDelMovimiento.BAJA) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Un alta no se reparte: incorporar deuda que no estaba no tiene tope contra el"
                            + " que repartir. El reparto es de la baja de una fila que agrega varios"
                            + " periodos");
        }
        registrarAsiento.exigirEjercicioAsentable(movimiento.clave().ejercicio());
        exigirQueLaUnidadSeaDelContribuyente(movimiento, comprobacion);
        List<MovimientoDeDeuda> partes = repartir(movimiento, cuotas);
        return asentarYEmitir(
                movimiento,
                partes,
                etiquetaDe(partes),
                codigoContribuyente,
                comprobacion,
                observacion);
    }

    // ------------------------------------------------------------------

    private Registro asentarYEmitir(
            MovimientoDeDeuda movimiento,
            List<MovimientoDeDeuda> porCuota,
            String etiquetaDeLasCuotas,
            String codigoContribuyente,
            ComprobacionDeUnidad comprobacion,
            Observacion observacion) {

        // La declaracion viaja hasta la fila del libro (#653). Que la comprobacion llegara solo
        // hasta `exigirQueLaUnidadSeaDelContribuyente` era el defecto: el acto se admitia y no
        // quedaba dicho en ninguna parte que se hubiera declarado, asi que su fila de auditoria
        // era indistinguible de la de un alta sobre la unidad propia.
        boolean deTitularAnterior =
                comprobacion == ComprobacionDeUnidad.DECLARADA_DE_TITULAR_ANTERIOR;

        List<Asiento> guardados = new ArrayList<>();
        for (MovimientoDeDeuda deLaCuota : porCuota) {
            for (Asiento asiento : deLaCuota.enAsientos(deTitularAnterior)) {
                guardados.add(registrarAsiento.asentar(asiento, observacion));
            }
        }
        List<Asiento> asentados = List.copyOf(guardados);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        FormatoDelMovimiento.tipoDe(movimiento.sentido()),
                        movimiento.clave().ejercicio(),
                        movimiento.documentoOrigen(),
                        FormatoDelMovimiento.de(
                                movimiento, etiquetaDeLasCuotas, asentados, codigoContribuyente),
                        FormatoDeDocumento.PDF,
                        observacion);

        return new Registro(asentados, emision.registro().numero());
    }

    /**
     * El reparto, cuota por cuota y parte por parte.
     *
     * <p>La deuda de <b>todos</b> los periodos se lee en una sola consulta y se agrupa en memoria:
     * una consulta por cuota serian doce por acto, y doce mil en una corrida.
     */
    private List<MovimientoDeDeuda> repartir(
            MovimientoDeDeuda movimiento, @Nullable RangoDeCuotas cuotas) {

        Dinero insoluto = movimiento.insoluto();
        Dinero reajuste = movimiento.reajuste();
        Dinero interes = movimiento.interes();
        Dinero gasto = movimiento.gasto();

        List<MovimientoDeDeuda> partes = new ArrayList<>();
        for (Map.Entry<Integer, DeudaActualizada> cuota : deudaPorCuota(movimiento).entrySet()) {
            int periodo = cuota.getKey();
            if (cuotas != null && (periodo < cuotas.desde() || periodo > cuotas.hasta())) {
                continue;
            }
            DeudaActualizada debe = cuota.getValue();
            Dinero deInsoluto = loMenor(insoluto, debe.insoluto());
            Dinero deReajuste = loMenor(reajuste, debe.reajuste());
            Dinero deInteres = loMenor(interes, debe.interes());
            Dinero deGasto = loMenor(gasto, debe.gasto());
            if (deInsoluto.esCero()
                    && deReajuste.esCero()
                    && deInteres.esCero()
                    && deGasto.esCero()) {
                continue;
            }
            insoluto = insoluto.menos(deInsoluto);
            reajuste = reajuste.menos(deReajuste);
            interes = interes.menos(deInteres);
            gasto = gasto.menos(deGasto);
            partes.add(
                    new MovimientoDeDeuda(
                            movimiento.sentido(),
                            new ClaveDeSaldo(
                                    movimiento.clave().contribuyenteId(),
                                    movimiento.clave().tributo(),
                                    movimiento.clave().ejercicio(),
                                    periodo,
                                    movimiento.clave().predioId(),
                                    movimiento.clave().vehiculoId()),
                            deInsoluto,
                            deReajuste,
                            deInteres,
                            deGasto,
                            movimiento.fase(),
                            movimiento.fechaValor(),
                            movimiento.documentoOrigen(),
                            movimiento.referenciaExterna(),
                            // La causal se copia en cada parte del reparto (#684): es del ACTO
                            // —una sola baja, con un solo sustento juridico— y no de la cuota.
                            // Perderla aqui no dejaria la fila sin causal: `MovimientoDeDeuda`
                            // no se deja construir sin ella cuando el sentido es BAJA, y este
                            // es el camino que la pantalla usa siempre (`repartir: true`).
                            movimiento.causal()));
        }

        sinRepartir("insoluto", insoluto, movimiento.insoluto());
        sinRepartir("reajuste", reajuste, movimiento.reajuste());
        sinRepartir("interes", interes, movimiento.interes());
        sinRepartir("gasto", gasto, movimiento.gasto());
        return List.copyOf(partes);
    }

    /**
     * Lo que debe cada cuota de la obligacion a la fecha valor, de la primera a la ultima.
     *
     * <p><b>La cuenta la hace {@link CalculoDeDeuda#deudaPorPeriodoA}</b>, que es la misma que usa
     * la lectura desglosada de {@code consulta_deuda} (#551). No es un detalle de reutilizacion:
     * quien atiende lee en pantalla el importe de la cuota y lo manda como baja, asi que las dos
     * cifras <b>tienen</b> que ser la misma al centimo — dos copias de la cuenta divergen y el acto
     * empieza a rechazar importes que la pantalla acaba de publicar, o peor, a admitir los que no
     * (#397).
     */
    private Map<Integer, DeudaActualizada> deudaPorCuota(MovimientoDeDeuda movimiento) {
        return calculo.deudaPorPeriodoA(
                asientos.deTodosLosPeriodosDe(ClaveDeObligacion.de(movimiento.clave())),
                movimiento.fechaValor(),
                redondeo);
    }

    /**
     * Que cuotas cubrio el acto, como se escribe en el papel.
     *
     * <p>Las que de verdad recibieron importe, no el rango que se pidio: la fila decia «periodos 0
     * - 9» y lo que se extinguio fueron las cuotas 1, 2 y 3. Un papel que dijera «0 a 9» prometeria
     * diez obligaciones movidas donde se movieron tres.
     */
    private static String etiquetaDe(List<MovimientoDeDeuda> partes) {
        if (partes.isEmpty()) {
            return RangoDeCuotas.ANUAL.etiqueta();
        }
        List<String> cuotas = new ArrayList<>();
        for (MovimientoDeDeuda parte : partes) {
            int periodo = parte.clave().periodo();
            cuotas.add(periodo == 0 ? RangoDeCuotas.ANUAL.etiqueta() : Integer.toString(periodo));
        }
        return String.join(", ", cuotas);
    }

    private static Dinero loMenor(Dinero uno, Dinero otro) {
        return uno.esMayorQue(otro) ? otro : uno;
    }

    /**
     * Lo que no cupo en ninguna cuota. Se dice con el mismo mensaje que la baja de una sola: lo que
     * se intento extinguir y lo que de verdad habia.
     */
    private static void sinRepartir(String parte, Dinero sobrante, Dinero declarado) {
        if (!sobrante.esCero()) {
            throw new BajaMayorQueLaDeuda(parte, declarado, declarado.menos(sobrante));
        }
    }

    /**
     * Lo que produjo el movimiento: sus asientos y el numero del documento con que se formalizo.
     *
     * <p>Se devuelve el <b>numero</b> y no los bytes: quien registra un alta desde una pantalla no
     * necesita el PDF en la respuesta, y devolverlo obligaria a acarrearlo por toda la capa web
     * para que casi siempre se descarte. Con el numero se pide cuando haga falta, y sale identico.
     */
    public record Registro(List<Asiento> asientos, String numeroDeDocumento) {}

    /**
     * Si hay que comprobar que la unidad de la obligacion sea del contribuyente (#635).
     *
     * <p>Son tres y no un booleano porque los tres casos existen de verdad y confundirlos rompe
     * algo distinto en cada uno.
     */
    public enum ComprobacionDeUnidad {

        /**
         * Se exige: la unidad tiene que ser del contribuyente a la fecha valor.
         *
         * <p>Es lo que el alta y la baja de ventanilla piden. Hasta #635 nadie lo comprobaba, y un
         * alta con el {@code vehiculoId} de otra persona quedaba asentada sobre una clave que nadie
         * va a mirar — invisible desde la ficha del vehiculo, sin sumarse a la deuda de quien paga,
         * y publicada como una fila mas en el estado de cuenta del obligado.
         */
        EXIGIDA,

        /**
         * La peticion declara que la deuda es de un titular anterior, y entonces se admite.
         *
         * <p>No es una puerta trasera: la deuda de un ejercicio anterior a una transferencia
         * <b>es</b> del titular de entonces, asi que un alta sobre la unidad de otro puede ser
         * exactamente lo que corresponde. Lo que separa ese caso del error es que alguien lo diga,
         * y <b>la declaracion queda escrita como dato</b> en cada asiento del movimiento —{@code
         * cuenta_corriente_asiento.unidad_de_titular_anterior}, V71— y, con el, en su fila de
         * auditoria (#653). Hasta entonces la marca solo servia para dejar pasar el acto y no
         * quedaba dicha en ninguna parte, asi que la fila era indistinguible de la de un alta sobre
         * la unidad propia.
         */
        DECLARADA_DE_TITULAR_ANTERIOR,

        /**
         * No se comprueba, y hay un motivo.
         *
         * <p>Lo usan los contextos que generan sus propios cargos —licencias, anuncios— y ahi la
         * unidad <b>no es</b> del obligado por definicion en varios casos: una papeleta se asienta
         * con el predio de la infraccion y quien la paga puede no ser su titular (#331). Exigirla
         * ahi rechazaria actos correctos.
         */
        NO_APLICA
    }

    /**
     * La unidad de la obligacion tiene que ser del contribuyente que la debe (#635, #680).
     *
     * <p>El puerto contesta <b>dos cosas separadas</b> —si la unidad esta en el padron y quienes la
     * tienen a esa fecha— y no una lista cuyo vacio significara las dos. Hasta #680 significaba las
     * dos, y con eso no se podia dar de alta deuda sobre un predio sin titularidad vigente: el 34,5
     * % del padron de Catacaos (#586), que es exactamente el predio que la fiscalizacion detecta,
     * sortea y visita.
     *
     * <p>La titularidad se resuelve a la <b>fecha valor</b> del movimiento y no con el reloj: la
     * deuda de 2024 la debe quien era titular en 2024, y comparar contra el titular de hoy
     * rechazaria el acto correcto y aceptaria el equivocado. Es el defecto de #24 y #366 en este
     * camino.
     */
    private void exigirQueLaUnidadSeaDelContribuyente(
            MovimientoDeDeuda movimiento, ComprobacionDeUnidad comprobacion) {

        if (comprobacion == ComprobacionDeUnidad.NO_APLICA) {
            return;
        }
        ClaveDeSaldo clave = movimiento.clave();
        // La baja se corrige aunque la unidad ya no exista (#660). Ver `comprobar`.
        boolean exigirQueLaUnidadExista = movimiento.sentido() == SentidoDelMovimiento.ALTA;
        if (clave.predioId() != null) {
            comprobar(
                    "predio",
                    clave.predioId(),
                    titulares.delPredio(clave.predioId(), movimiento.fechaValor()),
                    clave.contribuyenteId(),
                    comprobacion,
                    exigirQueLaUnidadExista);
        }
        if (clave.vehiculoId() != null) {
            comprobar(
                    "vehiculo",
                    clave.vehiculoId(),
                    titulares.delVehiculo(clave.vehiculoId(), movimiento.fechaValor()),
                    clave.contribuyenteId(),
                    comprobacion,
                    exigirQueLaUnidadExista);
        }
    }

    /**
     * La comprobacion de una unidad, con la asimetria que #660 encontro.
     *
     * <p><b>Un alta</b> escribe deuda nueva: si el identificador no apunta a nada, el cargo queda
     * sobre una clave que ninguna consulta va a mirar —invisible desde la ficha del predio, sin
     * sumarse a la deuda de quien paga— y por eso se rechaza.
     *
     * <p><b>Una baja</b> extingue deuda que <b>ya esta escrita</b>. Exigirle que la unidad siga en
     * el padron es pedirle a la correccion el requisito que el error no tuvo: los asientos con un
     * {@code predioId} colgado existen —nada lo impedia, porque V2 no declara clave foranea sobre
     * {@code predio} ni sobre {@code vehiculo}—, no se pueden borrar (regla 4, RNF-051) ni
     * reescribir desde el migrador (RLS con {@code FORCE}, DAT-01 §0), asi que rechazarla dejaba
     * esa deuda <b>viva y sin forma de extinguirla por el circuito normal</b>, que es peor que el
     * defecto original. Lo que la baja necesita comprobar es que la obligacion exista, y de eso ya
     * se ocupa {@link #verificarQueNoExcedeLaDeuda}: sobre una clave inventada no se debe nada y la
     * baja se rechaza por su propio motivo.
     *
     * <p>Lo que <b>no</b> cambia es la otra mitad: si la unidad existe y es de otro, la baja sigue
     * pidiendo la declaracion igual que el alta.
     *
     * <h2>Y la unidad que existe y no la reclama nadie entra, sin declararla (#680)</h2>
     *
     * <p>Es una tercera situacion, distinta de las dos anteriores, y la decision es que <b>pasa sin
     * declaracion</b>. Los motivos, por orden:
     *
     * <ol>
     *   <li><b>No hay nada que declarar.</b> La declaracion existe para pasar por encima de
     *       <i>evidencia contraria</i> —«esta unidad es de fulano»—. Sin ningun titular no hay
     *       evidencia contraria: el predicado «la unidad es de otro» es <b>falso</b>, no
     *       indeterminado. Pedir una declaracion seria pedir que se declare por encima de nada.
     *   <li><b>{@link ComprobacionDeUnidad#DECLARADA_DE_TITULAR_ANTERIOR} no se puede
     *       reutilizar</b>, y no por gusto: dice <i>quien</i> era el titular antes, y aqui no hay
     *       ninguno. Marcarla dejaria {@code cuenta_corriente_asiento.unidad_de_titular_anterior}
     *       (V71) afirmando algo falso en la fila del libro y en su bitacora, y una marca falsa es
     *       peor que ninguna — que es justamente lo que #653 arreglo por el otro lado.
     *   <li><b>Es un estado que el propio sistema declaro legitimo y espera cobrar.</b> {@code V73}
     *       relajo {@code programa_muestra.contribuyente_id} a nulo para que el predio sin titular
     *       <b>entre</b> en la muestra de fiscalizacion sin inventarle titular (#586), porque «un
     *       predio que nadie reclama es exactamente el que hay que fiscalizar». Son 4 977 de 14 422
     *       en Catacaos: una casilla que hubiera que marcar en el 34,5 % de los actos deja de ser
     *       una declaracion y pasa a ser ruido que nadie lee.
     * </ol>
     *
     * <p>Lo que <b>si</b> se conserva es la guarda que la rama del vacio de verdad sostenia: un
     * identificador que no apunta a ninguna fila sigue siendo 422, y ahora con su propio mensaje.
     */
    private static void comprobar(
            String unidad,
            long unidadId,
            TitularesDeLaUnidad.TitularidadDeLaUnidad deLaUnidad,
            long contribuyenteId,
            ComprobacionDeUnidad comprobacion,
            boolean exigirQueLaUnidadExista) {

        if (!deLaUnidad.estaEnElPadron()) {
            if (!exigirQueLaUnidadExista) {
                return;
            }
            throw new UnidadAjena(
                    "El "
                            + unidad
                            + " "
                            + unidadId
                            + " no esta en el padron de esta municipalidad. Un identificador que"
                            + " no apunta a nada deja el cargo sobre una clave que ninguna"
                            + " consulta va a mirar, asi que no se registra: hay que corregir el"
                            + " identificador, no declarar nada");
        }
        if (deLaUnidad.sinTitularVigente()) {
            // Existe y a esa fecha no figura a nombre de nadie: no hay evidencia contraria que
            // declarar. Ver el javadoc de arriba.
            return;
        }
        if (deLaUnidad.esDe(contribuyenteId)) {
            return;
        }
        if (comprobacion == ComprobacionDeUnidad.DECLARADA_DE_TITULAR_ANTERIOR) {
            return;
        }
        StringBuilder quienes = new StringBuilder();
        for (TitularesDeLaUnidad.TitularDeLaUnidad titular : deLaUnidad.titulares()) {
            if (quienes.length() > 0) {
                quienes.append(", ");
            }
            quienes.append(titular.codigo()).append(" ").append(titular.nombre()).append('\'');
        }
        throw new UnidadAjena(
                "El "
                        + unidad
                        + " "
                        + unidadId
                        + " es de '"
                        + quienes
                        + " a la fecha valor del movimiento, no del contribuyente que lo debe. Si"
                        + " es deuda de un titular anterior, hay que declararlo con"
                        + " «deudaDeTitularAnterior»");
    }

    /**
     * La unidad no es del contribuyente del movimiento, y nadie declaro que fuera de un titular
     * anterior (#635).
     */
    public static final class UnidadAjena extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        UnidadAjena(String mensaje) {
            super(mensaje);
        }
    }

    private void verificarQueNoExcedeLaDeuda(MovimientoDeDeuda movimiento) {
        ClaveDeSaldo clave = movimiento.clave();
        // Por la obligacion y no por CriterioDeDeuda: ese criterio busca por codigo de
        // contribuyente —es lo que teclea quien atiende— y aqui ya se tiene el
        // identificador. La fecha de corte es la fecha valor de la propia baja: se
        // compara contra lo que se debia el dia que la baja surte efecto, no hoy.
        DeudaActualizada vigente =
                calculo.deudaActualizadaA(
                        asientos.deLaObligacion(clave), movimiento.fechaValor(), redondeo);

        comprobar("insoluto", movimiento.insoluto(), vigente.insoluto());
        comprobar("reajuste", movimiento.reajuste(), vigente.reajuste());
        comprobar("interes", movimiento.interes(), vigente.interes());
        comprobar("gasto", movimiento.gasto(), vigente.gasto());
    }

    private static void comprobar(String parte, Dinero seDaDeBaja, Dinero vigente) {
        if (seDaDeBaja.esMayorQue(vigente)) {
            throw new BajaMayorQueLaDeuda(parte, seDaDeBaja, vigente);
        }
    }

    /**
     * Se intento dar de baja mas de lo que se debe. Dejarlo pasar produciria una deuda negativa que
     * nadie sabria explicar, y que la siguiente emision arrastraria como saldo a favor.
     */
    public static final class BajaMayorQueLaDeuda extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BajaMayorQueLaDeuda(String parte, Dinero seDaDeBaja, Dinero vigente) {
            super(
                    "La baja de "
                            + parte
                            + " es de "
                            + seDaDeBaja
                            + " y a esa fecha solo se deben "
                            + vigente
                            + ". Una baja no puede extinguir mas de lo que hay");
        }
    }
}
