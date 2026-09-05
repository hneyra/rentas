package kamayuk.rentas.licencias.infraestructura;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.licencias.dominio.CriterioDeAnuncios;
import kamayuk.rentas.licencias.dominio.ResumenDelPadron;
import kamayuk.rentas.licencias.dominio.TipoDeAnuncio;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las autorizaciones de anuncio contra PostgreSQL (V4, V45).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE anuncio} ni un {@code DELETE}: V45 le
 * retira a {@code kamayuk_app} el privilegio de {@code UPDATE} y {@code DELETE} nunca lo tuvo (V7);
 * el escaner de fuentes rechaza esas dos cadenas antes de que lleguen a ejecutarse. El unico {@code
 * UPDATE} de esta clase es el del contador de {@code anuncio_correlativo}, que es infraestructura
 * de numeracion y no un acto administrativo (mismo criterio que {@code licencia_correlativo} en
 * V37).
 *
 * <p><b>Las busquedas por prefijo van como rango, no como {@code LIKE}.</b> Bajo RLS un {@code LIKE
 * 'prefijo%'} no llega nunca al indice (DAT-01 §0, hallazgo 3); ver {@link RangoDePrefijo}.
 */
@Repository
public class AnuncioRepositoryJdbc extends RepositorioJdbc implements AnuncioRepository {

    private static final String COLUMNAS =
            "id, numero, contribuyente_id, predio_id, licencia_id, clase, tipo, emplazamiento,"
                    + " forma, denominacion, ubicacion, area, lados, cantidad, fecha_autorizacion,"
                    + " vigencia_hasta, expediente, fecha_expediente, clave_idempotencia,"
                    + " usuario_registro, fecha_registro, observacion";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha_autorizacion", "ubicacion", "expediente", "clase");

    public AnuncioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos autorizaciones concurrentes del mismo ejercicio se serializan en el motor y salen
        // con numeros consecutivos. Nunca un SELECT seguido de un UPDATE: entre los dos cabe otra
        // autorizacion, y las dos leerian el mismo numero.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO anuncio_correlativo (municipalidad_id, ejercicio,"
                                        + " ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = anuncio_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public Anuncio autorizar(Anuncio anuncio) {
        if (!anuncio.esNuevo()) {
            throw new IllegalArgumentException(
                    "Una autorizacion ya emitida no se vuelve a insertar ni se corrige: se cesa con"
                            + " su movimiento");
        }
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO anuncio"
                                            + " (municipalidad_id, numero, contribuyente_id,"
                                            + "  predio_id, licencia_id, clase, tipo,"
                                            + "  emplazamiento, forma, denominacion, ubicacion,"
                                            + "  area, lados, cantidad, fecha_autorizacion,"
                                            + "  vigencia_hasta, expediente, fecha_expediente,"
                                            + "  clave_idempotencia, usuario_registro,"
                                            + "  fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :contribuyente, :predio, :licencia,"
                                            + "  :clase, :tipo, :emplazamiento, :forma,"
                                            + "  :denominacion, :ubicacion, :area, :lados,"
                                            + "  :cantidad, :autorizacion, :vigencia, :expediente,"
                                            + "  :fechaExpediente, :clave, :usuario, :registrado,"
                                            + "  :observacion)"
                                            + " RETURNING id")
                            .param("numero", anuncio.numero())
                            .param("contribuyente", anuncio.contribuyenteId())
                            .param("predio", anuncio.predioId())
                            .param("licencia", anuncio.licenciaId())
                            .param("clase", anuncio.clase().name())
                            .param("tipo", anuncio.tipo().name())
                            .param("emplazamiento", anuncio.emplazamiento())
                            .param("forma", anuncio.forma())
                            .param("denominacion", anuncio.denominacion())
                            .param("ubicacion", anuncio.ubicacion())
                            .param("area", anuncio.area().valor())
                            .param("lados", anuncio.lados())
                            .param("cantidad", anuncio.cantidad())
                            .param("autorizacion", anuncio.fechaAutorizacion())
                            .param("vigencia", anuncio.vigenciaHasta())
                            .param("expediente", anuncio.expediente())
                            .param("fechaExpediente", anuncio.fechaExpediente())
                            .param("clave", anuncio.claveIdempotencia())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(anuncio.registradoEn()))
                            .param("observacion", anuncio.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Los dos indices unicos de la tabla significan cosas distintas y por eso se traducen
            // por separado. Un mensaje unico mandaria a quien opera a mirar donde no es: el de la
            // clave de idempotencia NO es un defecto -es un reintento, que es lo que se espera del
            // cliente- y el del numero si lo seria.
            if (choqueDe(yaEstaba, "anuncio_idempotencia_uq")) {
                throw new ClaveRepetida(
                        "Ya se registro una autorizacion con esa clave de idempotencia: el reintento"
                                + " no crea un segundo anuncio ni un segundo cargo",
                        yaEstaba);
            }
            throw new NumeroDuplicado(
                    "Ya existe la autorizacion de anuncio "
                            + anuncio.numero()
                            + " en esta municipalidad: dos autorizaciones con el mismo numero no se"
                            + " pueden distinguir",
                    yaEstaba);
        }
        return porId(Objects.requireNonNull(id));
    }

    @Override
    public Optional<Anuncio> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM anuncio WHERE numero = :numero")
                .param("numero", numero == null ? "" : numero.strip())
                .query(AnuncioRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Anuncio> porClaveDeIdempotencia(String clave) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM anuncio WHERE clave_idempotencia = :clave")
                .param("clave", clave)
                .query(AnuncioRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Anuncio> buscar(CriterioDeAnuncios criterio, Paginacion paginacion) {
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);
        return paginar(
                "SELECT " + COLUMNAS + " FROM anuncio" + donde,
                "SELECT count(*) FROM anuncio" + donde,
                parametros,
                paginacion,
                ORDEN,
                AnuncioRepositoryJdbc::mapear);
    }

    @Override
    public ResumenDelPadron resumen(CriterioDeAnuncios criterio, LocalDate aLaFecha) {
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);

        Long autorizaciones =
                jdbc().sql("SELECT count(*) FROM anuncio" + donde)
                        .params(parametros)
                        .query(Long.class)
                        .single();

        // El devengado sale de un AGREGADO DEL MOTOR sobre todas las filas del criterio, no de
        // sumar la pagina devuelta: sumar la pagina daria una cifra que parece un total y no lo
        // es (#25). El subconsulta repite el mismo WHERE, con los mismos parametros.
        parametros.put("aLaFecha", aLaFecha);
        BigDecimal devengado =
                jdbc().sql(
                                "SELECT coalesce(sum(m.tasa), 0)"
                                        + "  FROM anuncio_movimiento m"
                                        + " WHERE m.tasa IS NOT NULL"
                                        + "   AND m.fecha <= :aLaFecha"
                                        + "   AND m.anuncio_id IN"
                                        + "       (SELECT id FROM anuncio"
                                        + donde
                                        + ")")
                        .params(parametros)
                        .query(BigDecimal.class)
                        .single();

        return new ResumenDelPadron(
                Objects.requireNonNull(autorizaciones),
                new Dinero(Objects.requireNonNull(devengado)));
    }

    // ------------------------------------------------------------------

    private static String donde(CriterioDeAnuncios criterio, Map<String, Object> parametros) {
        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        if (criterio.numero() != null) {
            donde.append(" AND numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.clase() != null) {
            donde.append(" AND clase = :clase");
            parametros.put("clase", criterio.clase().name());
        }
        if (criterio.desde() != null) {
            donde.append(" AND fecha_autorizacion >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND fecha_autorizacion <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        if (criterio.expediente() != null) {
            RangoDePrefijo.condicion(
                    donde,
                    parametros,
                    "expediente",
                    criterio.expediente().toUpperCase(Locale.ROOT),
                    "exp");
        }
        if (criterio.direccion() != null) {
            RangoDePrefijo.condicion(
                    donde,
                    parametros,
                    "ubicacion",
                    criterio.direccion().toUpperCase(Locale.ROOT),
                    "dir");
        }
        Set<Long> titulares = criterio.contribuyentes();
        if (titulares != null) {
            // Vacio no llega aqui: `ConsultaDeAnuncios` devuelve la pagina vacia antes, porque un
            // `IN ()` no es SQL valido y un filtro ignorado devolveria el padron entero.
            donde.append(" AND contribuyente_id IN (:titulares)");
            parametros.put("titulares", titulares);
        }
        return donde.toString();
    }

    /**
     * Si el choque de clave unica fue contra ese indice.
     *
     * <p>Se mira el nombre en la cadena de causas y no el {@code SQLSTATE}, que es {@code 23505}
     * para los dos indices unicos de la tabla.
     */
    private static boolean choqueDe(RuntimeException fallo, String indice) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            String mensaje = causa.getMessage();
            if (mensaje != null && mensaje.contains(indice)) {
                return true;
            }
        }
        return false;
    }

    private Anuncio porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM anuncio WHERE id = :id")
                .param("id", id)
                .query(AnuncioRepositoryJdbc::mapear)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La autorizacion recien insertada no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    private static Anuncio mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long licencia = fila.getLong("licencia_id");
        Long licenciaId = fila.wasNull() ? null : licencia;
        Date vigencia = fila.getDate("vigencia_hasta");
        Date fechaExpediente = fila.getDate("fecha_expediente");

        return new Anuncio(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getLong("contribuyente_id"),
                predioId,
                licenciaId,
                ClaseDeAnuncio.porNombre(fila.getString("clase")),
                TipoDeAnuncio.porNombre(fila.getString("tipo")),
                fila.getString("emplazamiento"),
                fila.getString("forma"),
                fila.getString("denominacion"),
                fila.getString("ubicacion"),
                new AreaM2(fila.getBigDecimal("area")),
                fila.getInt("lados"),
                fila.getInt("cantidad"),
                fila.getDate("fecha_autorizacion").toLocalDate(),
                vigencia == null ? null : vigencia.toLocalDate(),
                fila.getString("expediente"),
                fechaExpediente == null ? null : fechaExpediente.toLocalDate(),
                fila.getString("clave_idempotencia"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
