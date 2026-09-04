package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.pagos.EstadoDelPagoRecibido;
import kamayuk.rentas.tesoreria.pagos.PagoRecibido;
import kamayuk.rentas.tesoreria.pagos.PagoRecibidoRepository;
import kamayuk.rentas.tesoreria.pagos.PagosEnTransito;
import kamayuk.rentas.tesoreria.pagos.ReferenciaDeObligacion;
import kamayuk.rentas.tesoreria.pagos.TipoDePagoRecibido;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** El buzon de entrada de pagos, contra PostgreSQL. */
@Repository
public class PagoRecibidoRepositoryJdbc extends RepositorioJdbc
        implements PagoRecibidoRepository, PagosEnTransito {

    private static final String COLUMNAS =
            "id, pago_id, tipo, pago_original_id, sistema_caja, recibo_numero, contribuyente_id,"
                    + " fecha_pago, total, cuerpo::text AS cuerpo, estado, asientos, motivo,"
                    + " recibido_en, aplicado_en";

    /**
     * Como viajan las obligaciones dentro del cuerpo.
     *
     * <p>Se leen del JSON congelado y no de una columna aparte, y es deliberado: la referencia es
     * opaca para la caja y su formato es de este sistema, asi que sacarla a columnas obligaria a
     * decidir aqui cuantas partes tiene — y el dia que un sistema de origen distinto publique con
     * otro formato, esas columnas mentirian.
     *
     * <p>Tolera el espacio detras de los dos puntos porque {@code jsonb} reserializa: ver {@link
     * #obligacionesDe(String)}.
     */
    private static final java.util.regex.Pattern REFERENCIA =
            java.util.regex.Pattern.compile("\"referenciaExterna\"\\s*:\\s*\"([^\"]*)\"");

    public PagoRecibidoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Recepcion recibirRechazado(PagoRecibido pago, String motivo) {
        return insertar(pago, "RECHAZADO", motivo);
    }

    @Override
    public Recepcion recibir(PagoRecibido pago) {
        return insertar(pago, "EN_TRANSITO", null);
    }

    private Recepcion insertar(
            PagoRecibido pago, String estado, @org.jspecify.annotations.Nullable String motivo) {
        Optional<Long> insertado =
                jdbc().sql(
                                "INSERT INTO pago_recibido (municipalidad_id, pago_id, tipo,"
                                        + " pago_original_id, sistema_caja, recibo_numero,"
                                        + " contribuyente_id, fecha_pago, total, cuerpo, estado,"
                                        + " asientos, recibido_en, motivo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :pago, :tipo, :original, :caja, :recibo,"
                                        + " :contribuyente, :fecha, :total,"
                                        + " CAST(:cuerpo AS jsonb), :estado, 0, :recibido,"
                                        + " :motivo)"
                                        + " ON CONFLICT ON CONSTRAINT pago_recibido_uq"
                                        + " DO NOTHING RETURNING id")
                        .param("pago", pago.pagoId())
                        .param("tipo", pago.tipo().name())
                        .param("original", pago.pagoOriginalId())
                        .param("caja", pago.sistemaCaja())
                        .param("recibo", pago.reciboNumero())
                        .param("contribuyente", pago.contribuyenteId())
                        .param("fecha", pago.fechaDePago())
                        .param("total", pago.total().valor())
                        .param("cuerpo", pago.cuerpo())
                        .param("recibido", Timestamp.from(pago.recibidoEn()))
                        .param("estado", estado)
                        .param("motivo", motivo)
                        .query(Long.class)
                        .optional();

        PagoRecibido guardado =
                porPagoId(pago.pagoId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El pago se acaba de recibir y no se puede leer;"
                                                        + " con RLS activo eso solo puede pasar sin"
                                                        + " contexto de tenant"));
        return new Recepcion(guardado, insertado.isPresent());
    }

    @Override
    public Optional<PagoRecibido> porPagoId(UUID pagoId) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM pago_recibido WHERE pago_id = :pago")
                .param("pago", pagoId)
                .query(PagoRecibidoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public void marcarAplicado(long id, int asientos, Instant cuando) {
        jdbc().sql(
                        "UPDATE pago_recibido SET estado = 'APLICADO', asientos = :asientos,"
                                + " aplicado_en = :cuando, motivo = NULL"
                                + " WHERE id = :id AND estado = 'EN_TRANSITO'")
                .param("asientos", asientos)
                .param("cuando", Timestamp.from(cuando))
                .param("id", id)
                .update();
    }

    @Override
    public void marcarRechazado(long id, String motivo) {
        jdbc().sql(
                        "UPDATE pago_recibido SET estado = 'RECHAZADO', motivo = :motivo"
                                + " WHERE id = :id AND estado = 'EN_TRANSITO'")
                .param("motivo", motivo)
                .param("id", id)
                .update();
    }

    @Override
    public List<PagoRecibido> enTransitoDe(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM pago_recibido"
                                + " WHERE contribuyente_id = :contribuyente"
                                + "   AND estado = 'EN_TRANSITO' ORDER BY recibido_en")
                .param("contribuyente", contribuyenteId)
                .query(PagoRecibidoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<PagosEnTransito.EnTransito> de(long contribuyenteId) {
        List<PagosEnTransito.EnTransito> filas = new ArrayList<>();
        for (PagoRecibido pago : enTransitoDe(contribuyenteId)) {
            filas.add(
                    new PagosEnTransito.EnTransito(
                            pago.reciboNumero(), pago.total(), pago.recibidoEn()));
        }
        return List.copyOf(filas);
    }

    /**
     * El recuento de un dia.
     *
     * <p>Las anulaciones <b>restan</b> del importe aplicado, y por eso el {@code CASE} distingue el
     * tipo: la caja pregunta por su NETO del dia —lo cobrado menos lo devuelto— y comparar contra
     * la suma bruta daria una diferencia igual al doble de lo anulado en cuanto alguien anule un
     * recibo, que es lo mas corriente que pasa en una ventanilla.
     */
    @Override
    public Recuento recuentoDe(LocalDate dia) {
        return jdbc().sql(
                        """
                        SELECT count(*)                                        AS recibidos,
                               count(*) FILTER (WHERE estado = 'APLICADO')     AS aplicados,
                               count(*) FILTER (WHERE estado = 'RECHAZADO')    AS rechazados,
                               coalesce(sum(CASE WHEN estado <> 'APLICADO' THEN 0
                                                 WHEN tipo = 'PAGO_ANULADO' THEN -total
                                                 ELSE total END), 0)           AS importe
                          FROM pago_recibido
                         WHERE fecha_pago = :dia
                        """)
                .param("dia", dia)
                .query(
                        (fila, numero) ->
                                new Recuento(
                                        fila.getInt("recibidos"),
                                        fila.getInt("aplicados"),
                                        fila.getInt("rechazados"),
                                        new Dinero(fila.getBigDecimal("importe"))))
                .single();
    }

    private static PagoRecibido mapear(ResultSet fila, int numero) throws SQLException {
        String original = fila.getString("pago_original_id");
        Timestamp aplicado = fila.getTimestamp("aplicado_en");
        Long contribuyente = (Long) fila.getObject("contribuyente_id");
        return new PagoRecibido(
                fila.getLong("id"),
                UUID.fromString(fila.getString("pago_id")),
                TipoDePagoRecibido.valueOf(fila.getString("tipo")),
                original == null ? null : UUID.fromString(original),
                fila.getString("sistema_caja"),
                fila.getString("recibo_numero"),
                contribuyente,
                fila.getObject("fecha_pago", LocalDate.class),
                new Dinero(fila.getBigDecimal("total")),
                obligacionesDe(fila.getString("cuerpo")),
                fila.getString("cuerpo"),
                EstadoDelPagoRecibido.valueOf(fila.getString("estado")),
                fila.getInt("asientos"),
                fila.getString("motivo"),
                fila.getTimestamp("recibido_en").toInstant(),
                aplicado == null ? null : aplicado.toInstant());
    }

    /**
     * Las referencias que el cuerpo trae.
     *
     * <h2>Y UNA TRAMPA QUE COSTO UNA PRUEBA EN ROJO</h2>
     *
     * <p>La columna es {@code jsonb}, y <b>PostgreSQL no devuelve el texto que se guardo</b>: lo
     * reserializa. Entra {@code "referenciaExterna":"PREDIAL|2026||"} y sale {@code
     * "referenciaExterna": "PREDIAL|2026||"} —con un espacio detras de los dos puntos, y las claves
     * reordenadas—. Una busqueda de subcadena escrita contra el texto original no encuentra nada, y
     * el sintoma no se parece a la causa: el pago se rechaza diciendo «no trae ninguna obligacion»
     * sobre un cuerpo que las trae todas.
     *
     * <p>Es el mismo hallazgo que #653 midio en {@code auditoria.datos_nuevos}, y por eso aqui se
     * usa un patron que tolera el espacio en vez de una subcadena. Lo que NO se hace es guardar la
     * columna como {@code text} para conservar el byte exacto: {@code jsonb} es lo que permite
     * consultarla, y perder eso por una comodidad de lectura seria peor.
     *
     * <p>Sin Jackson en el repositorio, a proposito: traer un {@code ObjectMapper} aqui haria que
     * un cambio de configuracion de serializacion cambiara como se leen los pagos <b>ya
     * guardados</b>, que es lo contrario de «congelado».
     */
    private static List<ReferenciaDeObligacion> obligacionesDe(String cuerpo) {
        List<ReferenciaDeObligacion> referencias = new ArrayList<>();
        java.util.regex.Matcher busqueda = REFERENCIA.matcher(cuerpo);
        while (busqueda.find()) {
            referencias.add(ReferenciaDeObligacion.leer(busqueda.group(1)));
        }
        return List.copyOf(referencias);
    }
}
