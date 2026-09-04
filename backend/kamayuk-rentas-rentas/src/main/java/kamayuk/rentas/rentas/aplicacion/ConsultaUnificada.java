package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.MovimientosDelLibro;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.tesoreria.ConvenioDelContribuyente;
import kamayuk.rentas.tesoreria.ConveniosDelContribuyente;
import kamayuk.rentas.valores.ValorDelContribuyente;
import kamayuk.rentas.valores.ValoresDelContribuyente;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code consulta_unificada}: la ficha consolidada de un contribuyente (RF-046, #25).
 *
 * <h2>Por que vive en {@code rentas}</h2>
 *
 * <p>No podia vivir en {@code cuentacorriente}, que es el contexto que mas secciones aporta —saldo,
 * deudas, pagos, altas y bajas—: ARQ-01 §4 regla 2 dice que «cuentacorriente no conoce a nadie», y
 * esta pantalla necesita ademas los convenios de {@code tesoreria} y los valores de {@code
 * valores}. Alojarla alli habria obligado a invertir la unica arista que el mapa de contextos
 * declara en un solo sentido, y con ella el motivo por el que el libro es un libro: recibe asientos
 * y no sabe de donde vienen.
 *
 * <p>Vive en {@code rentas} por lo mismo que {@code consulta_vehiculos} y {@code consulta_predios}:
 * es el contexto <b>de la pantalla</b> —«Consulta unificada predial-arbitrios», y el predial y los
 * arbitrios se determinan aqui (ARQ-01 §3.3)— y el unico de los cuatro que puede depender de los
 * otros tres sin cerrar ningun ciclo. Consume a cada uno <b>solo por su API publica</b>, el paquete
 * raiz: {@link ConsultaDeDeudaPublica} y {@link MovimientosDelLibro} de {@code cuentacorriente},
 * {@link ConveniosDelContribuyente} de {@code tesoreria}, {@link ValoresDelContribuyente} de {@code
 * valores} y {@link DirectorioDeContribuyentes} de {@code contribuyentes}. Ninguna tabla ajena, y
 * Spring Modulith lo verifica.
 *
 * <h2>Lo que esta ficha NO trae, y por que</h2>
 *
 * <ul>
 *   <li><b>La rejilla «Impuesto anual»</b> del prototipo —numero de HR, numero de calculo, valuo
 *       afecto/exonerado/total, impuesto predial, limpieza publica, parques y jardines, relleno
 *       sanitario y serenazgo—. El valuo depende de tablas de valores unitarios y aranceles sin
 *       firmar (D-02a) y las cuatro cifras de arbitrios de ordenanzas sin ratificar (D-02b, #31).
 *       Las claves <b>no viajan</b>: un cero se leeria como «este contribuyente no paga arbitrios»,
 *       que es una afirmacion distinta de «todavia no sabemos cuanto». Es el mismo criterio con que
 *       {@code consulta_resumen_predial} omite las suyas.
 *   <li><b>La pestaña «Movimientos del Predio»</b>. Ya esta publicada: es el historico versionado
 *       de la ficha catastral, {@code GET /catastro/fichas/{tipo}/{cod}?historico=true}. Repetirla
 *       aqui seria una segunda consulta para la misma pregunta, y la segunda es la que acaba
 *       divergiendo.
 * </ul>
 *
 * <h2>Las sumas las hace el servidor</h2>
 *
 * <p>RNF-083. {@link ResumenDeSaldos} se compone aqui, sobre <b>todas</b> las obligaciones del
 * contribuyente y no sobre la pagina que se devuelve: un resumen que sumara la pagina diria una
 * cifra distinta segun cuantas filas caben. La interfaz recibe las cinco cifras hechas y su fecha,
 * y no tiene ninguna que componer.
 */
@Service
public class ConsultaUnificada {

    private final DirectorioDeContribuyentes padron;
    private final ConsultaDeDeudaPublica deuda;
    private final MovimientosDelLibro libro;
    private final ConveniosDelContribuyente convenios;
    private final ValoresDelContribuyente valores;
    private final DeclaracionJuradaRepository declaraciones;
    private final Clock reloj;

    public ConsultaUnificada(
            DirectorioDeContribuyentes padron,
            ConsultaDeDeudaPublica deuda,
            MovimientosDelLibro libro,
            ConveniosDelContribuyente convenios,
            ValoresDelContribuyente valores,
            DeclaracionJuradaRepository declaraciones,
            Clock reloj) {
        this.padron = padron;
        this.deuda = deuda;
        this.libro = libro;
        this.convenios = convenios;
        this.valores = valores;
        this.declaraciones = declaraciones;
        this.reloj = reloj;
    }

    /** La fecha de hoy, del reloj inyectado y no de {@code LocalDate.now()} (regla 6). */
    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /**
     * La ficha completa del contribuyente, a la fecha de corte.
     *
     * <p><b>Una sola transaccion para las seis secciones.</b> Sin {@code @Transactional} no hay
     * {@code SET LOCAL} y la politica RLS no puede evaluar {@code
     * current_setting('app.municipalidad_id')}: la consulta no devuelve menos filas, <b>falla</b>.
     * Es el defecto que {@code GET /catastro/vias} arrastro hasta que alguien con permiso llego a
     * el. Y que sea <b>una</b> importa por algo mas: las seis secciones se leen del mismo instante
     * de la base, asi que la ficha no puede mostrar un pago que la deuda todavia no descontaba.
     *
     * <p><b>Un codigo que no existe es 404, no una ficha vacia.</b> A diferencia de {@code
     * consulta_deuda} —que devuelve una pagina vacia porque es una rejilla de busqueda—, esto es la
     * ficha de una persona concreta: seis secciones vacias afirmarian sobre alguien que no esta en
     * el padron de esta municipalidad que no debe nada, no tiene convenios y nunca declaro. Mismo
     * criterio que {@code constanciaDeNoAdeudo}.
     *
     * @throws ProblemaDeNegocio {@code NO_ENCONTRADO} si el codigo no identifica a ningun
     *     contribuyente de la municipalidad activa
     */
    @Transactional(readOnly = true)
    public Ficha de(Criterio criterio, Paginacion paginacion) {
        Objects.requireNonNull(criterio, "La ficha unificada es de un contribuyente concreto");
        Objects.requireNonNull(paginacion, "Sin paginacion no hay orden garantizado");

        ResumenDeContribuyente contribuyente =
                padron.porCodigo(criterio.codigoContribuyente())
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun contribuyente con el codigo "
                                                        + criterio.codigoContribuyente()
                                                        + " en esta municipalidad"));

        List<ObligacionPublica> todas =
                deuda.deTodoElContribuyente(contribuyente.id(), criterio.aLaFecha());
        List<ObligacionPublica> delAlcance = new ArrayList<>();
        for (ObligacionPublica obligacion : todas) {
            if (criterio.alcance().incluye(obligacion.tributo())) {
                delAlcance.add(obligacion);
            }
        }
        // La mas reciente primero, como se lee un listado de deuda en ventanilla. El orden
        // lo fija esta consulta y no el cliente: cada seccion tiene el suyo, y una sola
        // clave `ordenarPor` para seis rejillas no podria valer para todas (ver el javadoc
        // de ConsultaUnificadaController).
        delAlcance.sort(
                Comparator.comparing((ObligacionPublica o) -> o.ejercicio().valor())
                        .reversed()
                        .thenComparing(ObligacionPublica::tributo));

        return new Ficha(
                contribuyente,
                criterio.aLaFecha(),
                ResumenDeSaldos.de(delAlcance, criterio.aLaFecha()),
                pagina(delAlcance, paginacion),
                libro.pagosDe(
                        criterio.codigoContribuyente(),
                        null,
                        null,
                        ordenadaPor(paginacion, "fecha_valor", Paginacion.Direccion.DESCENDENTE)),
                libro.altasYBajasDe(
                        criterio.codigoContribuyente(),
                        criterio.alcance().tributo(),
                        ordenadaPor(paginacion, "fecha_valor", Paginacion.Direccion.DESCENDENTE)),
                convenios.deTodoElContribuyente(
                        criterio.codigoContribuyente(),
                        criterio.aLaFecha(),
                        ordenadaPor(paginacion, "fecha", Paginacion.Direccion.DESCENDENTE)),
                valores.deTodoElContribuyente(
                        contribuyente.id(),
                        criterio.aLaFecha(),
                        ordenadaPor(paginacion, "fecha_emision", Paginacion.Direccion.DESCENDENTE)),
                declaraciones.deContribuyente(
                        contribuyente.id(),
                        ordenadaPor(
                                paginacion,
                                "fecha_presentacion",
                                Paginacion.Direccion.DESCENDENTE)));
    }

    /**
     * La misma pagina y el mismo tamaño que pidio el cliente, con el orden que esa seccion admite.
     *
     * <p><b>El {@code ordenarPor} de la peticion no se propaga, y no es un descuido.</b> Cada
     * seccion se lee de una tabla distinta y cada repositorio valida el campo contra su propia
     * lista blanca: {@code fecha_valor} existe para los asientos y no para los convenios, {@code
     * fecha_emision} para los valores y para nadie mas. Un unico {@code ordenarPor} para seis
     * rejillas dejaria cinco fallando con {@code OrdenNoAdmitido} —o, peor, obligaria a que las
     * seis tablas compartieran nombres de columna—. Quien quiera ordenar una rejilla concreta usa
     * el endpoint de esa pestaña, que si lo admite.
     */
    private static Paginacion ordenadaPor(
            Paginacion pedida, String campo, Paginacion.Direccion direccion) {
        return new Paginacion(pedida.pagina(), pedida.tamano(), campo, direccion);
    }

    /**
     * Recorta la lista ya ordenada a la pagina pedida.
     *
     * <p>En memoria y no en SQL a proposito: {@link ConsultaDeDeudaPublica#deTodoElContribuyente}
     * devuelve la lista completa —para un contribuyente nunca es larga— y el resumen necesita
     * <b>todas</b> las obligaciones para sumar. Pedirlas dos veces, una entera para sumar y otra
     * paginada para listar, serian dos recorridos del libro para responder lo mismo.
     */
    private static <T> Pagina<T> pagina(List<T> todas, Paginacion paginacion) {
        int desde = Math.min(paginacion.desplazamiento(), todas.size());
        int hasta = Math.min(desde + paginacion.tamano(), todas.size());
        return Pagina.de(List.copyOf(todas.subList(desde, hasta)), paginacion, todas.size());
    }

    /**
     * Que tributos entran en la ficha, segun el desplegable «Impresion» del prototipo.
     *
     * <p>Es un filtro de verdad y no un parametro aceptado y descartado: {@code
     * cuenta_corriente_asiento.tributo} distingue {@code PREDIAL} de {@code ARBITRIO}, asi que
     * responder «PREDIAL» con el padron entero —lo que hace ignorar el filtro— le mostraria al
     * usuario arbitrios donde cree estar viendo solo predial. Ese es el criterio con que {@code
     * consulta_valores} rechaza «RECLAMADO» y {@code consulta_resumen_predial} rechaza «Palabra»:
     * un filtro se resuelve o se rechaza, nunca se ignora en silencio cuando ignorarlo
     * <b>amplia</b> el resultado.
     */
    public enum Alcance {

        /** Solo el impuesto predial. */
        PREDIAL(TributoDelLibro.PREDIAL.texto()),

        /**
         * Solo los arbitrios municipales.
         *
         * <p>{@code ARBITRIO} en singular: es como {@code DeterminarArbitrios} asienta el tributo y
         * como lo nombra el {@code CHECK} de {@code determinacion} (V2). El desplegable del
         * prototipo dice «ARBITRIOS», y la traduccion se hace aqui, una vez.
         */
        ARBITRIOS(TributoDelLibro.ARBITRIO.texto()),

        /** Los dos, y con ellos todo lo demas que el contribuyente deba. */
        PREDIAL_Y_ARBITRIOS(null);

        private final @Nullable String tributo;

        Alcance(@Nullable String tributo) {
            this.tributo = tributo;
        }

        /** El tributo por el que filtrar, o {@code null} si no se filtra. */
        public @Nullable String tributo() {
            return tributo;
        }

        boolean incluye(String tributoDeLaObligacion) {
            return tributo == null || tributo.equalsIgnoreCase(tributoDeLaObligacion);
        }

        /**
         * Traduce el texto del desplegable. {@code null} o en blanco es «PREDIAL Y ARBITRIOS», que
         * es lo que la pantalla trae marcado.
         *
         * @throws ProblemaDeNegocio {@code VALIDACION} si el texto no es ninguna de las tres
         */
        public static Alcance de(@Nullable String texto) {
            if (texto == null || texto.isBlank()) {
                return PREDIAL_Y_ARBITRIOS;
            }
            String limpio = texto.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (Alcance alcance : values()) {
                if (alcance.name().equals(limpio)) {
                    return alcance;
                }
            }
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro «Impresion» admite PREDIAL, ARBITRIOS o PREDIAL Y ARBITRIOS: '"
                            + texto
                            + "'");
        }
    }

    /**
     * Lo que la ficha pide.
     *
     * @param codigoContribuyente el codigo que teclea quien atiende
     * @param aLaFecha la fecha de corte con la que se responde todo lo que depende de hoy: la
     *     deuda, el saldo de los convenios y la situacion de los valores. Entra como argumento y no
     *     sale de un {@code now()} (regla 6, regla 9), o dos secciones de la misma ficha podrian
     *     quedar calculadas a dias distintos
     * @param alcance que tributos entran, del desplegable «Impresion»
     */
    public record Criterio(String codigoContribuyente, LocalDate aLaFecha, Alcance alcance) {

        public Criterio {
            Objects.requireNonNull(codigoContribuyente, "La ficha es de un contribuyente");
            codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
            if (codigoContribuyente.isEmpty()) {
                throw new IllegalArgumentException(
                        "El codigo de contribuyente no puede estar vacio");
            }
            Objects.requireNonNull(
                    aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
            Objects.requireNonNull(alcance, "La ficha dice que tributos incluye");
        }
    }

    /**
     * Las cinco cifras del «Resumen de saldos», sumadas por el servidor (RNF-083).
     *
     * <p>Sobre <b>todas</b> las obligaciones del alcance, no sobre la pagina: el resumen de una
     * ficha no puede depender de cuantas filas caben en la rejilla.
     *
     * @param aLaFecha la fecha de corte de las cinco (regla 9, RNF-075)
     * @param obligaciones cuantas obligaciones con deuda se sumaron
     */
    public record ResumenDeSaldos(
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto,
            Dinero total,
            LocalDate aLaFecha,
            int obligaciones) {

        public ResumenDeSaldos {
            Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
            Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
            Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
            Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
            Objects.requireNonNull(total, "El resumen necesita su total");
            Objects.requireNonNull(
                    aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
        }

        /**
         * Suma parte por parte, y el total como suma de las cuatro partes.
         *
         * <p>Nunca como una quinta cifra leida aparte: si el total se calculara por su cuenta,
         * podria no cuadrar con el desglose que esta encima de el en la misma pantalla.
         *
         * <p>Todas las obligaciones vienen de una sola llamada a {@link
         * ConsultaDeDeudaPublica#deTodoElContribuyente}, asi que comparten fecha de corte y
         * sumarlas no mezcla cifras de dias distintos.
         */
        static ResumenDeSaldos de(List<ObligacionPublica> obligaciones, LocalDate aLaFecha) {
            Dinero insoluto = Dinero.CERO;
            Dinero reajuste = Dinero.CERO;
            Dinero interes = Dinero.CERO;
            Dinero gasto = Dinero.CERO;
            for (ObligacionPublica obligacion : obligaciones) {
                insoluto = insoluto.mas(obligacion.insoluto());
                reajuste = reajuste.mas(obligacion.reajuste());
                interes = interes.mas(obligacion.interes());
                gasto = gasto.mas(obligacion.gasto());
            }
            return new ResumenDeSaldos(
                    insoluto,
                    reajuste,
                    interes,
                    gasto,
                    insoluto.mas(reajuste).mas(interes).mas(gasto),
                    aLaFecha,
                    obligaciones.size());
        }

        /**
         * El campo «Estado de la consulta» del prototipo, redactado por el servidor.
         *
         * <p>Aqui y no en la interfaz por lo mismo que las cinco cifras (RNF-083): dos pantallas
         * que lo compongan por su cuenta acaban escribiendo dos frases distintas, y una de las dos
         * olvida la fecha.
         */
        public String estadoDeLaConsulta() {
            if (obligaciones == 0) {
                return "Sin deuda pendiente al " + aLaFecha;
            }
            return obligaciones
                    + (obligaciones == 1 ? " obligacion" : " obligaciones")
                    + " con saldo al "
                    + aLaFecha;
        }
    }

    /**
     * La ficha consolidada: una cabecera y seis rejillas, cada una con su total.
     *
     * <p>Cada seccion viene ya paginada y ordenada por su criterio natural. Quien necesite filtrar
     * dentro de una —por rango de fechas los pagos, por tipo los valores, por estado los convenios—
     * tiene el endpoint dedicado de esa pestaña, que ya implementa esos filtros: duplicarlos aqui
     * serian seis juegos de filtros escritos dos veces.
     *
     * @param contribuyente de quien es la ficha
     * @param aLaFecha la fecha de corte de todo lo que depende de hoy (regla 9)
     * @param resumen las cinco cifras del «Resumen de saldos», ya sumadas
     * @param deudas las obligaciones con saldo, del ejercicio mas reciente al mas antiguo
     * @param pagos los cobros asentados, cronologicos
     * @param altasYBajas los movimientos de deuda, cronologicos
     * @param fraccionamientos los convenios, del mas reciente al mas antiguo
     * @param valores los valores emitidos, del mas reciente al mas antiguo
     * @param declaraciones las declaraciones juradas presentadas, de la mas reciente a la mas
     *     antigua
     */
    public record Ficha(
            ResumenDeContribuyente contribuyente,
            LocalDate aLaFecha,
            ResumenDeSaldos resumen,
            Pagina<ObligacionPublica> deudas,
            Pagina<MovimientoDelLibro> pagos,
            Pagina<MovimientoDelLibro> altasYBajas,
            Pagina<ConvenioDelContribuyente> fraccionamientos,
            Pagina<ValorDelContribuyente> valores,
            Pagina<DeclaracionJurada> declaraciones) {

        public Ficha {
            Objects.requireNonNull(contribuyente, "La ficha es de un contribuyente");
            Objects.requireNonNull(
                    aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
            Objects.requireNonNull(resumen, "La ficha necesita su resumen");
            Objects.requireNonNull(deudas, "Una seccion vacia es una pagina vacia, no null");
            Objects.requireNonNull(pagos, "Una seccion vacia es una pagina vacia, no null");
            Objects.requireNonNull(altasYBajas, "Una seccion vacia es una pagina vacia, no null");
            Objects.requireNonNull(
                    fraccionamientos, "Una seccion vacia es una pagina vacia, no null");
            Objects.requireNonNull(valores, "Una seccion vacia es una pagina vacia, no null");
            Objects.requireNonNull(declaraciones, "Una seccion vacia es una pagina vacia, no null");
        }
    }
}
