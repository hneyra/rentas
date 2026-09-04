package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecibos;
import kamayuk.rentas.tesoreria.dominio.EstadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeRecibo;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los recibos contra PostgreSQL (V3, V29).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE recibo} ni un {@code DELETE}: V29 le
 * retira a {@code sgtm_app} el privilegio de actualizar y V7 nunca le dio el de borrar, y el
 * escaner de fuentes rechaza esas dos cadenas antes de que lleguen a ejecutarse (regla 4, RNF-051).
 * Un recibo equivocado se anula con un movimiento que se agrega (#34).
 */
@Repository
public class ReciboRepositoryJdbc extends RepositorioJdbc implements ReciboRepository {

    private static final String COLUMNAS =
            "id, serie, numero, caja_id, turno_id, cajero, contribuyente_id, fecha, forma_pago,"
                    + " tipo_pago, campania_beneficio, actualizado_a, observacion";

    /**
     * El estado del recibo, derivado (V30): hay fila de anulacion o no la hay.
     *
     * <p>Escrito una vez y usado en el {@code SELECT} y en el {@code WHERE}, para que el filtro y
     * la columna no puedan divergir. Es la leccion de #397: con dos copias del mismo {@code CASE},
     * el filtro «Anulado» acaba devolviendo lo que la columna dice emitido, y no lo caza nadie.
     */
    private static final String ANULADO =
            "EXISTS (SELECT 1 FROM recibo_movimiento m"
                    + "  WHERE m.recibo_id = r.id AND m.tipo = 'ANULACION')";

    /**
     * Por donde se admite ordenar el listado, con {@code id} de desempate (#543).
     *
     * <p>Sin desempate, dos recibos del mismo instante —dos cajas cobrando a la vez— empatan en
     * {@code fecha} y el plan puede devolverlos en cualquier orden: dos paginas consecutivas
     * repetirian uno y omitirian el otro, que en una busqueda de recibos significa que el que se
     * busca no aparece nunca.
     */
    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha", "serie", "numero", "cajero", "total").desempatandoPor("id");

    private static final String COLUMNAS_DETALLE =
            "tributo, concepto, ejercicio, periodo, tasa_id, predio_id, vehiculo_id,"
                    + " referencia_externa, cantidad, precio_unitario, insoluto, reajuste, interes,"
                    + " gasto";

    public ReciboRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * Reserva el siguiente correlativo de la serie de esa caja.
     *
     * <p>Una sola sentencia, nunca {@code SELECT} + {@code UPDATE}. El {@code DO UPDATE} bloquea la
     * fila del contador mientras la incrementa, asi que dos cobranzas de la misma serie se
     * serializan en el motor y salen con numeros consecutivos. Con dos sentencias, las dos leerian
     * el mismo numero y la segunda chocaria contra {@code recibo_numero_uq}.
     */
    @Override
    public NumeroDeRecibo siguienteNumero(Caja caja) {
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO recibo_correlativo (municipalidad_id, serie, ultimo)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :serie, 1)"
                                        + " ON CONFLICT (municipalidad_id, serie)"
                                        + " DO UPDATE SET ultimo = recibo_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("serie", caja.serie())
                        .query(Long.class)
                        .single();
        return caja.numero(Objects.requireNonNull(ultimo));
    }

    @Override
    public Recibo emitir(Recibo recibo, @Nullable String claveDeIdempotencia) {
        if (recibo.id() != null) {
            throw new IllegalArgumentException(
                    "Un recibo ya emitido no se vuelve a insertar ni se corrige: se anula (#34)");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO recibo"
                                        + " (municipalidad_id, serie, numero, caja_id, turno_id,"
                                        + "  cajero, contribuyente_id, fecha, forma_pago, tipo_pago,"
                                        + "  campania_beneficio, total, actualizado_a,"
                                        + "  clave_idempotencia, usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :serie, :numero, :caja, :turno, :cajero, :contribuyente,"
                                        + "  :fecha, :formaPago, :tipoPago, :campania, :total,"
                                        + "  :actualizadoA, :clave, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("serie", recibo.numero().serie())
                        .param("numero", recibo.numero().numero())
                        .param("caja", recibo.cajaId())
                        .param("turno", recibo.turnoId())
                        .param("cajero", recibo.cajero())
                        .param("contribuyente", recibo.contribuyenteId())
                        .param("fecha", Timestamp.from(recibo.emitidoEn()))
                        .param("formaPago", recibo.formaDePago().name())
                        .param("tipoPago", recibo.tipoDePago().name())
                        .param("campania", recibo.campaniaBeneficio())
                        .param("total", recibo.total().valor())
                        .param("actualizadoA", recibo.actualizadoA())
                        .param("clave", claveDeIdempotencia)
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("observacion", recibo.observacion().texto())
                        .query(Long.class)
                        .single();

        for (LineaDeRecibo linea : recibo.lineas()) {
            insertarLinea(Objects.requireNonNull(id), linea);
        }

        return new Recibo(
                id,
                recibo.numero(),
                recibo.cajaId(),
                recibo.turnoId(),
                recibo.cajero(),
                recibo.contribuyenteId(),
                recibo.emitidoEn(),
                recibo.formaDePago(),
                recibo.tipoDePago(),
                recibo.campaniaBeneficio(),
                recibo.actualizadoA(),
                recibo.observacion(),
                recibo.lineas());
    }

    @Override
    public Optional<Recibo> porClaveDeIdempotencia(String clave) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM recibo WHERE clave_idempotencia = :clave")
                .param("clave", clave)
                .query(ReciboRepositoryJdbc::mapearCabecera)
                .optional()
                .map(this::conDetalle);
    }

    @Override
    public Optional<Recibo> porNumero(NumeroDeRecibo numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM recibo WHERE serie = :serie AND numero = :numero")
                .param("serie", numero.serie())
                .param("numero", numero.numero())
                .query(ReciboRepositoryJdbc::mapearCabecera)
                .optional()
                .map(this::conDetalle);
    }

    /**
     * El listado de recibos (#548), con el estado y los duplicados derivados en la misma consulta.
     *
     * <p><b>Sin ningun {@code JOIN}.</b> El codigo del contribuyente y el de la caja entran como
     * subconsultas escalares, igual que en {@code ConvenioRepositoryJdbc} y por lo mismo: con un
     * {@code JOIN}, el dia que alguien anada a {@code contribuyente} o a {@code caja} una columna
     * que se llame como una del {@code ORDER BY}, la paginacion se rompe entera y no se ve en
     * revision.
     *
     * <p>El {@code EXISTS} sobre {@code recibo_movimiento} y el {@code count} de duplicados van los
     * dos por {@code recibo_movimiento_recibo_ix} (V30), que es {@code (municipalidad_id,
     * recibo_id, tipo)}: el indice que V30 declaro para esto.
     */
    @Override
    public Pagina<ReciboEnConsulta> buscar(CriterioDeRecibos criterio, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new LinkedHashMap<>();

        if (criterio.codigoContribuyente() != null) {
            donde.append(
                    " AND r.contribuyente_id = (SELECT t.id FROM contribuyente t"
                            + " WHERE t.codigo_contribuyente = :codigo)");
            parametros.put("codigo", criterio.codigoContribuyente());
        }
        if (criterio.caja() != null) {
            donde.append(" AND r.caja_id = (SELECT c.id FROM caja c WHERE c.codigo = :caja)");
            parametros.put("caja", criterio.caja());
        }
        if (criterio.cajero() != null) {
            donde.append(" AND r.cajero = :cajero");
            parametros.put("cajero", criterio.cajero());
        }
        // `recibo.fecha` es timestamptz y el rango del filtro es de dias: se compara
        // sobre la fecha, no sobre el instante, o un recibo de las 09:14 quedaria fuera
        // de un «hasta» que es su mismo dia.
        if (criterio.desde() != null) {
            donde.append(" AND r.fecha >= :desde");
            parametros.put("desde", criterio.desde().atStartOfDay());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND r.fecha < :hasta");
            parametros.put("hasta", criterio.hasta().plusDays(1).atStartOfDay());
        }
        if (criterio.estado() != null) {
            donde.append(criterio.estado() == EstadoDeRecibo.ANULADO ? " AND " : " AND NOT ")
                    .append(ANULADO);
        }

        String desde = " FROM recibo r" + donde;
        String seleccion =
                "SELECT r.id, r.serie, r.numero, r.contribuyente_id, r.fecha, r.cajero,"
                        + " r.forma_pago, r.total, r.actualizado_a, "
                        + ANULADO
                        + " AS anulado,"
                        + " (SELECT count(*) FROM recibo_movimiento m"
                        + "   WHERE m.recibo_id = r.id AND m.tipo = 'DUPLICADO') AS duplicados"
                        + desde;

        return paginar(
                seleccion,
                "SELECT count(*)" + desde,
                parametros,
                paginacion,
                ORDEN,
                ReciboRepositoryJdbc::mapearFilaDeConsulta);
    }

    // ------------------------------------------------------------------

    private void insertarLinea(long reciboId, LineaDeRecibo linea) {
        jdbc().sql(
                        "INSERT INTO recibo_detalle"
                                + " (municipalidad_id, recibo_id, tributo, concepto, ejercicio,"
                                + "  periodo, tasa_id, predio_id, vehiculo_id, referencia_externa,"
                                + "  cantidad, precio_unitario, monto, insoluto, reajuste, interes,"
                                + "  gasto)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :recibo, :tributo, :concepto, :ejercicio, :periodo, :tasa,"
                                + "  :predio, :vehiculo, :referencia, :cantidad, :precio, :monto,"
                                + "  :insoluto, :reajuste, :interes, :gasto)")
                .param("recibo", reciboId)
                .param("tributo", linea.tributo())
                .param("concepto", linea.concepto())
                .param("ejercicio", linea.ejercicio() == null ? null : linea.ejercicio().valor())
                .param("periodo", linea.periodo())
                .param("tasa", linea.tasaId())
                .param("predio", linea.predioId())
                .param("vehiculo", linea.vehiculoId())
                .param("referencia", linea.referenciaExterna())
                .param("cantidad", linea.cantidad())
                .param(
                        "precio",
                        linea.precioUnitario() == null ? null : linea.precioUnitario().valor())
                .param("monto", linea.monto().valor())
                .param("insoluto", linea.insoluto().valor())
                .param("reajuste", linea.reajuste().valor())
                .param("interes", linea.interes().valor())
                .param("gasto", linea.gasto().valor())
                .update();
    }

    private Recibo conDetalle(Cabecera cabecera) {
        List<LineaDeRecibo> lineas =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS_DETALLE
                                        + " FROM recibo_detalle WHERE recibo_id = :recibo"
                                        + " ORDER BY id")
                        .param("recibo", cabecera.id())
                        .query(ReciboRepositoryJdbc::mapearLinea)
                        .list();
        return new Recibo(
                cabecera.id(),
                cabecera.numero(),
                cabecera.cajaId(),
                cabecera.turnoId(),
                cabecera.cajero(),
                cabecera.contribuyenteId(),
                cabecera.emitidoEn(),
                cabecera.formaDePago(),
                cabecera.tipoDePago(),
                cabecera.campaniaBeneficio(),
                cabecera.actualizadoA(),
                cabecera.observacion(),
                lineas);
    }

    /**
     * La fila de {@code recibo} sin su detalle.
     *
     * <p>Existe porque {@link Recibo} exige al menos una linea —un recibo sin desglose no documenta
     * nada— y por tanto no se puede construir a medias. Mapear a este tipo intermedio y completarlo
     * en {@link #conDetalle} es lo que evita tener que inventarse una linea de relleno que despues
     * alguien encuentre en produccion.
     */
    private record Cabecera(
            long id,
            NumeroDeRecibo numero,
            long cajaId,
            long turnoId,
            String cajero,
            long contribuyenteId,
            java.time.Instant emitidoEn,
            FormaDePago formaDePago,
            TipoDePago tipoDePago,
            @Nullable String campaniaBeneficio,
            java.time.LocalDate actualizadoA,
            Observacion observacion) {}

    private static Cabecera mapearCabecera(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Cabecera(
                fila.getLong("id"),
                new NumeroDeRecibo(fila.getString("serie"), fila.getLong("numero")),
                fila.getLong("caja_id"),
                fila.getLong("turno_id"),
                fila.getString("cajero"),
                fila.getLong("contribuyente_id"),
                fila.getTimestamp("fecha").toInstant(),
                FormaDePago.valueOf(fila.getString("forma_pago").strip()),
                TipoDePago.valueOf(fila.getString("tipo_pago").strip()),
                fila.getString("campania_beneficio"),
                fila.getDate("actualizado_a").toLocalDate(),
                Observacion.de(fila.getString("observacion")));
    }

    private static ReciboEnConsulta mapearFilaDeConsulta(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ReciboEnConsulta(
                fila.getLong("id"),
                new NumeroDeRecibo(fila.getString("serie"), fila.getLong("numero")),
                fila.getLong("contribuyente_id"),
                fila.getTimestamp("fecha").toInstant(),
                fila.getString("cajero"),
                FormaDePago.valueOf(fila.getString("forma_pago").strip()),
                new Dinero(fila.getBigDecimal("total")),
                fila.getDate("actualizado_a").toLocalDate(),
                EstadoDeRecibo.deLaAnulacion(fila.getBoolean("anulado")),
                fila.getLong("duplicados"));
    }

    private static LineaDeRecibo mapearLinea(ResultSet fila, int numeroDeFila) throws SQLException {
        int ejercicio = fila.getInt("ejercicio");
        Ejercicio delEjercicio = fila.wasNull() ? null : new Ejercicio(ejercicio);
        int periodo = fila.getInt("periodo");
        Integer laCuota = fila.wasNull() ? null : periodo;
        long tasa = fila.getLong("tasa_id");
        Long tasaId = fila.wasNull() ? null : tasa;
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        int cantidad = fila.getInt("cantidad");
        Integer cuantas = fila.wasNull() ? null : cantidad;
        java.math.BigDecimal precio = fila.getBigDecimal("precio_unitario");

        return new LineaDeRecibo(
                fila.getString("tributo"),
                fila.getString("concepto"),
                delEjercicio,
                laCuota,
                tasaId,
                predioId,
                vehiculoId,
                fila.getString("referencia_externa"),
                cuantas,
                precio == null ? null : new Dinero(precio),
                new Dinero(fila.getBigDecimal("insoluto")),
                new Dinero(fila.getBigDecimal("reajuste")),
                new Dinero(fila.getBigDecimal("interes")),
                new Dinero(fila.getBigDecimal("gasto")));
    }
}
