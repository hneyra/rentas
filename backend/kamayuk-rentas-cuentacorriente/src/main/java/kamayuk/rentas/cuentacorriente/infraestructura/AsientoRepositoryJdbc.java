package kamayuk.rentas.cuentacorriente.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.ActoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CargoAgregado;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeConsulta;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.PendienteAgregado;
import kamayuk.rentas.cuentacorriente.dominio.RecaudacionAgregada;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El libro contra PostgreSQL. Solo {@code SELECT} e {@code INSERT}: {@code kamayuk_app} no tiene
 * mas privilegios sobre {@code cuenta_corriente_asiento} (V7), y el escaner de fuentes rechaza
 * cualquier {@code UPDATE} escrito aqui por error, ademas de la propia base.
 *
 * <p>{@link #buscar} cruza con {@code contribuyente} para resolver el codigo de la ruta a un
 * identificador, en SQL: es la unica dependencia con ese contexto, y no es una dependencia de Java,
 * asi que Spring Modulith no la ve como tal (ARQ-01 §4 regla 2). Las dos tablas comparten politica
 * RLS por {@code municipalidad_id}, asi que el cruce no se sale del tenant.
 */
@Repository
public class AsientoRepositoryJdbc extends RepositorioJdbc implements AsientoRepository {

    /**
     * Memoria de {@link #ejerciciosAsentables()}; ver su javadoc para por que se puede memorizar.
     */
    private volatile @Nullable List<Ejercicio> particiones;

    private static final String COLUMNAS =
            "a.id, a.ejercicio, a.contribuyente_id, a.tributo, a.concepto, a.tipo, a.fase,"
                    + " a.periodo, a.predio_id, a.vehiculo_id, a.referencia_externa, a.monto,"
                    + " a.fecha_valor, a.documento_origen, a.asiento_reversado_id, a.usuario_id,"
                    + " a.motivo, a.acto, a.unidad_de_titular_anterior, a.causal";

    private static final String DESDE = " FROM cuenta_corriente_asiento a";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_valor", "ejercicio", "monto", "id");

    public AsientoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Asiento> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE a.id = :id")
                .param("id", id)
                .query(AsientoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Asiento> buscar(CriterioDeConsulta criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("c.codigo_contribuyente = :codigo");
        parametros.put("codigo", criterio.codigoContribuyente());

        if (criterio.ejercicio() != null) {
            condiciones.add("a.ejercicio = :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio().valor());
        }
        if (criterio.tributo() != null) {
            condiciones.add("a.tributo = :tributo");
            parametros.put("tributo", criterio.tributo());
        }
        if (criterio.fase() != null) {
            condiciones.add("a.fase = :fase");
            parametros.put("fase", criterio.fase().name());
        }

        String desdeConContribuyente = DESDE + " JOIN contribuyente c ON c.id = a.contribuyente_id";
        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + desdeConContribuyente + donde,
                "SELECT count(*)" + desdeConContribuyente + donde,
                parametros,
                paginacion,
                ORDEN,
                AsientoRepositoryJdbc::mapear);
    }

    @Override
    public List<Asiento> paraDeuda(CriterioDeDeuda criterio) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("c.codigo_contribuyente = :codigo");
        parametros.put("codigo", criterio.codigoContribuyente());
        condiciones.add("a.tributo = :tributo");
        parametros.put("tributo", criterio.tributo());
        condiciones.add("a.ejercicio = :ejercicio");
        parametros.put("ejercicio", criterio.ejercicio().valor());
        condiciones.add("a.fecha_valor <= :fecha");
        parametros.put("fecha", criterio.fecha());

        if (criterio.periodo() != null) {
            condiciones.add("a.periodo = :periodo");
            parametros.put("periodo", criterio.periodo());
        }
        if (criterio.predioId() != null) {
            condiciones.add("a.predio_id = :predioId");
            parametros.put("predioId", criterio.predioId());
        }
        if (criterio.vehiculoId() != null) {
            condiciones.add("a.vehiculo_id = :vehiculoId");
            parametros.put("vehiculoId", criterio.vehiculoId());
        }
        if (criterio.fase() != null) {
            condiciones.add("a.fase = :fase");
            parametros.put("fase", criterio.fase().name());
        }
        if (criterio.concepto() != null) {
            condiciones.add("a.concepto = :concepto");
            parametros.put("concepto", criterio.concepto().name());
        }

        String desdeConContribuyente = DESDE + " JOIN contribuyente c ON c.id = a.contribuyente_id";
        String donde = " WHERE " + String.join(" AND ", condiciones);

        return jdbc().sql("SELECT " + COLUMNAS + desdeConContribuyente + donde)
                .params(parametros)
                .query(AsientoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<Asiento> deLaObligacion(ClaveDeSaldo clave) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE a.contribuyente_id = :contribuyente"
                                + "   AND a.tributo = :tributo"
                                + "   AND a.ejercicio = :ejercicio"
                                + "   AND COALESCE(a.periodo, 0) = :periodo"
                                + "   AND COALESCE(a.predio_id, 0) = :predio"
                                + "   AND COALESCE(a.vehiculo_id, 0) = :vehiculo"
                                + " ORDER BY a.id")
                .param("contribuyente", clave.contribuyenteId())
                .param("tributo", clave.tributo())
                .param("ejercicio", clave.ejercicio().valor())
                .param("periodo", clave.periodo())
                .param("predio", clave.predioId() == null ? 0L : clave.predioId())
                .param("vehiculo", clave.vehiculoId() == null ? 0L : clave.vehiculoId())
                .query(AsientoRepositoryJdbc::mapear)
                .list();
    }

    /**
     * Igual que {@link #deLaObligacion}, sin la condicion del periodo (#598).
     *
     * <p>El orden es por periodo y despues por identificador: el reparto de una baja recorre las
     * cuotas de la primera a la ultima, y ese orden es parte de lo que la prueba comprueba.
     */
    @Override
    public List<Asiento> deTodosLosPeriodosDe(ClaveDeObligacion clave) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE a.contribuyente_id = :contribuyente"
                                + "   AND a.tributo = :tributo"
                                + "   AND a.ejercicio = :ejercicio"
                                + "   AND COALESCE(a.predio_id, 0) = :predio"
                                + "   AND COALESCE(a.vehiculo_id, 0) = :vehiculo"
                                + " ORDER BY COALESCE(a.periodo, 0), a.id")
                .param("contribuyente", clave.contribuyenteId())
                .param("tributo", clave.tributo())
                .param("ejercicio", clave.ejercicio().valor())
                .param("predio", clave.predioId() == null ? 0L : clave.predioId())
                .param("vehiculo", clave.vehiculoId() == null ? 0L : clave.vehiculoId())
                .query(AsientoRepositoryJdbc::mapear)
                .list();
    }

    /**
     * La relacion de altas y bajas de un contribuyente (RF-045), que son <b>actos</b> y no todo el
     * libro (#640).
     *
     * <h2>Lo que queda fuera, y por que</h2>
     *
     * <p>Un cobro de ventanilla no es una baja de deuda ni el cargo de la emision masiva es un
     * alta, aunque los tres se escriban con los mismos conceptos del desglose. Se acota por {@code
     * acto IS NOT NULL} porque es lo unico que los separa: un asiento sin acto no es uno del que se
     * ignore el origen, es uno que <b>no nacio</b> de un alta ni de una baja (V68 §2).
     *
     * <p>Los sitios que estampan la columna son <b>dos</b> desde #662: {@link
     * kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda#enAsientos} —los actos de RF-043 y
     * RF-044, tecleados en Rentas— y {@code ExtincionDeDeudaCuentaCorriente}, que asienta la misma
     * baja cuando la ordena una resolucion de gerencia (#50, RF-064). Antes de #662 esa segunda
     * baja no salia aqui, que es tanto como decir que la pantalla del control se saltaba la via por
     * la que se extingue deuda con mas consecuencias.
     *
     * <h2>Los asientos anteriores a V68, dicho en vez de callado</h2>
     *
     * <p>Los que existian antes de esa migracion tienen {@code acto} nulo y <b>no se pueden
     * reparar</b>: el libro no admite {@code UPDATE} (V7, regla 4) y el migrador no puede
     * reescribir una tabla con {@code FORCE ROW LEVEL SECURITY} porque corre sin contexto de tenant
     * (DAT-01 §0 cuarto hallazgo, medido igual en V64). Asi que <b>una baja anterior a V68 no sale
     * en esta relacion</b>, y no hay forma de distinguirla de un cobro del mismo dia.
     *
     * <p><b>No se acota por fecha</b>, que era la otra salida: haria falta saber cuando se aplico
     * V68 en <i>esta</i> instalacion —cada una la aplica el dia que despliega—, y ese dato no esta
     * en ninguna tabla que la aplicacion pueda leer. Un corte inventado dejaria fuera bajas buenas
     * o dentro cobros, con el mismo aspecto en los dos casos. Se dice, en cambio, en la descripcion
     * que el contrato publica de la operacion, que es lo que llega a quien lee la pantalla (#312).
     */
    @Override
    public Pagina<Asiento> altasYBajas(CriterioDeAltasBajas criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("c.codigo_contribuyente = :codigo");
        parametros.put("codigo", criterio.codigoContribuyente());
        // EL filtro de esta consulta, y el unico (#640). Acotar por los cuatro conceptos
        // del desglose no separaba nada: el abono de una baja y el de una COBRANZA son
        // columna a columna el mismo asiento —ABONO de concepto INSOLUTO—, asi que la
        // pantalla que existe para auditar las altas y bajas listaba como baja cada pago
        // de ventanilla, y como alta el cargo de la emision masiva y el que cristaliza el
        // interes al cobrar. Lo que distingue un acto de un movimiento cualquiera es de
        // que nace, y desde V68 el libro lo guarda.
        condiciones.add("a.acto IS NOT NULL");

        if (criterio.ejercicio() != null) {
            condiciones.add("a.ejercicio = :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio().valor());
        }
        if (criterio.tributo() != null) {
            condiciones.add("a.tributo = :tributo");
            parametros.put("tributo", criterio.tributo());
        }
        if (criterio.causal() != null) {
            // El filtro de #684, y el que la pantalla de RF-045 no tenia: «ensename las
            // bajas por prescripcion». Acota por la COLUMNA, no por el texto de la
            // observacion: hasta V77 la causal viajaba ahi dentro y «PRESCRIPCION
            // DECLARADA», «prescripción declarada» y «prescrita s/ Res. 123-2026» eran la
            // misma causal en tres cadenas distintas.
            //
            // Solo una baja tiene causal (asiento_causal_del_acto_ck), asi que este filtro
            // deja fuera las altas por si mismo — y tambien las bajas anteriores a V77, que
            // la tienen en nulo y no se pueden reparar (V7, regla 4). Sin filtro salen
            // todas, que es lo que la relacion hacia antes y sigue haciendo.
            condiciones.add("a.causal = :causal");
            parametros.put("causal", criterio.causal().name());
        }
        if (criterio.sentido() != null) {
            // Un alta incorpora deuda (CARGO) y una baja la extingue (ABONO): es la
            // misma equivalencia que MovimientoDeDeuda#enAsientos escribe al asentar.
            //
            // Se acota por `tipo` y NO por `acto`, y a proposito: la columna «A/B» de la
            // pantalla dibuja el tipo del asiento, asi que filtrar por el acto dejaria el
            // filtro diciendo una cosa y la columna otra —dos verdades sobre la misma
            // fila, y la que se lee no seria la que filtro (#397)—. Donde se ve es en la
            // REVERSION de una baja: copia el acto BAJA_DEUDA y es un CARGO, porque
            // devuelve la deuda al padron; sale bajo «Alta», que es lo que su tipo dice.
            condiciones.add("a.tipo = :tipo");
            parametros.put(
                    "tipo",
                    criterio.sentido() == SentidoDelMovimiento.ALTA
                            ? TipoAsiento.CARGO.name()
                            : TipoAsiento.ABONO.name());
        }

        String desdeConContribuyente = DESDE + " JOIN contribuyente c ON c.id = a.contribuyente_id";
        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + desdeConContribuyente + donde,
                "SELECT count(*)" + desdeConContribuyente + donde,
                parametros,
                paginacion,
                ORDEN,
                AsientoRepositoryJdbc::mapear);
    }

    @Override
    public Pagina<Asiento> pagos(CriterioDePagos criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("c.codigo_contribuyente = :codigo");
        parametros.put("codigo", criterio.codigoContribuyente());
        // Un pago es un ABONO de concepto PAGO (ver CriterioDePagos). Los demas abonos no
        // son todos «movimientos de deuda»: el de una cobranza tambien es un ABONO de
        // INSOLUTO, y desde #640 lo que separa un acto de un cobro es `acto`, no el
        // concepto.
        condiciones.add("a.tipo = 'ABONO'");
        condiciones.add("a.concepto = 'PAGO'");

        if (criterio.desde() != null) {
            condiciones.add("a.fecha_valor >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            condiciones.add("a.fecha_valor <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }

        String desdeConContribuyente = DESDE + " JOIN contribuyente c ON c.id = a.contribuyente_id";
        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + desdeConContribuyente + donde,
                "SELECT count(*)" + desdeConContribuyente + donde,
                parametros,
                paginacion,
                ORDEN,
                AsientoRepositoryJdbc::mapear);
    }

    @Override
    public List<Asiento> porDocumentoOrigen(String documentoOrigen) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE a.documento_origen = :documento"
                                + "   AND a.asiento_reversado_id IS NULL"
                                + " ORDER BY a.id")
                .param("documento", documentoOrigen)
                .query(AsientoRepositoryJdbc::mapear)
                .list();
    }

    /**
     * Lo que cada documento <b>sigue</b> abonando, agrupado en el motor (#36).
     *
     * <p>Una sola consulta para todo el turno, con {@code documento_origen IN (…)}: es {@code
     * asiento_documento_origen_ix} (V30) el que la resuelve por indice en cada particion. Con la
     * lista vacia no se pregunta nada —{@code IN ()} no es SQL valido— y se devuelve el mapa vacio,
     * que es la respuesta correcta a «cuanto abonaron cero documentos».
     *
     * <p>Dos filtros, y el segundo es el que hace util la respuesta:
     *
     * <ul>
     *   <li>{@code tipo = 'ABONO'}: el cargo con el que la cobranza cristaliza el reajuste y el
     *       interes devengados no es dinero que entro. Sumarlo daria una cifra que no coincide con
     *       ningun recibo —el recibo cobra la suma de sus abonos—.
     *   <li><b>que nadie los haya reversado</b>. Un recibo anulado conserva sus asientos —no se
     *       borran, se reversan (V2)—, asi que preguntar «cuanto abono» devolveria el importe
     *       entero de un recibo que ya no vale. Preguntar «cuanto sigue abonado» devuelve cero, que
     *       es lo que el arqueo necesita: el neto de ese recibo tambien es cero.
     * </ul>
     *
     * <p>Que se resuelva asi y no restando el documento de la anulacion tiene una consecuencia que
     * importa: quien pregunta <b>no tiene que saber</b> que documento reversa a que otro, ni que la
     * reversion de un abono se escribe como cargo. Ese conocimiento vive aqui, que es donde vive el
     * libro.
     */
    @Override
    public Map<String, Dinero> abonadoPorDocumento(java.util.Collection<String> documentosOrigen) {
        if (documentosOrigen.isEmpty()) {
            return Map.of();
        }
        Map<String, Dinero> abonado = new HashMap<>();
        jdbc().sql(
                        "SELECT a.documento_origen, sum(a.monto) AS abonado"
                                + DESDE
                                + " WHERE a.documento_origen IN (:documentos)"
                                + "   AND a.tipo = 'ABONO'"
                                + "   AND NOT EXISTS ("
                                + "         SELECT 1 FROM cuenta_corriente_asiento r"
                                + "          WHERE r.municipalidad_id = a.municipalidad_id"
                                + "            AND r.asiento_reversado_id = a.id)"
                                + " GROUP BY a.documento_origen")
                .param("documentos", List.copyOf(documentosOrigen))
                .query(
                        (fila, numeroDeFila) -> {
                            abonado.put(
                                    fila.getString("documento_origen"),
                                    new Dinero(fila.getBigDecimal("abonado")));
                            return null;
                        })
                .list();
        return Map.copyOf(abonado);
    }

    /**
     * Los conceptos con los que una cobranza imputa lo que entro por ventanilla.
     *
     * <p>Son <b>exactamente</b> las cuatro partes que {@code RegistroDeAbonos#abonarPagoIntegro}
     * abona, en ese orden. Los demas abonos del libro mueven deuda pero no son dinero: {@code
     * AJUSTE} cambia una obligacion de fase, {@code CONDONACION} y {@code ANULACION} la dan de
     * baja, {@code FRACCIONAMIENTO} la acoge a un convenio. Contarlos inflaria la recaudacion con
     * bajas de deuda, que es la peor manera de equivocarse en esta cifra: hacia arriba y sin que
     * nadie lo note.
     *
     * <p><b>{@code PAGO} no esta en la lista</b>, aunque el {@code CHECK} de V2 lo admita: ningun
     * camino de cobranza lo usa. Ponerlo «por si acaso» seria contar un concepto que nadie escribe,
     * y el dia que alguien lo escribiera nadie sabria si esa cifra tenia que estar aqui.
     */
    private static final String CONCEPTOS_DE_COBRANZA =
            "('INSOLUTO', 'REAJUSTE', 'INTERES', 'GASTO')";

    /**
     * El filtro que separa una baja de deuda de un cobro, <b>por el acto y no por el signo</b>
     * (#601, V68).
     *
     * <p>Los cuatro {@link #CONCEPTOS_DE_COBRANZA} no bastan: el abono de una baja de deuda es un
     * {@code ABONO} de concepto {@code INSOLUTO}, columna a columna el mismo asiento que el de una
     * cobranza. Sin este filtro, extinguir deuda se publica como dinero que <b>entro</b> por
     * ventanilla —hacia arriba y sin que nadie lo note—, que es lo mismo que #56 ya dijo de la
     * condonacion.
     *
     * <p>{@code IS DISTINCT FROM} y no {@code <>}: la columna es nula en todo lo que no nacio de un
     * alta ni de una baja —una emision, una cobranza, una reversion— y {@code <>} descartaria esas
     * filas enteras, que son casi todas.
     */
    private static final String NO_ES_UNA_BAJA_DE_DEUDA =
            "   AND a.acto IS DISTINCT FROM 'BAJA_DEUDA'";

    /**
     * Lo cobrado de esos tributos entre dos fechas, agregado en el motor (#53, RF-073, RF-074).
     *
     * <p>Los tres filtros dicen lo mismo que su contrato: {@code tipo = 'ABONO'} —el cargo con que
     * la cobranza cristaliza el devengo no es dinero que entro—, los cuatro {@link
     * #CONCEPTOS_DE_COBRANZA} —los otros abonos mueven deuda, no la cobran— y que <b>nadie lo haya
     * reversado</b>, porque un recibo anulado conserva sus asientos (V2) y sumarlos daria por
     * recaudado un recibo que ya no vale.
     *
     * <p>El mes sale de {@code fecha_valor} y el ejercicio de la columna homonima: son cosas
     * distintas y las dos ciertas. Un recibo de marzo de 2026 que cobra deuda de 2025 cae en el mes
     * 3 del ejercicio 2025.
     */
    @Override
    public List<RecaudacionAgregada> recaudadoPorTributo(
            java.util.Collection<String> tributos,
            java.time.LocalDate desde,
            java.time.LocalDate hasta) {
        if (tributos.isEmpty()) {
            return List.of();
        }
        return jdbc().sql(recaudacion(" AND a.tributo IN (:tributos)"))
                .param("tributos", List.copyOf(tributos))
                .param("desde", desde)
                .param("hasta", hasta)
                .query(AsientoRepositoryJdbc::mapearRecaudacion)
                .list();
    }

    /**
     * Lo cobrado de todos los tributos entre dos fechas (#56, RF-130).
     *
     * <p>Exactamente la misma consulta sin el filtro de tributo, y comparte su texto: si fueran dos
     * cadenas separadas, un cambio en el criterio de reversion se aplicaria a una y no a la otra, y
     * el panel de inicio diria una cifra distinta de la del resumen del area sin que nada fallara.
     */
    @Override
    public List<RecaudacionAgregada> recaudadoDeTodos(
            java.time.LocalDate desde, java.time.LocalDate hasta) {
        return jdbc().sql(recaudacion(""))
                .param("desde", desde)
                .param("hasta", hasta)
                .query(AsientoRepositoryJdbc::mapearRecaudacion)
                .list();
    }

    /** La consulta de recaudacion, con o sin el filtro de tributo. */
    private static String recaudacion(String filtroDeTributo) {
        return "SELECT a.tributo, a.ejercicio,"
                + "       CAST(extract(month FROM a.fecha_valor) AS integer) AS mes,"
                + "       a.fase, sum(a.monto) AS recaudado, count(*) AS abonos"
                + DESDE
                + " WHERE a.tipo = 'ABONO'"
                + filtroDeTributo
                + "   AND a.concepto IN "
                + CONCEPTOS_DE_COBRANZA
                + NO_ES_UNA_BAJA_DE_DEUDA
                + "   AND a.fecha_valor >= :desde"
                + "   AND a.fecha_valor <= :hasta"
                + "   AND NOT EXISTS ("
                + "         SELECT 1 FROM cuenta_corriente_asiento r"
                + "          WHERE r.municipalidad_id = a.municipalidad_id"
                + "            AND r.asiento_reversado_id = a.id)"
                + " GROUP BY a.tributo, a.ejercicio,"
                + "          extract(month FROM a.fecha_valor), a.fase"
                + " ORDER BY a.tributo, a.ejercicio,"
                + "          extract(month FROM a.fecha_valor), a.fase";
    }

    /**
     * Lo cargado en un ejercicio, agrupado por tributo (#56, RF-130).
     *
     * <p>El reverso exacto de {@link #recaudadoPorTributo}: {@code CARGO} en vez de {@code ABONO} y
     * solo {@code INSOLUTO}, que es el tributo puesto a cobrar. El mismo {@code NOT EXISTS} de
     * reversion, para que numerador y denominador del avance se puedan dividir sin advertencias.
     *
     * <p><b>Y una tercera condicion, que solo se ve ejecutando</b>: {@code asiento_reversado_id IS
     * NULL}. Reversar un abono produce un {@code CARGO} del mismo concepto —lo hace {@code
     * Asiento#reversionDe}—, asi que un recibo anulado de 120 deja en el libro un cargo de 120 que
     * <b>no es deuda nueva</b>: es la deuda de siempre, que vuelve a estar viva. Sin este filtro,
     * un tributo con 400 determinados y un recibo anulado de 120 se publicaba como 520 cargados, o
     * sea una emision inflada por cada anulacion del ejercicio. La proyeccion del saldo no tiene
     * ese defecto porque netea cargos contra abonos; este agregado solo mira un lado, y por eso
     * necesita decirlo.
     *
     * <p><b>Y una cuarta, que es la de #601</b>: una deuda dada de baja deja de estar puesta a
     * cobrar. La baja no es una reversion —es un asiento nuevo—, asi que no la cazaba ninguna de
     * las tres anteriores: dar de alta 100 y darlo de baja despues devolvia la cartera al centimo y
     * dejaba lo cargado con los 100. Medido en la instalacion de demostracion: seis altas de 100 y
     * cinco bajas dejaban la cartera exacta y lo cargado en +600,07, o sea el denominador de todas
     * las barras del panel inflado <b>por corregir bien</b>.
     *
     * <p>Se resta {@code acto = 'BAJA_DEUDA'}, <b>por el acto y no por el signo</b>. Netear todos
     * los abonos de insoluto contra los cargos —{@code CARGO} menos {@code ABONO} a secas— se
     * llevaria por delante los <b>cobros</b>, y «lo cargado» acabaria valiendo la cartera
     * pendiente: el avance de cobranza saltaria al 100 % en cuanto alguien pagara. El abono de una
     * cobranza es columna a columna el mismo asiento que el de una baja, y lo unico que los separa
     * es {@link ActoDelLibro} (V68).
     *
     * <p>{@code cargos} cuenta solo las filas de {@code CARGO}: son los asientos que pusieron deuda
     * a cobrar, y un acto que la quita no es «un cargo mas». Una baja anterior a V68 no lleva acto
     * y no se puede reconocer —el libro no admite {@code UPDATE} (V7) y no se reescribe (regla 4)—:
     * lo que este filtro arregla es de aqui en adelante.
     *
     * <p>El filtro por {@code ejercicio} es ademas la <b>clave de particion</b> del libro (V2), asi
     * que esta consulta toca una sola particion aunque haya diez anos de asientos.
     */
    @Override
    public List<CargoAgregado> cargadoPorTributo(Ejercicio ejercicio) {
        return jdbc().sql(
                        "SELECT a.tributo,"
                                + "       sum(CASE WHEN a.tipo = 'CARGO' THEN a.monto"
                                + "                ELSE -a.monto END) AS cargado,"
                                + "       count(*) FILTER (WHERE a.tipo = 'CARGO') AS cargos"
                                + DESDE
                                + " WHERE a.ejercicio = :ejercicio"
                                + "   AND a.concepto = 'INSOLUTO'"
                                + "   AND (a.tipo = 'CARGO' OR a.acto = 'BAJA_DEUDA')"
                                + "   AND a.asiento_reversado_id IS NULL"
                                + "   AND NOT EXISTS ("
                                + "         SELECT 1 FROM cuenta_corriente_asiento r"
                                + "          WHERE r.municipalidad_id = a.municipalidad_id"
                                + "            AND r.asiento_reversado_id = a.id)"
                                + " GROUP BY a.tributo"
                                + " ORDER BY a.tributo")
                .param("ejercicio", ejercicio.valor())
                .query(
                        (fila, numeroDeFila) ->
                                new CargoAgregado(
                                        fila.getString("tributo"),
                                        new Dinero(fila.getBigDecimal("cargado")),
                                        fila.getLong("cargos")))
                .list();
    }

    /**
     * Lo pendiente del ejercicio a la fecha de corte, agrupado por tributo (#639, RF-130).
     *
     * <p><b>Dos agregaciones y no una, y las dos las hace el motor.</b> La de dentro netea el
     * insoluto de cada <b>obligacion</b> —el mismo grupo con el que {@code consulta_deuda} publica
     * una fila: contribuyente, tributo, ejercicio y unidad— hasta el corte; la de fuera se queda
     * con las positivas y las suma por tributo. Devuelve una linea por tributo, no una por
     * obligacion: la cartera de un padron son decenas de miles de filas y el panel escribe una
     * docena de numeros (AC 4 de #56).
     *
     * <p>{@code fecha_valor <= :aLaFecha} es la condicion que faltaba y que da nombre a #639. Sin
     * ella la cifra es la misma preguntando por enero que por diciembre, y estampar en ella la
     * fecha de corte del panel es afirmar que en enero ya se debia la cuota de noviembre. Medido en
     * la municipalidad de demostracion: PREDIAL 2026 al 2026-09-01 son <b>8 221,05</b> y sin la
     * condicion salian <b>10 662,60</b>.
     *
     * <p>Sin filtro de reversion: ver {@link AsientoRepository#pendientePorTributo}. Netear ya lo
     * corrige; las dos mitades del filtro son inertes aqui y media resta deuda viva.
     */
    @Override
    public List<PendienteAgregado> pendientePorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
        return jdbc().sql(
                        "SELECT tributo, sum(insoluto) AS pendiente,"
                                + "       count(*) AS obligaciones"
                                + " FROM (SELECT a.tributo,"
                                + "              sum(CASE WHEN a.tipo = 'CARGO' THEN a.monto"
                                + "                       ELSE -a.monto END) AS insoluto"
                                + DESDE
                                + "        WHERE a.ejercicio = :ejercicio"
                                + "          AND a.concepto = 'INSOLUTO'"
                                + "          AND a.fecha_valor <= :aLaFecha"
                                + "        GROUP BY a.contribuyente_id, a.tributo, a.ejercicio,"
                                + "                 a.predio_id, a.vehiculo_id) obligacion"
                                + " WHERE insoluto > 0"
                                + " GROUP BY tributo"
                                + " ORDER BY tributo")
                .param("ejercicio", ejercicio.valor())
                .param("aLaFecha", aLaFecha)
                .query(
                        (fila, numeroDeFila) ->
                                new PendienteAgregado(
                                        fila.getString("tributo"),
                                        new Dinero(fila.getBigDecimal("pendiente")),
                                        fila.getLong("obligaciones")))
                .list();
    }

    private static RecaudacionAgregada mapearRecaudacion(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new RecaudacionAgregada(
                fila.getString("tributo"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getInt("mes"),
                Fase.valueOf(fila.getString("fase")),
                new Dinero(fila.getBigDecimal("recaudado")),
                fila.getLong("abonos"));
    }

    @Override
    public Optional<Long> contribuyentePorCodigo(String codigo) {
        return jdbc().sql(
                        "SELECT c.id FROM contribuyente c"
                                + " WHERE c.codigo_contribuyente = :codigo")
                .param("codigo", codigo)
                .query((fila, numeroDeFila) -> fila.getLong("id"))
                .optional();
    }

    @Override
    public List<Asiento> deContribuyente(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE a.contribuyente_id = :contribuyente"
                                + " ORDER BY a.id")
                .param("contribuyente", contribuyenteId)
                .query(AsientoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<Long> contribuyentesConAsientos(long despuesDe, int cuantos) {
        return jdbc().sql(
                        "SELECT DISTINCT a.contribuyente_id"
                                + DESDE
                                + " WHERE a.contribuyente_id > :desde"
                                + " ORDER BY a.contribuyente_id"
                                + " LIMIT :cuantos")
                .param("desde", despuesDe)
                .param("cuantos", cuantos)
                // Mapeo explicito y no query(Long.class): la columna es NOT NULL, y el
                // atajo devuelve List<@Nullable Long>, que NullAway rechaza con razon.
                .query((fila, numeroDeFila) -> fila.getLong("contribuyente_id"))
                .list();
    }

    /**
     * Los ejercicios con particion declarada en {@code cuenta_corriente_asiento} (#597).
     *
     * <p>Se lee del catalogo y no de una lista escrita en Java: una constante quedaria vieja en
     * silencio el dia que una migracion anada 2028, que es el mismo modo de fallo que este issue
     * describe con otro nombre.
     *
     * <p>La expresion del limite de cada particion se parte por comas para admitir tambien una
     * particion que agrupe varios ejercicios, y de cada trozo se toma el primer numero. <b>Los dos
     * detalles salieron de ejecutarlo</b>: {@code ejercicio} es un <b>dominio</b> sobre {@code
     * int}, y {@code pg_get_expr} imprime el limite de una columna de dominio <b>entrecomillado</b>
     * —{@code FOR VALUES IN ('2026')}, no {@code (2026)}—, de modo que la version obvia se caia con
     * «invalid input syntax for type integer: "'2026'"» <b>en la primera alta de cualquier
     * ejercicio</b>. Una particion {@code DEFAULT} no lleva ningun numero y se descarta: no la hay,
     * y si algun dia la hubiera esta comprobacion seria mas estricta de lo necesario, nunca mas
     * laxa.
     *
     * <p><b>Se memoriza.</b> La lista cambia solo con una migracion, y las migraciones corren antes
     * de que el proceso web atienda (ADR-0003); preguntarlo por asiento costaria una consulta al
     * catalogo por cada fila de una emision masiva, que son decenas de miles.
     */
    @Override
    public List<Ejercicio> ejerciciosAsentables() {
        List<Ejercicio> memorizados = particiones;
        if (memorizados != null) {
            return memorizados;
        }
        List<Ejercicio> leidos =
                jdbc().sql(
                                """
                                SELECT DISTINCT substring(valor from '[0-9]+')::int AS ejercicio
                                  FROM pg_catalog.pg_inherits i
                                  JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid,
                                       LATERAL regexp_split_to_table(
                                           substring(pg_get_expr(c.relpartbound, c.oid)
                                                     from 'FOR VALUES IN \\((.*)\\)'),
                                           ',') AS valor
                                 WHERE i.inhparent = 'cuenta_corriente_asiento'::regclass
                                   AND substring(valor from '[0-9]+') IS NOT NULL
                                 ORDER BY ejercicio
                                """)
                        .query((fila, numeroDeFila) -> new Ejercicio(fila.getInt("ejercicio")))
                        .list();
        particiones = List.copyOf(leidos);
        return particiones;
    }

    /**
     * Las grafias del libro que el vocabulario de V74 no admite (#553).
     *
     * <p>La lista de admitidos <b>no</b> se escribe en el SQL: se pasa como parametro desde {@link
     * TributoDelLibro#admitidos()}, que es lo que impide que este {@code NOT IN} y el {@code CHECK}
     * de la migracion se separen sin que nadie lo note. Y la comprobacion va sobre el texto tal
     * cual esta guardado: aqui no se normaliza, porque lo que se busca es precisamente lo que se
     * escribio distinto.
     */
    @Override
    public List<String> tributosFueraDelVocabulario() {
        return jdbc().sql(
                        "SELECT DISTINCT tributo FROM cuenta_corriente_asiento"
                                + " WHERE tributo <> ALL (:admitidos)"
                                + " ORDER BY tributo")
                .param("admitidos", TributoDelLibro.admitidos().toArray(new String[0]))
                .query((fila, numeroDeFila) -> fila.getString("tributo"))
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Por que la traduccion solo envuelve al alta</h2>
     *
     * <p>El unico indice unico que este {@code INSERT} puede violar hoy es {@code
     * asiento_alta_unica_uq} (V75, #588), que es <b>parcial</b> y solo cubre {@code acto =
     * 'ALTA_DEUDA'}: la clave primaria la genera la base con {@code IDENTITY} y no hay ningun otro
     * indice unico sobre {@code cuenta_corriente_asiento}. Envolver tambien a los demas actos daria
     * un {@code catch} que **no puede dispararse**, y el dia que alguien anadiera otro indice unico
     * a esta tabla ese {@code catch} contestaria «ya se dio de alta esta deuda» sobre un choque que
     * no es ese — un mensaje plausible y falso, que es la forma exacta del defecto que #588 cierra.
     *
     * <p><b>Medido</b>: quitar esta distincion —envolver todo por igual— deja las 400+ pruebas del
     * modulo en verde, porque hoy ningun otro insert puede producir un {@code 23505}. Lo que la
     * distincion compra no es una prueba que muerda: es que el mensaje no pueda empezar a mentir
     * sin que nadie lo decida.
     */
    @Override
    public Asiento registrar(Asiento asiento) {
        if (asiento.acto() != ActoDelLibro.ALTA_DEUDA) {
            return insertar(asiento);
        }
        try {
            return insertar(asiento);
        } catch (DuplicateKeyException choque) {
            throw new AsientoRepository.AltaYaAsentada(descripcionDelAltaRepetida(asiento), choque);
        }
    }

    /**
     * El mensaje del alta repetida: la obligacion, el concepto y el sustento, y nada mas.
     *
     * <p>Ni tabla, ni indice, ni SQL (RNF-033). Lo que lleva es lo que quien atiende necesita para
     * saber que fue lo que ya estaba: el <b>documento de sustento</b> —que es lo que separa dos
     * altas legitimas sobre la misma obligacion— y la cuota y el concepto, porque un acto puede
     * abarcar un rango y un desglose y sin ellos no se sabria cual de los {@code n} asientos choco.
     */
    private static String descripcionDelAltaRepetida(Asiento asiento) {
        Integer periodo = asiento.periodo();
        String cuota = periodo == null || periodo == 0 ? "anual" : "cuota " + periodo;
        return "Ya se dio de alta esta deuda con el mismo sustento: "
                + asiento.tributo()
                + " "
                + asiento.ejercicio().valor()
                + ", "
                + cuota
                + ", "
                + asiento.concepto().name().toLowerCase(java.util.Locale.ROOT)
                + ", documento de origen '"
                + asiento.documentoOrigen()
                + "'. Un alta repetida cargaria dos veces la misma obligacion al mismo"
                + " contribuyente, y ninguna cifra pareceria mal. Si es un acto distinto, va con"
                + " su propio documento de sustento";
    }

    private Asiento insertar(Asiento asiento) {
        String usuario = OrigenContext.actual().usuario();

        Long id =
                jdbc().sql(
                                "INSERT INTO cuenta_corriente_asiento"
                                        + " (municipalidad_id, ejercicio, contribuyente_id,"
                                        + "  tributo, concepto, tipo, fase, periodo, predio_id,"
                                        + "  vehiculo_id, referencia_externa, monto, fecha_valor,"
                                        + "  documento_origen, asiento_reversado_id, usuario_id,"
                                        + "  motivo, acto, unidad_de_titular_anterior, causal)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, :contribuyenteId, :tributo, :concepto,"
                                        + "  :tipo, :fase, :periodo, :predioId, :vehiculoId,"
                                        + "  :referenciaExterna, :monto, :fechaValor,"
                                        + "  :documentoOrigen, :asientoReversadoId, :usuario,"
                                        + "  :motivo, :acto, :deTitularAnterior, :causal)"
                                        + " RETURNING id")
                        .param("ejercicio", asiento.ejercicio().valor())
                        .param("contribuyenteId", asiento.contribuyenteId())
                        .param("tributo", asiento.tributo())
                        .param("concepto", asiento.concepto().name())
                        .param("tipo", asiento.tipo().name())
                        .param("fase", asiento.fase().name())
                        .param("periodo", asiento.periodo())
                        .param("predioId", asiento.predioId())
                        .param("vehiculoId", asiento.vehiculoId())
                        .param("referenciaExterna", asiento.referenciaExterna())
                        .param("monto", asiento.monto().valor())
                        .param("fechaValor", asiento.fechaValor())
                        .param("documentoOrigen", asiento.documentoOrigen())
                        .param("asientoReversadoId", asiento.asientoReversadoId())
                        .param("usuario", usuario)
                        .param("motivo", asiento.motivo())
                        .param("acto", asiento.acto() == null ? null : asiento.acto().name())
                        .param("deTitularAnterior", asiento.unidadDeTitularAnterior())
                        .param("causal", asiento.causal() == null ? null : asiento.causal().name())
                        .query(Long.class)
                        .single();

        return new Asiento(
                id,
                asiento.ejercicio(),
                asiento.contribuyenteId(),
                asiento.tributo(),
                asiento.concepto(),
                asiento.tipo(),
                asiento.fase(),
                asiento.periodo(),
                asiento.predioId(),
                asiento.vehiculoId(),
                asiento.referenciaExterna(),
                asiento.monto(),
                asiento.fechaValor(),
                asiento.documentoOrigen(),
                asiento.asientoReversadoId(),
                usuario,
                asiento.motivo(),
                asiento.acto(),
                asiento.unidadDeTitularAnterior(),
                asiento.causal());
    }

    private static Asiento mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        int periodo = fila.getInt("periodo");
        Integer periodoValor = fila.wasNull() ? null : periodo;
        long reversado = fila.getLong("asiento_reversado_id");
        Long asientoReversadoId = fila.wasNull() ? null : reversado;

        return new Asiento(
                fila.getLong("id"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("contribuyente_id"),
                fila.getString("tributo"),
                Concepto.valueOf(fila.getString("concepto")),
                TipoAsiento.valueOf(fila.getString("tipo").strip()),
                Fase.valueOf(fila.getString("fase")),
                periodoValor,
                predioId,
                vehiculoId,
                fila.getString("referencia_externa"),
                new Dinero(fila.getBigDecimal("monto")),
                fila.getDate("fecha_valor").toLocalDate(),
                fila.getString("documento_origen"),
                asientoReversadoId,
                fila.getString("usuario_id"),
                fila.getString("motivo"),
                actoDe(fila.getString("acto")),
                fila.getBoolean("unidad_de_titular_anterior"),
                causalDe(fila.getString("causal")));
    }

    /**
     * El acto del asiento, si el libro lo sabe. Nulo es «no nacio de un alta ni de una baja de
     * deuda» —una emision, una cobranza, una reversion—, no «se desconoce» (#601, V68).
     */
    private static @Nullable ActoDelLibro actoDe(@Nullable String columna) {
        return columna == null ? null : ActoDelLibro.valueOf(columna);
    }

    /**
     * La causal de la baja, si la fila la declara. Nulo es «esta fila no la declaro» —toda baja
     * anterior a V77, y todo asiento que no es una baja—, no «se desconoce» (#684, V77).
     */
    private static @Nullable CausalDeBaja causalDe(@Nullable String columna) {
        return columna == null ? null : CausalDeBaja.valueOf(columna);
    }
}
