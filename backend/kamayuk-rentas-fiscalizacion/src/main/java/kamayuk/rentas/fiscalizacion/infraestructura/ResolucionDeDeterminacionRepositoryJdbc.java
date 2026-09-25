package kamayuk.rentas.fiscalizacion.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las transferencias a rentas contra PostgreSQL.
 *
 * <p>Solo {@code INSERT} y {@code SELECT}: V49 no le concede {@code UPDATE} ni {@code DELETE} a
 * {@code kamayuk_app}, y el escaner del codigo fuente vigila lo mismo desde arriba.
 *
 * <p>Ninguna consulta lleva {@code WHERE municipalidad_id = ?}: el filtrado lo hace la politica RLS
 * con el valor que {@code SET LOCAL} fijo al abrir la transaccion (regla 2).
 */
@Repository
public class ResolucionDeDeterminacionRepositoryJdbc extends RepositorioJdbc
        implements ResolucionDeDeterminacionRepository {

    private static final String COLUMNAS =
            "id, numero, documento_id, liquidacion_id, contribuyente_id, predio_id, vehiculo_id,"
                    + " ficha_anterior_id, ficha_nueva_id, fecha, documento_sustento, sustento,"
                    + " base_legal, usuario_registro, observacion";

    public ResolucionDeDeterminacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ResolucionDeDeterminacion registrar(ResolucionDeDeterminacion resolucion) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("numero", resolucion.numero());
        campos.put("documento", resolucion.documentoId());
        campos.put("liquidacion", resolucion.liquidacionId());
        campos.put("contribuyente", resolucion.contribuyenteId());
        campos.put("predio", resolucion.predioId());
        campos.put("vehiculo", resolucion.vehiculoId());
        campos.put("fichaAnterior", resolucion.fichaAnteriorId());
        campos.put("fichaNueva", resolucion.fichaNuevaId());
        campos.put("fecha", resolucion.fecha());
        campos.put("sustentoDocumental", resolucion.documentoSustento());
        campos.put("sustento", resolucion.sustento());
        campos.put("baseLegal", resolucion.baseLegal());
        campos.put("usuario", OrigenContext.actual().usuario());
        campos.put("observacion", resolucion.observacion().texto());

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO resolucion_determinacion"
                                            + " (municipalidad_id, numero, documento_id,"
                                            + "  liquidacion_id, contribuyente_id, predio_id,"
                                            + "  vehiculo_id, ficha_anterior_id, ficha_nueva_id, fecha,"
                                            + "  documento_sustento, sustento, base_legal,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :documento, :liquidacion, :contribuyente,"
                                            + "  :predio, :vehiculo, :fichaAnterior, :fichaNueva,"
                                            + "  :fecha, :sustentoDocumental, :sustento,"
                                            + "  :baseLegal, :usuario, now(), :observacion)"
                                            + " RETURNING id")
                            .params(campos)
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException duplicada) {
            // `resolucion_determinacion_liquidacion_uq` (V49), que es el AC 6. La comprobacion no
            // se escribe solo en Java porque dos peticiones simultaneas pasan las dos por
            // cualquier `if`: la de arriba ahorra el trabajo, esta es la que lo impide.
            throw new LiquidacionYaTransferida(resolucion.liquidacionId());
        }

        return conIdentificador(resolucion, id);
    }

    @Override
    public Optional<ResolucionDeDeterminacion> porNumero(String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion WHERE numero = :numero")
                .param("numero", numero.strip().toUpperCase(java.util.Locale.ROOT))
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ResolucionDeDeterminacion> deLiquidacion(long liquidacionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion"
                                + " WHERE liquidacion_id = :liquidacion")
                .param("liquidacion", liquidacionId)
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * Las resoluciones de la unidad con el periodo de su liquidacion, en una consulta (#462).
     *
     * <p>Es el mismo {@code JOIN} que la relacion, y por lo mismo interno: no hay resolucion sin
     * liquidacion. Las dos tablas son de fiscalizacion, asi que la lectura no cruza ninguna
     * frontera. Sin {@code WHERE municipalidad_id}: lo pone RLS (regla 2).
     */
    @Override
    public List<kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion> vigentesSobreLaUnidad(
            @Nullable Long predioId, @Nullable Long vehiculoId) {
        return jdbc().sql(
                        RELACION
                                + (predioId != null
                                        ? " WHERE r.predio_id = :unidad"
                                        : " WHERE r.vehiculo_id = :unidad")
                                + " ORDER BY r.fecha, r.numero")
                .param("unidad", unidad(predioId, vehiculoId))
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapearLaRelacion)
                .list();
    }

    /**
     * Un candado <b>de transaccion</b> por unidad, nunca de sesion (#462).
     *
     * <p>Uno de sesion sobrevive a la devolucion de la conexion al pool y bloquearia la peticion de
     * otra municipalidad: es la regla 3 aplicada a los candados, como en {@code
     * CacheDeSnapshotsJdbc} y en {@code ValorRepositoryJdbc}. La clave es la unidad dentro de la
     * municipalidad —que sale del contexto que fijo {@code SET LOCAL}, no de un argumento (regla
     * 2)— reducida a un {@code bigint} con {@code hashtextextended} y con el prefijo {@code
     * determinacion|}, que la separa de las claves de los valores. Dos unidades con la misma huella
     * solo se esperarian de mas; nunca se dejarian pasar.
     *
     * <p>Es por unidad y no por unidad y ejercicio: un solo candado por transferencia no puede
     * interbloquearse con otro, y dos transferencias sobre el mismo vehiculo son raras y cortas. El
     * {@code count(*)} es porque la funcion devuelve {@code void}, que no se puede mapear.
     */
    @Override
    public void bloquearLaUnidad(@Nullable Long predioId, @Nullable Long vehiculoId) {
        String clave = (predioId != null ? "p" : "v") + unidad(predioId, vehiculoId);
        jdbc().sql(
                        "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended("
                                + "'determinacion|' || "
                                + MUNICIPALIDAD_ACTUAL
                                + " || '|' || :clave, 0))) AS candado")
                .param("clave", clave)
                .query(Long.class)
                .single();
    }

    private static long unidad(@Nullable Long predioId, @Nullable Long vehiculoId) {
        if ((predioId == null) == (vehiculoId == null)) {
            throw new IllegalArgumentException(
                    "Una resolucion determina un predio o un vehiculo, nunca los dos ni ninguno");
        }
        return predioId != null ? predioId : java.util.Objects.requireNonNull(vehiculoId);
    }

    @Override
    public List<ResolucionDeDeterminacion> deContribuyente(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion"
                                + " WHERE contribuyente_id = :contribuyente"
                                + " ORDER BY fecha DESC, id DESC")
                .param("contribuyente", contribuyenteId)
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .list();
    }

    /**
     * Lo que la relacion ensena de cada resolucion, con su liquidacion, en UNA consulta (#192).
     *
     * <p>El {@code JOIN} es interno y no externo a proposito: {@code
     * resolucion_determinacion.liquidacion_id} es {@code NOT NULL} y tiene foranea (V49), asi que
     * no puede haber una resolucion sin liquidacion. Con un {@code LEFT JOIN} una fila huerfana
     * saldria con el periodo en blanco en vez de no salir, y aqui no hay ninguna que ocultar.
     *
     * <p>Las columnas del {@code SELECT} van con alias para que la lista blanca de orden pueda
     * nombrarlas sin ambiguedad: {@code numero} y {@code version} estan en las dos tablas.
     */
    private static final String RELACION =
            "SELECT r.numero, r.fecha, r.contribuyente_id, r.predio_id, r.vehiculo_id,"
                    + " r.documento_sustento, l.numero AS numero_liquidacion,"
                    + " l.version AS version_liquidacion, l.acta_id, l.ejercicio_desde,"
                    + " l.ejercicio_hasta"
                    + " FROM resolucion_determinacion r"
                    + " JOIN liquidacion_fiscalizacion l ON l.id = r.liquidacion_id";

    private static final String CONTEO =
            "SELECT count(*) FROM resolucion_determinacion r"
                    + " JOIN liquidacion_fiscalizacion l ON l.id = r.liquidacion_id";

    /**
     * Por que se admite ordenar, y por que estas cuatro.
     *
     * <p>Son las que la fila <b>ensena con ese nombre</b>: pedir por un nombre que la fila no
     * publica es el defecto que #608 tuvo que arreglar con {@code publicandoComo}. {@code
     * desempatandoPor("numero")} da el orden total que una lectura paginada necesita —{@code fecha}
     * empata en cuanto dos resoluciones se dictan el mismo dia, que es lo normal en una tanda— y se
     * elige {@code numero} y no {@code id} porque el numero SI sale en la fila: un desempate por
     * una columna que el cliente no ve es indistinguible de un orden inestable (#543, #548).
     */
    static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha", "contribuyente_id", "acta_id")
                    .publicandoComo("actaId", "acta_id")
                    .desempatandoPor("numero");

    @Override
    public kamayuk.rentas.compartido.Pagina<
                    kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion>
            consultar(
                    kamayuk.rentas.fiscalizacion.dominio.CriterioDeResoluciones criterio,
                    kamayuk.rentas.compartido.Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();
        if (criterio.contribuyenteId() != null) {
            donde.append(" AND r.contribuyente_id = :contribuyente");
            parametros.put("contribuyente", criterio.contribuyenteId());
        }

        return paginar(
                RELACION + donde,
                CONTEO + donde,
                parametros,
                paginacion,
                ORDEN,
                ResolucionDeDeterminacionRepositoryJdbc::mapearLaRelacion);
    }

    private static kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion mapearLaRelacion(
            java.sql.ResultSet fila, int numeroDeFila) throws java.sql.SQLException {
        Object predioId = fila.getObject("predio_id");
        Object vehiculoId = fila.getObject("vehiculo_id");
        return new kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion(
                fila.getString("numero"),
                fila.getDate("fecha").toLocalDate(),
                fila.getLong("contribuyente_id"),
                predioId == null ? null : fila.getLong("predio_id"),
                vehiculoId == null ? null : fila.getLong("vehiculo_id"),
                fila.getString("numero_liquidacion"),
                fila.getInt("version_liquidacion"),
                fila.getLong("acta_id"),
                new kamayuk.rentas.dominio.Ejercicio(fila.getInt("ejercicio_desde")),
                new kamayuk.rentas.dominio.Ejercicio(fila.getInt("ejercicio_hasta")),
                fila.getString("documento_sustento"));
    }

    private static ResolucionDeDeterminacion conIdentificador(
            ResolucionDeDeterminacion resolucion, Long id) {
        return new ResolucionDeDeterminacion(
                id,
                resolucion.numero(),
                resolucion.documentoId(),
                resolucion.liquidacionId(),
                resolucion.contribuyenteId(),
                resolucion.predioId(),
                resolucion.vehiculoId(),
                resolucion.fichaAnteriorId(),
                resolucion.fichaNuevaId(),
                resolucion.fecha(),
                resolucion.documentoSustento(),
                resolucion.sustento(),
                resolucion.baseLegal(),
                OrigenContext.actual().usuario(),
                resolucion.observacion());
    }

    private static ResolucionDeDeterminacion mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ResolucionDeDeterminacion(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getLong("documento_id"),
                fila.getLong("liquidacion_id"),
                fila.getLong("contribuyente_id"),
                entero(fila, "predio_id"),
                entero(fila, "vehiculo_id"),
                entero(fila, "ficha_anterior_id"),
                entero(fila, "ficha_nueva_id"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("documento_sustento"),
                fila.getString("sustento"),
                fila.getString("base_legal"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static @Nullable Long entero(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }
}
