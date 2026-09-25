package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la hoja resumen de una declaracion jurada consigna (#563).
 *
 * <h2>Por que existe</h2>
 *
 * <p>{@code Rentas · Declaración jurada} es el <b>unico documento del modulo pensado para
 * imprimirse y firmarse</b>: termina con «Declaro bajo juramento que los datos consignados son
 * verdaderos» y dos lineas de firma. Todo lo que consignaba —el declarante, sus predios con su
 * valuo, y los cuatro totales— venia del juego de datos de la maqueta, con cualquier sesion y sin
 * haber abierto ningun contribuyente. Lo unico que el backend publicaba de una DJ eran sus
 * identificadores y sus fechas: nada del contribuyente, nada del predio, ninguna cifra.
 *
 * <p>Un papel es la unica salida del sistema que sobrevive fuera de el. Una hoja con el nombre y el
 * DNI de una persona, dos predios que no son suyos y un «total a pagar», firmada por quien atiende
 * y por quien declara, <b>no se distingue de una correcta</b> una vez impresa.
 *
 * <h2>De donde sale cada cosa, y por que de ahi</h2>
 *
 * <ul>
 *   <li><b>El declarante</b>, del padron: {@code DirectorioDeContribuyentes}. El domicilio es el
 *       <b>vigente a la fecha de corte</b> y no «el ultimo» (regla 9): la hoja de una DJ de marzo
 *       tiene que poder reimprimirse como se imprimio.
 *   <li><b>Las cifras</b> —autovaluo, valuo exonerado, valuo afecto y el impuesto— de la <b>ultima
 *       determinacion predial del ejercicio</b> de ese contribuyente, que es el unico sitio donde
 *       el sistema las tiene. No se derivan aqui: el autovaluo <b>se declara</b> (#395), porque el
 *       sistema no sabe valorizar mientras falten el cuadro de valores unitarios y la depreciacion
 *       (GOB-03), los aranceles (D-02b) y el {@code % actualizacion} (D-11).
 *   <li><b>Los predios</b>, con determinacion, son <b>su detalle</b>: lo que se cobro, de donde
 *       salen ya los dos totales, y por eso los totales son la suma de las filas (#328). Sin
 *       determinacion, los del padron <b>al 1 de enero del ejercicio, antes de las transferencias
 *       de ese dia</b> ({@link Ejercicio#fechaDeLaTitularidad()}). De catastro —{@code
 *       PrediosDelContribuyente} a esa misma fecha— salen solo el codigo, la direccion y el tipo.
 *   <li><b>El predio que la DJ declara</b> ({@link DeclaracionJurada#predioId()}) sale
 *       <b>siempre</b>, aunque no sea de la base del ejercicio (#472). Si no lo es, va en una fila
 *       aparte, marcada con su {@link Condicion} y sin cifras: no suma a los totales.
 * </ul>
 *
 * <h2>Por que el predio declarado sale aunque no sea del ejercicio (#472)</h2>
 *
 * <p>B compra P el 15 de marzo y presenta su DJ de P en abril. El padron del ejercicio es el del 1
 * de enero, y ahi P todavia era de A: hasta #472 la hoja de la DJ de B <b>no traia el predio que la
 * DJ declara</b>. Es correcto para lo que se cobra —el adquirente asume a partir del 1 de enero del
 * año siguiente, TUO LTM art. 10—, y por eso la fila no lleva cifras ni suma; pero un papel que se
 * firma bajo juramento declarando P tiene que decir P. El codigo y la direccion salen del padron al
 * dia en que se <b>presento</b> la DJ, que es fijo: reimprimirla otro dia da la misma hoja.
 *
 * <h2>Por que el padron del ejercicio y no el del dia que se pide (#328)</h2>
 *
 * <p>Hasta #328 las filas salian de {@code PrediosDelContribuyente} a la fecha de corte —hoy, si la
 * peticion no trae {@code fecha}— y las cifras de la determinacion del ejercicio. Con una venta a
 * mitad de año las dos lecturas dejaban de hablar del mismo conjunto: la hoja de un contribuyente
 * que declaro P1 y P, pedida despues de vender P, salia con una sola fila y con un valuo afecto
 * total y un impuesto que incluian P. Un papel que se firma bajo juramento con un total que no es
 * la suma de sus filas, y que ademas cambiaba segun el dia en que se reimprimiera. El obligado del
 * ejercicio es el titular al 1 de enero (TUO LTM art. 10), y esa es la fecha a la que se lee: la de
 * {@link Ejercicio#fechaDeLaTitularidad()}, el 31 de diciembre del año anterior, porque «el
 * adquirente asume la condicion de contribuyente a partir del 1 de enero del año siguiente de
 * producido el hecho» y una venta fechada el mismo 1 de enero ya figura en el padron a ese dia.
 *
 * <p>El <b>domicilio</b> sigue a la fecha de corte: es otra decision, con su motivo arriba, y no
 * cambia.
 *
 * <h2>Lo que la hoja NO puede consignar todavia, dicho por su nombre</h2>
 *
 * <p>Sin determinacion del ejercicio no hay ninguna cifra que poner, y la hoja lo dice en vez de
 * dejar celdas en blanco que se lean como ceros. El <b>derecho de emision</b> y el <b>total a
 * pagar</b> tampoco viajan aunque haya determinacion: {@code Determinacion} guarda la base
 * imponible y el impuesto, no el derecho —que es una cifra de ordenanza local, {@code
 * DERECHO_EMISION_PREDIAL}, y sigue siendo D-02b—. Sumarlo aqui con un cero inventado seria
 * exactamente lo que este issue existe para impedir.
 *
 * <p><b>Una sola transaccion</b> para las cuatro lecturas (#486): entre una y otra cabria una
 * transferencia, y la hoja saldria diciendo que un predio es de dos personas y de ninguna.
 */
@Service
public class ConsultaDeLaHojaDeDeclaracion {

    private final DeclaracionJuradaRepository declaraciones;
    private final DirectorioDeContribuyentes directorio;
    private final PrediosDelContribuyente predios;
    private final DeterminacionRepository determinaciones;

    public ConsultaDeLaHojaDeDeclaracion(
            DeclaracionJuradaRepository declaraciones,
            DirectorioDeContribuyentes directorio,
            PrediosDelContribuyente predios,
            DeterminacionRepository determinaciones) {
        this.declaraciones = declaraciones;
        this.directorio = directorio;
        this.predios = predios;
        this.determinaciones = determinaciones;
    }

    /**
     * La hoja de esa declaracion, o vacio si no hay ninguna con ese numero en ese ejercicio.
     *
     * @param aLaFecha a que dia se resuelve el domicilio (regla 9). La titularidad NO: es la del 1
     *     de enero del ejercicio antes de las transferencias de ese dia ({@link
     *     Ejercicio#fechaDeLaTitularidad()}), por el mismo motivo que la determinacion (#328)
     */
    @Transactional(readOnly = true)
    public Optional<Hoja> de(String numero, Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");

        Optional<DeclaracionJurada> encontrada = declaraciones.porNumero(numero, ejercicio);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        DeclaracionJurada declaracion = encontrada.get();
        long contribuyenteId = declaracion.contribuyenteId();

        ResumenDeContribuyente quien =
                directorio.porIds(Set.of(contribuyenteId)).get(contribuyenteId);
        String domicilio = directorio.domicilioFiscalDe(contribuyenteId, aLaFecha).orElse(null);

        Optional<Determinacion> determinacion =
                determinaciones.ultimaPredialDe(ejercicio, contribuyenteId);

        // El padron DEL EJERCICIO, no el del dia en que se pide (#328): la titularidad al 1 de
        // enero antes de las transferencias de ese dia, que es la misma que determino.
        LocalDate fechaDeLaTitularidad = ejercicio.fechaDeLaTitularidad();
        Map<Long, PredioDelContribuyente> delEjercicio = new LinkedHashMap<>();
        for (PredioDelContribuyente predio : predios.de(contribuyenteId, fechaDeLaTitularidad)) {
            delEjercicio.put(predio.predioId(), predio);
        }

        List<FilaDePredio> filas = new ArrayList<>();
        List<String> faltan = new ArrayList<>();
        if (determinacion.isPresent()) {
            List<DetalleDeterminacionPredio> cobrado =
                    determinacion
                            .map(Determinacion::id)
                            .map(determinaciones::detalleDe)
                            .orElse(List.of());
            for (DetalleDeterminacionPredio detalle : cobrado) {
                PredioDelContribuyente predio = delEjercicio.get(detalle.predioId());
                if (predio == null) {
                    faltan.add(noConstaEnElEjercicio(detalle.predioId(), fechaDeLaTitularidad));
                }
                filas.add(FilaDePredio.cobrada(detalle, predio));
            }
        } else {
            for (PredioDelContribuyente predio : delEjercicio.values()) {
                filas.add(FilaDePredio.sinCifras(predio));
            }
        }
        Long declarado = declaracion.predioId();
        if (declarado != null && filas.stream().noneMatch(fila -> fila.predioId() == declarado)) {
            filas.add(filaDelDeclarado(declaracion, declarado, delEjercicio, faltan));
        }
        faltan.addAll(loQueFalta(determinacion.isPresent(), ejercicio));

        return Optional.of(
                new Hoja(
                        declaracion,
                        aLaFecha,
                        quien,
                        domicilio,
                        List.copyOf(filas),
                        determinacion.map(Determinacion::baseImponible).orElse(null),
                        determinacion.map(Determinacion::montoDeterminado).orElse(null),
                        List.copyOf(faltan)));
    }

    /**
     * La fila del predio que la DJ declara cuando no esta entre las de la base del ejercicio
     * (#472), con la {@link Condicion} que dice por que. Nunca lleva cifras: lo que se cobra en el
     * ejercicio son las otras filas, y de ellas salen los totales.
     */
    private FilaDePredio filaDelDeclarado(
            DeclaracionJurada declaracion,
            long declarado,
            Map<Long, PredioDelContribuyente> delEjercicio,
            List<String> faltan) {
        PredioDelContribuyente delPrimeroDeEnero = delEjercicio.get(declarado);
        if (delPrimeroDeEnero != null) {
            // Es suyo al 1 de enero y no esta en las filas: solo cabe con determinacion, y es
            // que se determino sin el.
            faltan.add(
                    "El predio declarado "
                            + declarado
                            + " es del declarante en el ejercicio y la determinacion no lo"
                            + " incluye: la hoja no tiene cifras que consignarle. Hay que volver a"
                            + " determinar el ejercicio");
            return FilaDePredio.declarada(
                    delPrimeroDeEnero, Condicion.DECLARADO_FUERA_DE_LA_DETERMINACION);
        }
        LocalDate presentada = declaracion.fechaPresentacion();
        for (PredioDelContribuyente predio :
                predios.de(declaracion.contribuyenteId(), presentada)) {
            if (predio.predioId() == declarado) {
                return FilaDePredio.declarada(
                        predio, Condicion.DECLARADO_AFECTA_AL_EJERCICIO_SIGUIENTE);
            }
        }
        faltan.add(
                "El predio declarado "
                        + declarado
                        + " no consta a nombre del declarante ni al 1 de enero del ejercicio ni al "
                        + presentada
                        + ", el dia en que presento la declaracion: la fila no tiene codigo,"
                        + " direccion ni porcentaje que leer del padron");
        return FilaDePredio.sinTitularidad(declarado);
    }

    /**
     * Un predio que la determinacion cobro y que el padron al 1 de enero no pone a nombre de este
     * contribuyente (#328).
     *
     * <p>Solo pasa si la titularidad cambio <b>despues</b> de determinar con una fecha anterior al
     * ejercicio —una transferencia inscrita tarde—, o con una determinacion anterior a #328, que
     * leia el padron del dia. La fila sale igual, porque es lo que se cobro y sin ella el total
     * deja de ser la suma de las filas; lo que no sale es el codigo ni la direccion, que no hay de
     * donde leer, y se dice aqui en vez de inventarlos.
     */
    private static String noConstaEnElEjercicio(long predioId, LocalDate fechaDeLaTitularidad) {
        return "El predio "
                + predioId
                + " esta en la determinacion del ejercicio y al "
                + fechaDeLaTitularidad
                + " no consta a nombre de este contribuyente: la titularidad cambio despues de"
                + " determinar. La fila consigna lo que se cobro, sin codigo ni direccion que"
                + " leer; hay que volver a determinar el ejercicio";
    }

    /**
     * Lo que la hoja no puede consignar, nombrado.
     *
     * <p>Se publica como lista y no como un booleano «imprimible» para que la pantalla pueda decir
     * <b>que</b> falta: «no se puede imprimir» sin decir por que es lo que hace que alguien lo
     * imprima igual desde otro sitio.
     */
    private static List<String> loQueFalta(boolean hayDeterminacion, Ejercicio ejercicio) {
        List<String> falta = new ArrayList<>();
        if (!hayDeterminacion) {
            falta.add(
                    "No hay determinacion del impuesto predial del ejercicio "
                            + ejercicio.valor()
                            + " para este contribuyente: sin ella la hoja no tiene autovaluo, ni"
                            + " valuo afecto, ni impuesto que consignar. Se calcula en «Calculo"
                            + " individual del impuesto predial»");
        }
        // Aunque haya determinacion: el derecho de emision es una cifra de ordenanza local
        // (DERECHO_EMISION_PREDIAL, D-02b) y la determinacion guarda la base y el impuesto,
        // no el derecho. Sin el no hay «total a pagar» que escribir en un papel que se firma.
        falta.add(
                "El derecho de emision y el total a pagar no se publican: son"
                        + " DERECHO_EMISION_PREDIAL del conjunto sellado, una cifra de ordenanza"
                        + " local que sigue sin cargarse (D-02b)");
        return falta;
    }

    /**
     * La hoja entera, con su fecha de corte: la del domicilio, no la de los predios (#328).
     *
     * @param declarante nulo si el contribuyente ya no esta en el padron; la hoja lo dice en vez de
     *     inventar un nombre
     * @param valuoAfectoTotal y {@code impuestoInsoluto} nulos cuando no hay determinacion del
     *     ejercicio: no hay cifra que dar, y un cero se leeria como «no debe nada»
     * @param faltan lo que la hoja no puede consignar todavia, con su motivo
     */
    public record Hoja(
            DeclaracionJurada declaracion,
            LocalDate aLaFecha,
            @Nullable ResumenDeContribuyente declarante,
            @Nullable String domicilioFiscal,
            List<FilaDePredio> predios,
            @Nullable Dinero valuoAfectoTotal,
            @Nullable Dinero impuestoInsoluto,
            List<String> faltan) {}

    /**
     * Que es una fila respecto de lo que se cobra en el ejercicio (#472).
     *
     * <p>Solo las {@link #BASE_DEL_EJERCICIO} llevan cifras y suman a los totales. Las otras son el
     * predio que la DJ declara cuando no esta en esa base, y dicen por que.
     */
    public enum Condicion {
        /** De lo que se cobra: el detalle de la determinacion, o el padron al 1 de enero. */
        BASE_DEL_EJERCICIO,
        /**
         * El declarante no era titular al 1 de enero y si el dia en que presento: lo adquirio en el
         * año, y como adquirente asume a partir del 1 de enero siguiente (TUO LTM art. 10).
         */
        DECLARADO_AFECTA_AL_EJERCICIO_SIGUIENTE,
        /** Es suyo al 1 de enero, pero la determinacion del ejercicio se hizo sin el. */
        DECLARADO_FUERA_DE_LA_DETERMINACION,
        /** El padron no lo pone a su nombre ni al 1 de enero ni el dia en que presento. */
        DECLARADO_SIN_TITULARIDAD
    }

    /**
     * Un predio de la hoja.
     *
     * <p>El {@code porcentajePropiedad} sale de la determinacion cuando la hay —es el que se uso
     * para calcular, y la hoja tiene que decir el que se aplico, no el de hoy— y de la titularidad
     * del ejercicio ({@link Ejercicio#fechaDeLaTitularidad()}) cuando no.
     *
     * @param codigoReferenciaCatastral nulo, igual que {@code direccion} y {@code tipo}, solo en la
     *     fila de un predio cobrado que el padron al 1 de enero no pone a nombre del declarante: la
     *     hoja lo dice en {@code faltan} (#328), y en la del predio declarado que el padron no pone
     *     a su nombre (#472)
     * @param porcentajePropiedad nulo solo en esa ultima: no hay cuota que leer
     * @param condicion si la fila es de la base del ejercicio o el predio declarado fuera de ella
     */
    public record FilaDePredio(
            long predioId,
            @Nullable String codigoReferenciaCatastral,
            @Nullable String direccion,
            @Nullable String tipo,
            @Nullable Porcentaje porcentajePropiedad,
            @Nullable Dinero autovaluo,
            @Nullable Dinero valuoExonerado,
            @Nullable Dinero valuoAfecto,
            Condicion condicion) {

        public FilaDePredio {
            Objects.requireNonNull(condicion, "Toda fila dice si es de la base del ejercicio");
            if (condicion != Condicion.BASE_DEL_EJERCICIO && valuoAfecto != null) {
                throw new IllegalArgumentException(
                        "El predio declarado fuera de la base no lleva cifras: no suma (#472)");
            }
        }

        /** La fila de lo que se cobro: las cifras y el % del detalle; del padron, el nombre. */
        static FilaDePredio cobrada(
                DetalleDeterminacionPredio detalle, @Nullable PredioDelContribuyente predio) {
            return new FilaDePredio(
                    detalle.predioId(),
                    predio == null ? null : predio.codigoReferenciaCatastral(),
                    predio == null ? null : predio.direccion(),
                    predio == null ? null : predio.tipo(),
                    detalle.porcentajePropiedad(),
                    detalle.autovaluo(),
                    detalle.valuoExonerado(),
                    detalle.baseImponiblePredio(),
                    Condicion.BASE_DEL_EJERCICIO);
        }

        /** La fila de un predio del ejercicio sin determinacion: nada que consignar como cifra. */
        static FilaDePredio sinCifras(PredioDelContribuyente predio) {
            return new FilaDePredio(
                    predio.predioId(),
                    predio.codigoReferenciaCatastral(),
                    predio.direccion(),
                    predio.tipo(),
                    predio.porcentajeTitularidad(),
                    null,
                    null,
                    null,
                    Condicion.BASE_DEL_EJERCICIO);
        }

        /** El predio declarado fuera de la base, con lo que el padron dice de el y sin cifras. */
        static FilaDePredio declarada(PredioDelContribuyente predio, Condicion condicion) {
            return new FilaDePredio(
                    predio.predioId(),
                    predio.codigoReferenciaCatastral(),
                    predio.direccion(),
                    predio.tipo(),
                    predio.porcentajeTitularidad(),
                    null,
                    null,
                    null,
                    condicion);
        }

        /** El predio declarado que el padron no pone a su nombre: solo su identificador. */
        static FilaDePredio sinTitularidad(long predioId) {
            return new FilaDePredio(
                    predioId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Condicion.DECLARADO_SIN_TITULARIDAD);
        }
    }
}
