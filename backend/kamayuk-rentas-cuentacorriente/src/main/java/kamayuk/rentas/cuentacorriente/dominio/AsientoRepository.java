package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El libro de asientos (ADR-0006). Ningun metodo recibe la municipalidad (regla 2): sale del token
 * y la aplica la politica RLS.
 *
 * <p><b>No hay {@code update} ni {@code delete}.</b> {@link #registrar} es el unico punto de
 * escritura, y siempre inserta: una correccion es otro asiento, nunca una modificacion del que ya
 * esta (V2, V7).
 */
public interface AsientoRepository {

    Optional<Asiento> findById(long id);

    /**
     * El estado de cuenta que pide el criterio, paginado.
     *
     * <p>Sin nombre que sugiera «busqueda»: no hay aproximacion aqui, todos los filtros son
     * igualdad. La aproximacion es cosa del padron de {@code contribuyentes}.
     */
    Pagina<Asiento> buscar(CriterioDeConsulta criterio, Paginacion paginacion);

    /**
     * Los asientos de <b>una</b> obligacion, hasta la fecha de corte del criterio (RF-041, RF-042).
     *
     * <p>Sin paginar: una obligacion tiene pocos asientos —no el libro completo de un
     * contribuyente—, y {@link CalculoDeDeuda#deudaActualizadaA} necesita verlos todos para netear
     * cargos contra abonos, no una pagina de ellos.
     */
    List<Asiento> paraDeuda(CriterioDeDeuda criterio);

    /**
     * Los movimientos de alta y baja de deuda que pide el criterio, paginados (RF-045).
     *
     * <p>Ver {@link CriterioDeAltasBajas} para que cuenta como movimiento de deuda y que no.
     */
    Pagina<Asiento> altasYBajas(CriterioDeAltasBajas criterio, Paginacion paginacion);

    /**
     * El historial de pagos que pide el criterio, paginado (RF-048, #25).
     *
     * <p>Ver {@link CriterioDePagos} para que cuenta como pago y que no.
     */
    Pagina<Asiento> pagos(CriterioDePagos criterio, Paginacion paginacion);

    /**
     * <b>Todos</b> los asientos de una obligacion, sin filtro de fecha (#23).
     *
     * <p>Es lo que la reconstruccion del saldo proyectado recorre: reconstruir a una fecha de corte
     * daria un saldo que no es el del libro, y la conciliacion lo leeria como divergencia.
     */
    List<Asiento> deLaObligacion(ClaveDeSaldo clave);

    /**
     * Los asientos de <b>todos los periodos</b> de una obligacion, sin filtro de fecha (#598).
     *
     * <p>Es {@link #deLaObligacion} sin la cuota: lo que la grilla de {@code consulta_deuda} agrupa
     * en <b>una</b> fila. Existe porque una baja sobre esa fila tiene que repartirse entre los
     * periodos que la componen, y para repartir hay que saber cuanto debe cada uno — algo que solo
     * sabe el servidor: {@code ObligacionConDeudaResource} publica el total del grupo y no el
     * importe de cada periodo.
     *
     * <p>Una sola consulta y no una por cuota: son doce como mucho, pero doce consultas por acto se
     * convierten en doce mil en una corrida.
     */
    List<Asiento> deTodosLosPeriodosDe(ClaveDeObligacion clave);

    /**
     * Los asientos que un documento origino y que <b>todavia no son una reversion</b> (#34).
     *
     * <p>Es como la anulacion de un recibo encuentra lo que tiene que deshacer: la cobranza dejo el
     * numero del recibo en {@code documento_origen} de cada asiento que escribio (#33), y eso es lo
     * unico que los relaciona entre si. Sin paginar: son los asientos de <b>una</b> cobranza.
     *
     * <p>El filtro {@code asiento_reversado_id IS NULL} es una segunda barrera, no la principal: la
     * primera es que la reversion se asienta con un documento de origen <b>distinto</b> ({@code
     * RegistroDeAbonos#reversarAbonos} lo exige). Aun asi se filtra, porque el precio es cero y lo
     * que evita es que una reversion pueda encontrarse a si misma si algun dia alguien reutilizara
     * el mismo texto.
     *
     * <p>Recorre todas las particiones del libro: un abono se imputa al ejercicio de la
     * <b>obligacion</b>, no al de la fecha de pago, asi que un solo recibo puede haber tocado
     * varias. {@code asiento_documento_origen_ix} (V30) es lo que hace que eso sea una lectura de
     * indice por particion y no un recorrido completo.
     */
    List<Asiento> porDocumentoOrigen(String documentoOrigen);

    /**
     * Lo que cada uno de esos documentos <b>sigue</b> abonando, en una sola consulta (#36).
     *
     * <p>Es como el cierre de caja cuadra contra el libro. {@link #porDocumentoOrigen} sirve para
     * uno; esto sirve para los de un turno entero, que pueden ser cientos, y hacerlo documento por
     * documento serian cientos de viajes al motor por cada arqueo.
     *
     * <p>«Sigue» es la palabra que importa. Solo se suman los asientos de tipo {@code ABONO} —el
     * cargo con el que la cobranza cristaliza el devengo no es dinero que entro— y solo los que
     * <b>nadie ha reversado</b>. Un recibo anulado conserva sus asientos, porque no se borran, se
     * reversan (V2): preguntar «cuanto abono» devolveria el importe de un recibo que ya no vale.
     *
     * <p>La consecuencia util es que quien pregunta no tiene que saber que documento reversa a que
     * otro, ni que la reversion de un abono se escribe como cargo. Ese conocimiento se queda aqui.
     *
     * <p>Un documento que no asento nada <b>no aparece</b> en el mapa. Distinguir «cero» de «no
     * estaba» es cosa de quien pregunta: {@code AbonadoEnElLibro} lo resuelve devolviendo cero.
     */
    java.util.Map<String, kamayuk.rentas.dominio.Dinero> abonadoPorDocumento(
            Collection<String> documentosOrigen);

    /**
     * El identificador del contribuyente con ese codigo, si existe en esta municipalidad.
     *
     * <p>Vive aqui y no en un repositorio del contexto {@code contribuyentes} por lo mismo que el
     * cruce de {@link #buscar}: se resuelve en SQL contra una tabla con la que ya hay clave
     * foranea, sin conocer ningun tipo de ese contexto (ARQ-01 §4 regla 2). Las dos tablas
     * comparten politica RLS, asi que la busqueda no se sale del tenant.
     */
    Optional<Long> contribuyentePorCodigo(String codigo);

    /**
     * Lo cobrado de esos tributos entre las dos fechas, agrupado por tributo, ejercicio, mes y fase
     * (#53, RF-073).
     *
     * <p>Los mismos dos filtros que {@link #abonadoPorDocumento}, y por el mismo motivo: solo los
     * {@code ABONO} —el cargo con que la cobranza cristaliza el devengo no es dinero que entro— y
     * solo los que <b>nadie ha reversado</b>, porque un recibo anulado conserva sus asientos.
     * Ademas, solo los cuatro conceptos con los que una cobranza imputa el pago —insoluto,
     * reajuste, interes y gasto—: los otros abonos mueven deuda, no la cobran.
     *
     * <p>La agregacion la hace el motor. Traer los asientos para sumarlos en Java significaria
     * traer todos los pagos del periodo para escribir doce cifras.
     *
     * @return una linea por grupo con movimiento; vacia si no se cobro nada o si {@code tributos}
     *     lo esta
     */
    List<RecaudacionAgregada> recaudadoPorTributo(
            Collection<String> tributos, java.time.LocalDate desde, java.time.LocalDate hasta);

    /**
     * Lo cobrado de <b>todos</b> los tributos entre las dos fechas, con el mismo desglose y los
     * mismos filtros que {@link #recaudadoPorTributo} (#56, RF-130).
     *
     * <p>Es un metodo aparte y no una lista vacia interpretada como «todos». El contrato de {@link
     * #recaudadoPorTributo} dice que la coleccion vacia devuelve la respuesta vacia, y eso es lo
     * correcto alli: quien pregunta por los tributos de un area y resulta que el area no tiene
     * ninguno debe recibir cero, no el padron entero. Reutilizar el mismo metodo con dos
     * significados opuestos para el mismo argumento es como se escriben los defectos que nadie ve
     * al revisar.
     *
     * <p>Sin filtro de tributo, la <b>unica</b> cota es el rango de fechas, y por eso este metodo
     * existe para un panel y no para un listado: lo que devuelve es una linea por (tributo,
     * ejercicio, mes, fase) con movimiento, que crece con el numero de grupos y no con el del
     * padron.
     */
    List<RecaudacionAgregada> recaudadoDeTodos(
            java.time.LocalDate desde, java.time.LocalDate hasta);

    /**
     * Lo cargado en un ejercicio, agrupado por tributo (#56, RF-130).
     *
     * <p>El reverso de {@link #recaudadoPorTributo}: {@code CARGO} en lugar de {@code ABONO}, y
     * solo el concepto {@code INSOLUTO} —el tributo puesto a cobrar, sin el reajuste, el interes ni
     * los gastos que se le anadieron despues—. El mismo criterio de reversion que el otro lado,
     * para que las dos cifras se puedan dividir sin explicar nada.
     *
     * <p>Y una condicion mas que el otro lado no necesita: <b>un asiento que es la reversion de
     * otro no cuenta como cargo</b>. Reversar un abono produce un cargo del mismo concepto, asi que
     * un recibo anulado dejaria «lo cargado» inflado por su propio importe. No es deuda nueva: es
     * la deuda de siempre, que vuelve a estar viva.
     *
     * <p>La agregacion la hace el motor, por lo mismo de siempre: los cargos de un ejercicio son el
     * padron entero.
     */
    List<CargoAgregado> cargadoPorTributo(kamayuk.rentas.dominio.Ejercicio ejercicio);

    /**
     * Lo que sigue pendiente en el ejercicio <b>a la fecha de corte</b>, agrupado por tributo
     * (#639, RF-130).
     *
     * <h2>Es la misma definicion que {@code consulta_deuda}, sumada</h2>
     *
     * <p>Netea {@link Concepto#INSOLUTO} —cargos menos abonos— <b>de los asientos cuya fecha valor
     * no pasa del corte</b>, agrupando por obligacion. Eso es exactamente lo que hace {@link
     * CalculoDeDeuda#deudaActualizadaA} para una obligacion, escrito una sola vez en SQL para poder
     * aplicarlo al padron entero. Que las dos lecturas apliquen la misma regla es lo que {@code
     * CarteraCuadraConLaConsultaJdbcTest} comprueba obligacion por obligacion.
     *
     * <p><b>Y por eso no lleva el filtro de reversion que si lleva {@link #cargadoPorTributo}.</b>
     * Netear se corrige solo: la reversion de un abono es un cargo del mismo concepto y del mismo
     * importe, asi que el par suma cero. {@code cargadoPorTributo} mira <b>un solo lado</b> del
     * libro y por eso necesita las dos mitades de ese filtro.
     *
     * <p>Aqui las dos mitades juntas serian <b>inertes</b> —se midio: quitan el cargo de la
     * reversion y el abono reversado, y el neto no cambia—, pero <b>media</b> es un defecto: solo
     * con {@code asiento_reversado_id IS NULL} se va el cargo de la reversion y se queda el abono
     * que reversa, y la cartera sale <b>mas baja</b> —medido, 280,00 donde el libro dice 400,00—.
     * Un filtro que no hace falta y que a medias resta deuda viva es un filtro que no se pone.
     *
     * <p>El grupo es la <b>obligacion</b> —tributo, ejercicio y unidad—, no la cuota, por lo mismo:
     * es el grupo con el que {@code consulta_deuda} publica una fila. Agrupando por cuota, un
     * contribuyente que pago de mas la cuota 1 y debe la 2 apareceria con el importe de la 2, y en
     * la consulta con la diferencia.
     *
     * <p>Se cuentan solo las obligaciones con insoluto <b>positivo</b>: una en cero esta cancelada,
     * y una negativa es un pago en exceso —un hecho del libro, pero no cartera por cobrar; restarlo
     * taparia con el saldo a favor de uno la deuda de otro—.
     *
     * @param ejercicio de que ejercicio son las obligaciones
     * @param aLaFecha la fecha de corte: ningun asiento posterior entra (regla 9, RNF-075)
     */
    List<PendienteAgregado> pendientePorTributo(
            kamayuk.rentas.dominio.Ejercicio ejercicio, java.time.LocalDate aLaFecha);

    /** Todos los asientos de un contribuyente, para reconstruir sus saldos de una vez (#23). */
    List<Asiento> deContribuyente(long contribuyenteId);

    /**
     * Los contribuyentes con al menos un asiento, en orden de identificador, desde {@code
     * despuesDe} y como mucho {@code cuantos}.
     *
     * <p>La forma —cursor por identificador, no {@code OFFSET}— es lo que hace <b>reanudable</b> la
     * reconstruccion masiva (#23): el proceso guarda el ultimo identificador que termino y sigue
     * desde ahi, sin recorrer otra vez lo hecho y sin saltarse a nadie si entretanto entra un
     * contribuyente nuevo.
     */
    List<Long> contribuyentesConAsientos(long despuesDe, int cuantos);

    /**
     * Los ejercicios en los que el libro <b>puede</b> asentar, de menor a mayor (#597).
     *
     * <p>{@code cuenta_corriente_asiento} esta particionada por ejercicio (V2) y las particiones se
     * declaran una a una en las migraciones. Un {@code INSERT} de un ejercicio sin particion no
     * devuelve vacio ni escribe en ningun sitio: <b>falla</b>, con «no partition of relation found
     * for row» y SQLSTATE {@code 23514} —el mismo que una violacion de {@code CHECK}, asi que
     * atraparlo por el codigo de error confundiria las dos—.
     *
     * <p>Se pregunta antes de escribir para que el borde pueda contestar {@code 422} nombrando el
     * ejercicio en vez de un {@code 500} con incidencia, que dice «vuelve a intentarlo» sobre algo
     * que no va a cambiar hasta que alguien escriba una migracion.
     *
     * <p>No es «el ejercicio esta abierto» en sentido tributario —eso es otra cosa, y no vive
     * aqui—: es literalmente donde hay sitio en el libro.
     */
    List<Ejercicio> ejerciciosAsentables();

    /**
     * Las grafias de {@code tributo} presentes en el libro que <b>no</b> estan en {@link
     * kamayuk.rentas.cuentacorriente.TributoDelLibro}, sin repetir y ordenadas (#553).
     *
     * <p>Existe porque esas filas no se pueden corregir y hay que poder <b>verlas</b>. El libro no
     * admite {@code UPDATE} ni {@code DELETE} desde la aplicacion (V7, regla 4) y el migrador
     * tampoco puede reescribirlas —corre sin contexto de tenant, y un {@code UPDATE} sobre una
     * tabla con RLS muere con «unrecognized configuration parameter» (DAT-01 §0, medido igual en
     * V64)—. Lo unico que el sistema puede hacer con una obligacion escrita como {@code ARBITRIOS}
     * es decir que esta ahi: no aparece con la de {@code ARBITRIO} en ninguna consulta, y sin esta
     * lectura nadie sabria por que.
     *
     * <p>Devuelve la lista vacia en una instalacion sana, que es el caso corriente desde V74.
     */
    List<String> tributosFueraDelVocabulario();

    /**
     * Inserta el asiento y devuelve la fila guardada, con su {@code id} y su {@code usuarioId}.
     *
     * @throws AltaYaAsentada si el asiento es de un alta de deuda que ya esta en el libro con el
     *     mismo sustento (#588). Lo decide {@code asiento_alta_unica_uq} (V75), no un {@code if}
     */
    Asiento registrar(Asiento asiento);

    /**
     * Ya hay en el libro un alta de esta obligacion con este sustento y este concepto (#588).
     *
     * <h2>Quien lo garantiza, y por que no hay ninguna comprobacion previa</h2>
     *
     * <p>Lo garantiza {@code asiento_alta_unica_uq} (V75), un indice unico parcial sobre {@code
     * cuenta_corriente_asiento}. Esta excepcion <b>no</b> es una guarda: es la traduccion del
     * choque al vocabulario del dominio, hecha en el unico sitio donde se sabe con certeza que el
     * {@code INSERT} que fallo fue el del asiento y no otro —{@code documento_numero_uq} (V15)
     * tambien lanza {@code DuplicateKeyException} y significa algo completamente distinto—.
     *
     * <p>Y no hay un {@code SELECT} previo a proposito. No anadiria nada: el mensaje se compone con
     * el asiento que se iba a escribir, asi que ya nombra la obligacion, la cuota, el concepto y el
     * sustento; no evita gastar nada, porque el documento se emite <b>despues</b> de los asientos;
     * y entre leer y escribir cabe otra peticion, de modo que dos envios simultaneos lo pasarian
     * los dos. Es la leccion de #188 —«la regla la sostiene la restriccion y no el codigo»— llevada
     * un paso mas alla: alli la guarda de Java quedo documentada como inutil, aqui no se escribe.
     */
    final class AltaYaAsentada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AltaYaAsentada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
