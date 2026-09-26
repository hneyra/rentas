package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.BeneficiosDelContribuyente;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CronogramaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.OrigenDelAutovaluo;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import kamayuk.rentas.nucleo.dominio.predial.Tramo;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * El calculo individual del impuesto predial de un contribuyente, con todo lo que hace falta para
 * explicarlo (#395: {@code POST /rentas/predial/calculo-individual}).
 *
 * <p>Junta las tres piezas que ya existian por separado y no tenian quien las llamara: los predios
 * del contribuyente y su titularidad —de catastro—, el cuadro del articulo 13 —del conjunto
 * sellado, {@link CuadroPredialParametrizado}— y la regla de #30 —{@link
 * RegistrarDeterminacionPredial}—.
 *
 * <h2>La base es del contribuyente, no de cada predio</h2>
 *
 * <p>NEG-05 §1. Cada predio aporta su valuo afecto <b>ponderado por el % de propiedad</b>, y los
 * tramos progresivos se aplican una sola vez sobre la suma. Calcular predio por predio y sumar los
 * impuestos produce un error sistematico a la baja en todo el padron —los tramos bajos se aplican
 * tantas veces como predios—, y ese error no se ve en ninguna cifra.
 *
 * <h2>El % de propiedad no lo manda quien pide</h2>
 *
 * <p>Sale de {@code titularidad} por {@link PrediosDelContribuyente}. Es lo unico que impide que la
 * base se pueda inflar o desinflar desde el cuerpo de la peticion, y por eso {@link
 * PredioDeclarado} no tiene campo para el.
 *
 * <h2>Dos fechas, y no una (#328)</h2>
 *
 * <p>El padron se lee a la <b>fecha de referencia</b> del ejercicio, y la <b>fecha de calculo</b>
 * es la del reloj y es la que viaja con la cifra (regla 9). Hasta #328 las dos eran la misma
 * variable, y la titularidad salia del dia en que alguien pulsaba el boton. La de referencia tiene
 * a su vez dos lecturas, y las dos las dice {@link Ejercicio}:
 *
 * <ul>
 *   <li><b>Quien es titular y con que {@code %}</b>, a {@link Ejercicio#fechaDeLaTitularidad()}: el
 *       31 de diciembre del año anterior, o sea la situacion juridica al 1 de enero <b>antes</b> de
 *       cualquier transferencia de ese dia. TUO LTM art. 10, segundo parrafo: «cuando se efectue
 *       cualquier transferencia, el adquirente asume la condicion de contribuyente a partir del 1
 *       de enero del año siguiente de producido el hecho». El que vende en marzo —o el mismo 1 de
 *       enero— sigue debiendo 2026; el que compro el 31 de diciembre ya lo debe.
 *   <li><b>Las caracteristicas del predio</b>, a {@link Ejercicio#primerDia()}: NEG-05 §3 las pide
 *       «vigentes a la fecha de referencia», y para ellas no hay regla de transferencia.
 * </ul>
 *
 * <p>Leer la titularidad tambien al 1 de enero parecia lo mismo y no lo es: el padron cierra la
 * cuota anterior el dia antes de la transferencia, asi que con una venta fechada el 1 de enero a
 * ese dia ya consta el comprador, y se le cargaba un ejercicio que la ley le da al vendedor (#328,
 * ronda 1 de la revision).
 *
 * <h2>El autovaluo: de donde sale, y que manda cuando hay dos (#38, AC-2)</h2>
 *
 * <p><b>Manda la valuacion que {@code catastro} sello</b> cuando existe y trae cifra; si no, manda
 * la declarada. Y las dos quedan escritas: {@code determinacion_predio_detalle} guarda desde V14 de
 * cual de las dos salio cada predio, y —cuando salio de una valuacion— con que {@code conjuntoId} y
 * con que huella se calculo; desde V33, <b>ademas</b> la declarada que no mando (#362). Hasta #362
 * esa ultima frase era falsa: la declarada solo vivia en memoria.
 *
 * <p><b>Las tres opciones se enumeraron con su coste antes de elegir</b>, que es lo que AC-2 pide:
 *
 * <ol>
 *   <li><b>Manda siempre la declarada.</b> Es el estado anterior a #38, y su coste esta medido: la
 *       valuacion sellada llegaba, se guardaba, se contaba, y <b>ninguna consulta leia una
 *       cifra</b> — de modo que el candado de emision se negaba a emitir hasta que llegaran todas y
 *       despues la corrida determinaba con otros numeros. Una valuacion completa y una incompleta
 *       producian exactamente el mismo recibo.
 *   <li><b>Manda siempre la sellada, y sin la declarada no se determina.</b> Dejaria hoy sin emitir
 *       a casi todo el padron: {@code catastro} valoriza 4 de los 23 predios de la demostracion, y
 *       los otros 19 traen el motivo de RT-004.
 *   <li><b>Manda la sellada cuando la hay, y la declarada cuando no.</b> Es la elegida.
 * </ol>
 *
 * <p>El argumento de la tercera es ADR-0024 leido literalmente: «aqui llega un valor ya calculado y
 * sellado, y sobre el se aplican tramos, deducciones y alicuotas». La valuacion sale de la ficha
 * catastral y la firma un sistema con su conjunto y su huella; la declaracion la firma el
 * contribuyente y es la que la fiscalizacion contrasta. Cuando el sistema sabe valorizar, esa es la
 * cifra — y que la declarada quede guardada al lado es lo que permite ver la discrepancia en vez de
 * enterarse de ella en ventanilla.
 *
 * <p><b>Lo que esto NO hace es valorizar.</b> Este sistema sigue sin saber, y sigue siendo correcto
 * que no sepa: llegar al autovaluo exige el cuadro de valores unitarios y la tabla de depreciacion
 * (GOB-03, H-14 y H-15), los aranceles de la ordenanza (D-02b) y el {@code % actualizacion} (D-11).
 * Lo que cambia es que, cuando <b>otro</b> sistema ya lo hizo y lo sello, aqui se lee.
 *
 * <p>La consecuencia practica sigue en pie: un predio sin ninguno de los dos <b>no se
 * determina</b>, y se responde nombrandolo con lo que {@code catastro} dijo que le falta. Tomar el
 * autovaluo del ejercicio anterior seria aplicar en silencio un {@code % actualizacion} de cero,
 * que es exactamente lo que D-11 advierte que no es neutro.
 *
 * <h2>Simular no asienta</h2>
 *
 * <p>El contrato declara una sola operacion por pantalla, asi que la diferencia va en el cuerpo,
 * como ya hacia {@code VehicularController} (#32). Aqui es <b>obligatoria</b>: una peticion que no
 * diga si simula o determina se rechaza en vez de suponer. Suponer «determina» emitiria deuda al
 * pulsar un boton que dice «Simular»; suponer «simula» dejaria de emitirla al pulsar el que dice
 * «Calcular», y ninguna de las dos equivocaciones avisa.
 *
 * <h2>La modalidad tampoco se supone, y desde #234 se GUARDA</h2>
 *
 * <p>Hasta #234 la modalidad llegaba en el cuerpo y, si no venia, este caso de uso suponia {@code
 * TRIMESTRAL}. Con ella se resolvian los vencimientos y se repartia el monto, y <b>no se guardaba
 * en ninguna parte</b>: la unica tabla que la tenia era {@code corrida_predial}, que es de la
 * emision masiva. La consecuencia es que la regla 6 no se cumplia del todo para la individual —el
 * impuesto era reproducible y las cuotas no—, y que la lectura de #207 no podia dibujar el
 * cronograma.
 *
 * <p>Las dos mitades se cierran juntas, porque una sin la otra no sirve: {@link Peticion}
 * <b>exige</b> la modalidad —la misma decision que {@code simulacion}, por el mismo motivo— y
 * {@code V21} le da a {@code determinacion} su columna, que {@link RegistrarDeterminacionPredial}
 * escribe. Guardar la supuesta habria sido la otra salida defendible, y se descarto: escribiria en
 * la fila un cronograma que el contribuyente no eligio, indistinguible dentro de dos anios del que
 * si eligio.
 */
@Service
public class DeterminarPredial {

    /** El tributo con que {@code beneficio.tributo} nombra al predial. */
    private static final String TRIBUTO_PREDIAL = "PREDIAL";

    private final PadronPredialDelEjercicio yaDeclarados;
    private final PrediosDelContribuyente predios;
    private final LectorDeCaracteristicas caracteristicas;
    private final DirectorioDeContribuyentes directorio;
    private final CuadroPredialParametrizado cuadro;
    private final ValuacionRecibida valuaciones;
    private final BeneficiosDelContribuyente beneficios;
    private final RegistrarDeterminacionPredial registro;
    private final Clock reloj;

    public DeterminarPredial(
            PadronPredialDelEjercicio yaDeclarados,
            PrediosDelContribuyente predios,
            LectorDeCaracteristicas caracteristicas,
            DirectorioDeContribuyentes directorio,
            CuadroPredialParametrizado cuadro,
            ValuacionRecibida valuaciones,
            BeneficiosDelContribuyente beneficios,
            RegistrarDeterminacionPredial registro,
            Clock reloj) {
        this.yaDeclarados = yaDeclarados;
        this.predios = predios;
        this.caracteristicas = caracteristicas;
        this.directorio = directorio;
        this.cuadro = cuadro;
        this.valuaciones = valuaciones;
        this.beneficios = beneficios;
        this.registro = registro;
        this.reloj = reloj;
    }

    /**
     * Determina —o simula— el predial de un contribuyente.
     *
     * <p>No abre transaccion propia, y no la abre nadie por ella: <b>la escritura</b> abre la suya
     * en {@link RegistrarDeterminacionPredial#asentar}, y <b>cada lectura previa trae la propia</b>
     * —el directorio, el conjunto sellado, los beneficios, el padron ya declarado y la valuacion
     * sellada ({@code ValuacionRecibidaJdbc}, desde #358)—. Envolver esto en una del anfitrion es
     * la trampa que #54 y #72 documentan: una excepcion capturada dentro de la del anfitrion la
     * deja marcada como <i>rollback-only</i> y revienta al confirmarla.
     *
     * <p>Hasta #358 este parrafo decia que la transaccion «la abre {@code registrar}», y dejo de
     * ser cierto con #52: la precedencia de la valuacion sellada anadio una lectura de {@code
     * valuacion_predio} <b>antes</b> de {@code registrar}, que corria en autocommit y sin {@code
     * SET LOCAL}, y la politica de RLS la hacia fallar con un 500. Un colaborador nuevo que lea la
     * base tiene que traer su transaccion: aqui no hay ninguna a la que unirse.
     *
     * <h2>Se asienta al final (#359)</h2>
     *
     * <p>Primero se compone la determinacion entera —la cabecera calculada, el derecho de emision,
     * los vencimientos de la modalidad pedida y las cuotas con su redondeo— y solo despues se
     * asienta. Hasta #359 el orden era el contrario: {@code registrar} calculaba y confirmaba la
     * fila con su {@code ALTA}, y lo que faltaba del conjunto se descubria despues, con un 422 que
     * el usuario leia como «no se determino» y una fila que decia lo contrario. Cada reintento
     * escribia otra, y esa pasaba a ser «la ultima» del ejercicio para la corrida masiva y para la
     * consulta.
     *
     * @param peticion que se determina y con que autovaluos
     * @param observacion por que (regla 10); se exige tambien al simular
     */
    public DeterminacionPredialCalculada determinar(Peticion peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "Hace falta la peticion");
        Objects.requireNonNull(observacion, "Toda modificacion exige la observacion (regla 10)");

        // Solo la fecha que se PUBLICA con la cifra (regla 9). Las del padron son otras, y las fija
        // `componerLaBase` desde el ejercicio: hasta #328 las tres eran esta misma variable.
        LocalDate fechaDeCalculo = LocalDate.now(reloj);
        ResumenDeContribuyente contribuyente =
                directorio
                        .porCodigo(peticion.codContribuyente())
                        .orElseThrow(
                                () -> new ContribuyenteInexistente(peticion.codContribuyente()));
        exigirQueNoHayaUnBeneficioSinRegla(contribuyente, peticion.ejercicio());

        // La UNICA resolucion del conjunto en toda la determinacion (#361): de ella salen el
        // cuadro, el redondeo, el derecho, los vencimientos y el `conjunto_id` que se guarda, y
        // `calcular` la recibe en vez de volver a preguntar.
        CuadroPredialParametrizado.Vigente vigente = cuadro.vigenteEn(peticion.ejercicio());
        PoliticasDeRedondeo redondeo = vigente.redondeo();

        List<PredioEnLaBase> enLaBase = componerLaBase(contribuyente, peticion, redondeo);

        List<Tramo> tramos = vigente.tramos();
        Dinero minimo = vigente.minimoImponible();
        List<DetalleDeterminacionPredio> detalle =
                enLaBase.stream().map(PredioEnLaBase::comoDetalle).toList();

        // Lo que la determinacion necesita para EMITIRSE, resuelto ANTES de asentar (#359, ARQ-09
        // §4: «¿parametros completos? → no → DETENER», antes de la determinacion). Son las
        // piezas que hoy no publica nadie —el derecho y los vencimientos son de ordenanza local
        // (D-02b)—, y hasta #359 se resolvian despues de `registrar`: el 422 salia con la fila y
        // su ALTA ya confirmados. La modalidad es la de la peticion, que es la misma que
        // `calcular` pone en la cabecera y `asentar` escribe (#234): el cronograma y la fila no
        // pueden hablar de modalidades distintas.
        Dinero derechoDeEmision = vigente.derechoDeEmision();
        List<LocalDate> vencimientos = vigente.vencimientos(peticion.modalidad());

        Determinacion calculada =
                registro.calcular(
                        vigente, contribuyente.id(), detalle, tramos, minimo, peticion.modalidad());

        List<AporteDeTramo> aportes =
                TramosProgresivosAcumulativos.desglosar(calculada.baseImponible(), tramos);
        // Reparte con el redondeo del punto CUOTA, que tambien puede faltar (D-03c): falla aqui,
        // con nada escrito todavia.
        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(calculada.montoDeterminado(), vencimientos, redondeo);

        // La determinacion ENTERA, validada por su record, antes de escribir nada.
        DeterminacionPredialCalculada determinada =
                new DeterminacionPredialCalculada(
                        calculada,
                        enLaBase,
                        sumar(enLaBase, PredioEnLaBase::autovaluo),
                        sumar(enLaBase, PredioEnLaBase::valuoExonerado),
                        sumar(enLaBase, PredioEnLaBase::valuoAfecto),
                        vigente.uit(),
                        aportes,
                        minimo,
                        calculada.montoDeterminado(),
                        derechoDeEmision,
                        cuotas,
                        vigente.nombreDelConjunto(),
                        contribuyente.codigo(),
                        contribuyente.nombre(),
                        fechaDeCalculo);

        // Simular es no asentar. Y asentar es lo ULTIMO: despues de esta linea no queda nada que
        // pueda faltar.
        if (peticion.simulacion()) {
            return determinada;
        }
        return determinada.asentadaComo(registro.asentar(calculada, detalle, observacion));
    }

    /**
     * La guarda de RT-012 (#331): con un beneficio del predial vigente a la fecha de referencia del
     * ejercicio, no se emite, porque la deduccion que ese beneficio da no se sabe aplicar todavia
     * (#464). Ver {@link BeneficioPredialSinRegla}.
     */
    private void exigirQueNoHayaUnBeneficioSinRegla(
            ResumenDeContribuyente contribuyente, Ejercicio ejercicio) {
        LocalDate fechaDeReferencia = ejercicio.primerDia();
        List<BeneficioRegistrado> delPredial =
                beneficios.vigentesA(contribuyente.id(), fechaDeReferencia).stream()
                        .filter(beneficio -> TRIBUTO_PREDIAL.equals(beneficio.tributo()))
                        .toList();
        if (!delPredial.isEmpty()) {
            throw new BeneficioPredialSinRegla(
                    contribuyente.codigo(), fechaDeReferencia, delPredial);
        }
    }

    /**
     * La base del contribuyente, leida del padron <b>a las fechas de referencia del ejercicio</b>.
     *
     * <h2>Por que no recibe una fecha (#328)</h2>
     *
     * <p>Hasta #328 recibia {@code hoy}, la misma variable que se publica como fecha de calculo, y
     * con ella leia la titularidad: si el contribuyente tiene base ({@link SinPrediosEnElPadron}),
     * si un predio declarado sigue siendo suyo ({@link PredioAjeno}) y con que {@code %} pondera
     * cada predio. Las tres son hechos <b>del ejercicio</b>: el caracter de sujeto del impuesto se
     * atribuye con la situacion juridica al 1 de enero (TUO LTM art. 10), y una transferencia
     * durante el ejercicio no cambia al obligado del ejercicio (NEG-05 §3). Con la fecha del reloj,
     * una venta en marzo le cargaba 2026 al comprador, dejaba al vendedor sin base o «ajeno», y
     * recalcular 2026 en 2027 daba otro obligado y otro importe — la regla 6 rota.
     *
     * <p>Las fechas las dice {@link Ejercicio}, que es la unica fuente de verdad de «la fecha del
     * ejercicio» en este dominio: la titularidad a {@link Ejercicio#fechaDeLaTitularidad()} —el 31
     * de diciembre del año anterior, porque una transferencia fechada el mismo 1 de enero no cambia
     * al obligado de ese ejercicio (TUO LTM art. 10, segundo parrafo)— y las caracteristicas a
     * {@link Ejercicio#primerDia()}. Derivarlas aqui, en vez de recibirlas, es lo que impide que la
     * fecha de calculo vuelva a colarse por el argumento.
     */
    private List<PredioEnLaBase> componerLaBase(
            ResumenDeContribuyente contribuyente, Peticion peticion, PoliticasDeRedondeo redondeo) {
        LocalDate fechaDeLaTitularidad = peticion.ejercicio().fechaDeLaTitularidad();
        LocalDate fechaDeReferencia = peticion.ejercicio().primerDia();
        List<PredioDelContribuyente> suyos = predios.de(contribuyente.id(), fechaDeLaTitularidad);
        if (suyos.isEmpty()) {
            throw new SinPrediosEnElPadron(contribuyente.codigo(), fechaDeLaTitularidad);
        }

        List<PredioDeclarado> pedidos = autovaluosDe(contribuyente, peticion);

        Map<Long, PredioDeclarado> declarados = new LinkedHashMap<>();
        for (PredioDeclarado declarado : pedidos) {
            if (declarados.put(declarado.predioId(), declarado) != null) {
                throw new PredioRepetido(declarado.predioId());
            }
        }
        for (PredioDelContribuyente predio : suyos) {
            declarados.remove(predio.predioId());
        }
        if (!declarados.isEmpty()) {
            throw new PredioAjeno(
                    contribuyente.codigo(),
                    declarados.keySet().iterator().next(),
                    fechaDeLaTitularidad);
        }

        Map<Long, PredioDeclarado> porPredio = new LinkedHashMap<>();
        for (PredioDeclarado declarado : pedidos) {
            porPredio.put(declarado.predioId(), declarado);
        }

        // Las valuaciones selladas de TODOS sus predios, de una vez. Preguntar dentro del bucle
        // seria una consulta por predio, y la corrida masiva recorre el padron entero.
        Map<Long, ValuacionSellada> selladas =
                valuaciones.deLosPredios(
                        peticion.ejercicio(),
                        suyos.stream().map(PredioDelContribuyente::predioId).toList());

        List<PredioEnLaBase> base = new ArrayList<>();
        for (PredioDelContribuyente predio : suyos) {
            PredioDeclarado declarado = porPredio.get(predio.predioId());
            ValuacionSellada sellada = selladas.get(predio.predioId());
            // LA PRECEDENCIA DE #38, en una linea: manda la sellada cuando trae cifra.
            boolean mandaLaSellada = sellada != null && sellada.tieneCifra();
            if (declarado == null && !mandaLaSellada) {
                throw new PredioSinAutovaluo(predio, sellada);
            }
            // Las del 1 de enero tambien: la determinacion consulta las caracteristicas vigentes a
            // la fecha de referencia, no las actuales (NEG-05 §3, consecuencia 1).
            Optional<CaracteristicasDelPredio> rasgos =
                    caracteristicas.de(predio.predioId(), fechaDeReferencia);
            Dinero exonerado =
                    declarado == null || declarado.valuoExonerado() == null
                            ? Dinero.CERO
                            : declarado.valuoExonerado();
            // La parte exonerada sigue siendo un dato DECLARADO aunque el autovaluo venga sellado:
            // `catastro` valoriza el predio y no sabe que parte esta inafecta —eso es una
            // deduccion, y las deducciones son de este lado (ADR-0024)—.
            Dinero autovaluo;
            OrigenDelAutovaluo origen;
            Long conjuntoDeLaValuacion = null;
            String huellaDeLaValuacion = null;
            Dinero autovaluoDeclarado = null;
            if (mandaLaSellada) {
                ValuacionSellada laSellada = Objects.requireNonNull(sellada);
                autovaluo = laSellada.autovaluo().orElseThrow();
                origen = OrigenDelAutovaluo.SELLADO;
                conjuntoDeLaValuacion = laSellada.conjuntoId();
                huellaDeLaValuacion = laSellada.huella();
                // La declarada NO desaparece cuando manda la sellada: se guarda al lado para que
                // la discrepancia se pueda ver, en vez de descubrirse en ventanilla con el papel
                // ya notificado (#38, AC-3). «Se guarda» desde #362: `comoDetalle` la pasa a
                // `autovaluo_declarado` (V33) y las dos respuestas la publican; hasta entonces
                // este comentario lo afirmaba y la cifra se quedaba en este objeto.
                autovaluoDeclarado = declarado == null ? null : declarado.autovaluo();
            } else {
                autovaluo = Objects.requireNonNull(declarado).autovaluo();
                origen = OrigenDelAutovaluo.DECLARADO;
            }
            Dinero afecto = autovaluo.menos(exonerado);
            Porcentaje cuota = predio.porcentajeTitularidad();
            Dinero ponderado =
                    afecto.por(cuota.valor().movePointLeft(2))
                            .redondeadoEn(PuntoDeRedondeo.BASE_IMPONIBLE_DEL_PREDIO, redondeo);
            base.add(
                    new PredioEnLaBase(
                            predio.predioId(),
                            predio.codigoReferenciaCatastral(),
                            predio.direccion(),
                            rasgos.map(CaracteristicasDelPredio::uso).orElse(null),
                            cuota,
                            autovaluo,
                            exonerado,
                            ponderado,
                            // Lo que suma la titularidad ENTERA del predio, no solo esta cuota
                            // (#690): si es menor que 100, la base sale ponderada por un predio
                            // que no tiene dueño completo, y eso hay que poder decirlo.
                            predio.porcentajeRegistradoDelPredio(),
                            origen,
                            conjuntoDeLaValuacion,
                            huellaDeLaValuacion,
                            autovaluoDeclarado));
        }
        return List.copyOf(base);
    }

    /**
     * Los autovaluos con los que se determina: los que trae la peticion, y si no trae ninguno, los
     * que ya se declararon en <b>este mismo</b> ejercicio.
     *
     * <p>Lo segundo es lo que hace que recalcular no obligue a volver a teclear el padron entero
     * —cambio el conjunto sellado, cambio una titularidad, se corrigio una alicuota— y es la misma
     * lectura que usa la corrida masiva. Del mismo ejercicio y de ningun otro: arrastrar el
     * autovaluo del ano pasado seria aplicar en silencio un {@code % actualizacion} de cero, el
     * factor que D-11 deja sin fuente y que NEG-05 §0.1 advierte que multiplica importes.
     *
     * <p>Si no hay ninguno de los dos, no se inventa: cada predio sin autovaluo se nombra al
     * componer la base.
     *
     * <p><b>Lo ya declarado es lo que declaro el contribuyente, no el autovaluo guardado</b>
     * (#362). Tras una determinacion en que mando la sellada, el autovaluo guardado es el sellado;
     * leerlo como declarado —lo que se hacia hasta #362— convertia 100 000 declarados y 180 000
     * sellados en 180 000 y 180 000 al primer recalculo. La regla la dice {@link
     * PredioDeclarado#delDetalle}, la misma que usa la corrida masiva.
     */
    private List<PredioDeclarado> autovaluosDe(
            ResumenDeContribuyente contribuyente, Peticion peticion) {
        if (!peticion.predios().isEmpty()) {
            return peticion.predios();
        }
        return PredioDeclarado.deLoGuardado(
                yaDeclarados.autovaluosDeclaradosDe(peticion.ejercicio(), contribuyente.id()));
    }

    private static Dinero sumar(
            List<PredioEnLaBase> predios,
            java.util.function.Function<PredioEnLaBase, Dinero> cual) {
        Dinero total = Dinero.CERO;
        for (PredioEnLaBase predio : predios) {
            total = total.mas(cual.apply(predio));
        }
        return total;
    }

    /**
     * Lo que se pide determinar.
     *
     * @param ejercicio el ejercicio que se determina
     * @param codContribuyente el codigo del contribuyente en el padron
     * @param predios el autovaluo declarado de cada uno de sus predios; hacen falta todos
     * @param modalidad el cronograma que se aplica; <b>obligatorio</b> desde #234
     * @param simulacion si esto no se guarda
     */
    public record Peticion(
            Ejercicio ejercicio,
            String codContribuyente,
            List<PredioDeclarado> predios,
            ModalidadDelPredial modalidad,
            boolean simulacion) {

        public Peticion {
            Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
            Objects.requireNonNull(codContribuyente, "La determinacion necesita el contribuyente");
            predios =
                    List.copyOf(
                            Objects.requireNonNull(
                                    predios, "La lista de predios es vacia," + " no nula"));
            // No hay valor por omision, y es la mitad de #234 que no esta en la base. Antes de
            // este cambio, un cuerpo sin `modalidad` determinaba TRIMESTRAL en silencio; con la
            // columna de V21 puesta, ese silencio pasaria a quedar ESCRITO como si el
            // contribuyente lo hubiera elegido.
            Objects.requireNonNull(
                    modalidad,
                    "La determinacion dice bajo que cronograma se emite: «modalidad» es"
                            + " obligatoria y no tiene valor por omision (#234)");
        }
    }

    /**
     * El autovaluo declarado de un predio.
     *
     * @param predioId el predio, que tiene que ser del contribuyente
     * @param autovaluo terreno + construccion + obras complementarias (RT-010)
     * @param valuoExonerado la parte no afecta; {@code null} se lee como ninguna
     */
    public record PredioDeclarado(
            long predioId,
            Dinero autovaluo,
            @org.jspecify.annotations.Nullable Dinero valuoExonerado) {

        public PredioDeclarado {
            if (predioId <= 0) {
                throw new IllegalArgumentException(
                        "El predio declarado necesita su identificador: " + predioId);
            }
            Objects.requireNonNull(autovaluo, "El predio declarado necesita su autovaluo");
            if (autovaluo.esNegativo()) {
                throw new IllegalArgumentException("El autovaluo no puede ser negativo");
            }
        }

        /**
         * Lo que el contribuyente declaro de un predio en una determinacion ya guardada, o vacio si
         * no declaro nada (#362).
         *
         * <p>El autovaluo es {@link DetalleDeterminacionPredio#autovaluoDeclaradoSegunElOrigen()} y
         * no {@code autovaluo()}: tras una determinacion SELLADO, el segundo es la cifra de {@code
         * catastro}. La parte exonerada si es {@code valuoExonerado()} en los dos origenes: es un
         * dato declarado aunque el autovaluo venga sellado ({@code catastro} no sabe que parte esta
         * inafecta, ADR-0024).
         *
         * <p>Vacio —mando la sellada y nadie habia declarado, o la fila es anterior a V33— deja el
         * predio sin declarada: si la sellada sigue, manda ella; si no, el predio sale sin
         * autovaluo y se dice ({@link PredioSinAutovaluo}), en vez de tomar como declarada una
         * cifra que sello otro.
         */
        public static Optional<PredioDeclarado> delDetalle(DetalleDeterminacionPredio detalle) {
            return detalle.autovaluoDeclaradoSegunElOrigen()
                    .map(
                            declarado ->
                                    new PredioDeclarado(
                                            detalle.predioId(),
                                            declarado,
                                            detalle.valuoExonerado()));
        }

        /**
         * Lo declarado de todos los predios de una determinacion guardada: la lectura que usan el
         * recalculo individual y la corrida masiva, y por eso una sola.
         */
        public static List<PredioDeclarado> deLoGuardado(List<DetalleDeterminacionPredio> detalle) {
            List<PredioDeclarado> declarados = new ArrayList<>();
            for (DetalleDeterminacionPredio predio : detalle) {
                delDetalle(predio).ifPresent(declarados::add);
            }
            return List.copyOf(declarados);
        }
    }

    /** Ese codigo no esta en el padron de contribuyentes. */
    public static final class ContribuyenteInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(String codigo) {
            super("No hay ningun contribuyente con codigo '" + codigo + "' en esta municipalidad");
        }
    }

    /**
     * El contribuyente existe y no tiene ningun predio a su nombre al 1 de enero del ejercicio,
     * antes de las transferencias de ese dia ({@link Ejercicio#fechaDeLaTitularidad()}).
     *
     * <p>Es lo que le pasa, por ejemplo, al que compra durante el ejercicio —tambien el mismo 1 de
     * enero—: su primer ejercicio es el siguiente (TUO LTM art. 10, #328).
     */
    public static final class SinPrediosEnElPadron extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinPrediosEnElPadron(String codigo, LocalDate fechaDeLaTitularidad) {
            super(
                    "El contribuyente "
                            + codigo
                            + " no tiene ningun predio a su nombre al "
                            + fechaDeLaTitularidad
                            + ", antes de cualquier transferencia del 1 de enero del ejercicio"
                            + " (TUO LTM art. 10: el adquirente asume desde el año siguiente): un"
                            + " contribuyente sin predios no tiene base imponible cero, no tiene"
                            + " determinacion (NEG-05 §1)");
        }
    }

    /**
     * El contribuyente tiene un beneficio del predial que rige al 1 de enero del ejercicio, y la
     * regla que lo aplica —RT-012, las deducciones del pensionista y del adulto mayor (TUO LTM art.
     * 19, Ley 30490)— todavia no existe (#331, #464).
     *
     * <p>No se emite. Hasta #331 la cadena encadenaba RT-011, RT-013 y RT-014 sin RT-012, y a quien
     * la ley le deduce 50 UIT se le emitia la cifra entera sin decirlo: la misma cifra que a su
     * vecino sin beneficio. Negarse es peor para la emision —el contribuyente queda sin determinar,
     * o observado en la masiva— y mejor para el contribuyente: lo que falta se ve, en vez de
     * cobrarse.
     *
     * <p>Lo que se mira es el <b>beneficio concedido</b> y no la {@code condicion_especial}: la
     * condicion se registra, el beneficio es el acto que valido los requisitos. Y se mira a {@link
     * Ejercicio#primerDia()}, la fecha de referencia (NEG-05 §3): un beneficio cesado antes no
     * cuenta. Que pasa con uno concedido a mitad del ejercicio es el caso c03 de RT-012, sin
     * decidir, y lo decide #464 con el resto de la regla.
     */
    public static final class BeneficioPredialSinRegla extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BeneficioPredialSinRegla(
                String codigo, LocalDate fechaDeReferencia, List<BeneficioRegistrado> vigentes) {
            super(
                    "El contribuyente "
                            + codigo
                            + " tiene un beneficio del predial que rige al "
                            + fechaDeReferencia
                            + " ("
                            + vigentes.stream()
                                    .map(b -> b.tipo() + ", " + b.baseLegal())
                                    .collect(java.util.stream.Collectors.joining("; "))
                            + ") y la regla que lo aplica, RT-012, todavia no existe (#464):"
                            + " determinarlo sin la deduccion le cobraria de mas. No se emite");
        }
    }

    /**
     * Falta el autovaluo de uno de los predios del contribuyente.
     *
     * <p>No se determina con los demas: los tramos son progresivos sobre la base del
     * <b>conjunto</b>, asi que dejar un predio fuera no produce una determinacion incompleta sino
     * una <b>equivocada</b> y mas barata, sin ningun error de por medio (NEG-05 §1).
     */
    public static final class PredioSinAutovaluo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final long predioId;

        PredioSinAutovaluo(PredioDelContribuyente predio, @Nullable ValuacionSellada sellada) {
            super(
                    "El predio "
                            + predio.codigoReferenciaCatastral()
                            + " (id "
                            + predio.predioId()
                            + ") entra en la base y no trae autovaluo: ni declarado, ni sellado por"
                            + " `catastro`. "
                            + porQueNoHayValuacion(sellada)
                            + " Determinar sin el deja la base del contribuyente por debajo de lo"
                            + " que es");
            this.predioId = predio.predioId();
        }

        /**
         * Lo que `catastro` dijo, cuando dijo algo.
         *
         * <p>Antes de #38 este mensaje explicaba «el sistema no lo puede derivar todavia» y
         * nombraba GOB-03, D-02b y D-11 — que era cierto de ESTE sistema y no de la frontera. Ahora
         * quien valoriza es `catastro`, asi que lo que hace falta decir es <b>que contesto</b> y
         * con que llave se paro, en vez de repetir de memoria una lista de bloqueos que ya no es la
         * suya. Las tres respuestas se arreglan de maneras distintas: publicar la valuacion, sellar
         * la llave que falta, o teclear la declaracion jurada.
         */
        private static String porQueNoHayValuacion(@Nullable ValuacionSellada sellada) {
            if (sellada == null) {
                return "`catastro` no ha publicado ninguna valuacion de este predio para este"
                        + " ejercicio.";
            }
            return "`catastro` publico su valuacion y NO pudo calcularla: «"
                    + sellada.motivo()
                    + "»"
                    + (sellada.llaveQueFalta() == null
                            ? ""
                            : " (falta la llave " + sellada.llaveQueFalta() + ")")
                    + ".";
        }

        public long predioId() {
            return predioId;
        }
    }

    /** Se declaro dos veces el mismo predio. */
    public static final class PredioRepetido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioRepetido(long predioId) {
            super(
                    "El predio "
                            + predioId
                            + " se declara dos veces: no se puede saber cual de los dos autovaluos"
                            + " entra en la base");
        }
    }

    /**
     * Se declaro un predio que no es del contribuyente al 1 de enero del ejercicio, antes de las
     * transferencias de ese dia ({@link Ejercicio#fechaDeLaTitularidad()}).
     *
     * <p>Una venta del propio ejercicio ya no lo dispara (#328): el que vende en marzo —o el mismo
     * 1 de enero— sigue siendo el sujeto del ejercicio.
     */
    public static final class PredioAjeno extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioAjeno(String codigo, long predioId, LocalDate fechaDeLaTitularidad) {
            super(
                    "El predio "
                            + predioId
                            + " no esta a nombre del contribuyente "
                            + codigo
                            + " al "
                            + fechaDeLaTitularidad
                            + ", antes de cualquier transferencia del 1 de enero del ejercicio"
                            + " (TUO LTM art. 10: el adquirente asume desde el año siguiente): la"
                            + " titularidad sale del padron, no de la peticion");
        }
    }
}
