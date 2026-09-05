package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FichasDelPadron;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository.ResumenDeConciliacion;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La conciliacion catastro-rentas: la grilla de fichas con su estado de conciliacion derivado
 * (ADR-0015, #344).
 *
 * <h2>Por que vive en {@code rentas}</h2>
 *
 * <p>Porque el derivado sale de {@code declaracion_jurada}, que es de este contexto, y {@code
 * catastro} no puede mirarla: dependeria de rentas y {@code verificarArquitectura} rechaza el
 * ciclo. Es el motivo del 422 deliberado que {@code ConsultaController} devolvia hasta este issue,
 * y es el patron exacto de {@code ConsultaPrediosController}, que ya compone catastro y
 * cuentacorriente por sus APIs publicas.
 *
 * <p>Los dos lados entran por puerto publico y solo por ahi: {@link FichasDelPadron} de {@code
 * catastro} y el repositorio propio. Este caso de uso no lee ni una tabla ajena (ARQ-01 §4).
 *
 * <h2>El predicado, entero</h2>
 *
 * <blockquote>
 * Un predio esta <b>conciliado a un ejercicio</b> cuando existe una {@code declaracion_jurada} de
 * ese ejercicio, con {@code predio_id} igual al del predio, en estado {@code PRESENTADA} u {@code
 * OBSERVADA}.
 * </blockquote>
 *
 * <p><b>Y lleva su ejercicio</b> (regla 9, RNF-075): no existe «conciliado», existe {@code
 * conciliadoA(ejercicio)}. La declaracion de 2024 no concilia 2026 —el padron afecto se rehace cada
 * ejercicio— y la respuesta dice a que ejercicio contesta, como toda cifra indica su fecha.
 *
 * <h2>Tres metodos y no un parametro, porque no son la misma consulta</h2>
 *
 * <p>{@link #todas} y {@link #conciliadas} dicen quien esta dentro; {@link #noConciliadas} dice
 * <b>quien falta</b>, que es la lista de los predios que no generan deuda predial —el producto de
 * trabajo de la fiscalizacion de omisos, y en manos equivocadas el mapa de a quien no le va a
 * llegar recibo—. Por eso la tercera va detras de un permiso de fiscalizacion, que comprueba el
 * controlador, y deja rastro en la bitacora: es la unica de las tres que escribe, y tenerlas
 * separadas es lo que permite que las otras dos sigan siendo de solo lectura.
 */
@Service
public class ConsultaDeConciliacion {

    /**
     * La tabla que se anota en la bitacora cuando alguien pide la lista de los que faltan.
     *
     * <p>Es {@code declaracion_jurada} y no la pantalla: lo que la consulta atraviesa es el padron
     * de declaraciones, y quien audite «quien miro las declaraciones» tiene que encontrar tambien a
     * quien las miro <b>por su ausencia</b>.
     */
    private static final String TABLA_AUDITADA = "declaracion_jurada";

    private final FichasDelPadron fichas;
    private final DeclaracionJuradaRepository declaraciones;
    private final ConciliacionRepository recuento;
    private final Auditoria auditoria;
    private final Clock reloj;

    public ConsultaDeConciliacion(
            FichasDelPadron fichas,
            DeclaracionJuradaRepository declaraciones,
            ConciliacionRepository recuento,
            Auditoria auditoria,
            Clock reloj) {
        this.fichas = fichas;
        this.declaraciones = declaraciones;
        this.recuento = recuento;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cuantos predios hay, cuantos declararon y cuantos no, <b>sin recorrerlos</b> (#564).
     *
     * <p>La grilla no sirve para contar y lo decia su propio javadoc: el filtro se aplica sobre la
     * pagina y {@code totalElementos} sigue siendo el del padron sin filtrar. Medido sobre
     * Catacaos, los tres filtros contestaban 14 422 —el padron entero—, y el panel de Catastro
     * dibujaba con esa cifra «Predios sin conciliar: 14 422» encima de «14 422 predios en el
     * padron»: una acusacion de omision a todo el distrito que ninguna de las dos cifras pretendia
     * hacer.
     *
     * <h2>Este recuento NO deja rastro en la bitacora, y ese es el motivo</h2>
     *
     * <p>{@link #noConciliadas} si lo deja, porque <b>nombra</b>: es la lista de los predios que no
     * generan deuda predial, y en manos equivocadas el mapa de a quien no le va a llegar recibo
     * (ADR-0015 §2.3). Un recuento no nombra a nadie —dice cuantos, no cuales—, asi que auditar
     * cada pintada del panel llenaria la bitacora de filas que no responden a la pregunta que la
     * bitacora existe para responder, y de paso haria que una pantalla de solo lectura escribiera.
     *
     * <p>Por lo mismo no exige el permiso de fiscalizacion: quien puede abrir la consulta de fichas
     * puede saber cuantas hay.
     *
     * <p><b>Sin criterio</b>, y a proposito: la pregunta del panel es sobre el padron, no sobre una
     * busqueda. Aceptar los filtros de la grilla obligaria a que esta consulta repitiera el {@code
     * WHERE} de aquella, y dos copias de la misma poblacion divergen — que es exactamente el
     * defecto que este metodo viene a cerrar, un escalon mas abajo.
     */
    @Transactional(readOnly = true)
    public ResumenDeConciliacion resumen(Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(ejercicio, "El recuento necesita el ejercicio (regla 9)");
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");
        return recuento.contar(ejercicio, aLaFecha);
    }

    /** El filtro «Todas»: la grilla entera, cada fila con su estado de conciliacion. */
    @Transactional(readOnly = true)
    public Pagina<FichaConciliada> todas(
            BusquedaDeFichas criterio,
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            Paginacion paginacion) {
        return resolver(criterio, ejercicio, aLaFecha, paginacion, null);
    }

    /** El filtro «Si»: solo los predios que declararon ese ejercicio. */
    @Transactional(readOnly = true)
    public Pagina<FichaConciliada> conciliadas(
            BusquedaDeFichas criterio,
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            Paginacion paginacion) {
        return resolver(criterio, ejercicio, aLaFecha, paginacion, true);
    }

    /**
     * El filtro «No»: los predios sin declaracion jurada de ese ejercicio, <b>con su rastro</b>
     * (ADR-0015 §2.3).
     *
     * <p>Escribe una fila en la bitacora con operacion {@code ACCESO} —el valor ya existe en {@code
     * auditoria_operacion_check} (V5) y la pantalla {@code auditoria} ya lo muestra— dentro de
     * <b>la misma transaccion</b> que la lectura: si la consulta falla, no queda constancia de algo
     * que no paso; si la constancia no se puede escribir, la consulta no se responde.
     *
     * <p>La observacion la compone el sistema y no el usuario, porque aqui no hay usuario que
     * observe: nadie escribe un motivo para mirar una grilla. Es la excepcion que {@code
     * ConObservacionEnLasEscrituras.SIN_USUARIO_QUE_OBSERVE} nombra con este metodo y su porque.
     *
     * <p>El permiso de fiscalizacion lo comprueba el controlador, que es quien conoce al usuario en
     * curso: sin el, esta fila no se llega a escribir.
     */
    @Transactional
    public Pagina<FichaConciliada> noConciliadas(
            BusquedaDeFichas criterio,
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        Objects.requireNonNull(ejercicio, "La conciliacion necesita el ejercicio (regla 9)");
        Objects.requireNonNull(paginacion, "La consulta necesita su paginacion");

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                // Del reloj inyectado, no de aLaFecha: la particion de la
                                // bitacora es el ejercicio del ACTO, y consultar en 2026 el
                                // padron de 2024 es un acto de 2026.
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                "conciliacion=NO;ejercicio=" + ejercicio.valor(),
                                Operacion.ACCESO,
                                Observacion.de(
                                        "Consulta de los predios sin declaracion jurada del"
                                                + " ejercicio "
                                                + ejercicio.valor()
                                                + " (conciliadaConRentas=No, ADR-0015)"))
                        // Solo cifras: el criterio lleva texto del usuario y componer JSON con el
                        // a mano acabaria en una comilla que rompe el cast a jsonb.
                        .con(
                                null,
                                "{\"conciliadaConRentas\":\"NO\",\"ejercicio\":"
                                        + ejercicio.valor()
                                        + ",\"pagina\":"
                                        + paginacion.pagina()
                                        + "}"));

        return resolver(criterio, ejercicio, aLaFecha, paginacion, false);
    }

    // ------------------------------------------------------------------

    /**
     * Una fila de la grilla con su conciliacion resuelta.
     *
     * <p>Lleva la ficha tal como catastro la publica —sin el identificador del titular, ADR-0015
     * §2.4— y del padron de rentas <b>solo el derivado y su ejercicio</b>: ni el numero de la
     * declaracion, ni su tipo, ni sus importes, ni quien la presento, ni su identificador. Es la
     * misma linea que {@code ConsultaDeDeudaPublica} traza para la deuda: el importe, no los
     * asientos.
     *
     * @param conciliadaA el ejercicio al que responde {@code conciliada}, siempre presente: la
     *     columna de la pantalla se rotula con el (regla 9)
     */
    public record FichaConciliada(FichaDelPadron ficha, boolean conciliada, Ejercicio conciliadaA) {

        public FichaConciliada {
            Objects.requireNonNull(ficha, "La fila de la conciliacion lleva su ficha");
            Objects.requireNonNull(conciliadaA, "No hay «conciliada»: hay conciliadaA(ejercicio)");
        }
    }

    /**
     * La pagina de catastro, ya <b>acotada por el filtro</b> y anotada con el derivado (#631).
     *
     * <h2>Lo que cambio, y por que la version anterior mentia</h2>
     *
     * <p>Hasta #631 el filtro se aplicaba <b>sobre la pagina ya devuelta</b> y {@code
     * totalElementos} seguia siendo el del padron sin filtrar. Medido sobre Catacaos: {@code
     * conciliadaConRentas=Si} contestaba «722 paginas, 14 422 elementos» y <b>cero filas en
     * todas</b>. El pie de la grilla habria dicho «0 de 14 422», que se lee como «hay 14 422 y no
     * te caben» y no como «no hay ninguna».
     *
     * <p>Ahora el conjunto de predios que declararon el ejercicio se resuelve <b>una vez</b> y
     * viaja a catastro como {@link AcotacionPorPredio}, de modo que la condicion entra en el
     * <b>mismo</b> {@code WHERE} que ya se ejecutaba: la pagina y el {@code count(*)} salen de la
     * misma consulta y no pueden separarse.
     *
     * <p><b>La alternativa era duplicar la grilla en rentas</b> —leyendo {@code predio}, {@code
     * ficha_catastral} y {@code titularidad} por su cuenta— y es lo que el javadoc de {@code
     * FichasDelPadron} descarta desde #344: duplicaria en otro SQL, que envejeceria aparte, la
     * resolucion de la version vigente a una fecha. Lo que cuesta la salida elegida esta dicho en
     * {@link AcotacionPorPredio}: los identificadores viajan por el proceso, una vez por peticion.
     *
     * <p>{@link #resumen} sigue existiendo y sigue siendo la forma de contar (#564): recorrer la
     * grilla para llegar a una cifra dejaria 722 filas de bitacora con el filtro «No».
     */
    private Pagina<FichaConciliada> resolver(
            BusquedaDeFichas criterio,
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            Paginacion paginacion,
            @Nullable Boolean soloConciliadas) {

        Objects.requireNonNull(ejercicio, "La conciliacion necesita el ejercicio (regla 9)");
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");

        Set<Long> conciliados =
                soloConciliadas == null
                        ? Set.of()
                        : declaraciones.prediosConDeclaracionVigente(ejercicio);
        BusquedaDeFichas acotado =
                soloConciliadas == null
                        ? criterio
                        : criterio.acotadaA(
                                soloConciliadas
                                        ? AcotacionPorPredio.soloEstos(conciliados)
                                        : AcotacionPorPredio.todosMenosEstos(conciliados));

        Pagina<FichaDelPadron> pagina = fichas.buscar(acotado, aLaFecha, paginacion);
        if (pagina.estaVacia()) {
            return new Pagina<>(
                    List.of(), pagina.pagina(), pagina.tamano(), pagina.totalElementos());
        }

        // Con «Todas» hay que preguntar por los predios de la pagina para pintar la columna;
        // con «Si» o «No» ya se sabe, porque el conjunto es el que acoto la consulta.
        Set<Long> delFiltro = conciliados;
        if (soloConciliadas == null) {
            Set<Long> predios = new LinkedHashSet<>();
            for (FichaDelPadron fila : pagina.contenido()) {
                predios.add(fila.predioId());
            }
            delFiltro = declaraciones.prediosConDeclaracionVigente(predios, ejercicio);
        }

        List<FichaConciliada> filas = new ArrayList<>();
        for (FichaDelPadron fila : pagina.contenido()) {
            filas.add(new FichaConciliada(fila, delFiltro.contains(fila.predioId()), ejercicio));
        }

        return new Pagina<>(filas, pagina.pagina(), pagina.tamano(), pagina.totalElementos());
    }
}
