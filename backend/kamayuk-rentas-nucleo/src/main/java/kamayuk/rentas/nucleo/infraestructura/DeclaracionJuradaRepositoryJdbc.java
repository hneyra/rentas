package kamayuk.rentas.nucleo.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.TipoDeDeclaracion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las declaraciones juradas contra PostgreSQL (#28).
 *
 * <p>{@link #marcar} es el unico {@code UPDATE}, y toca solo {@code estado}: es lo que garantiza en
 * la base lo que {@link DeclaracionJurada#rectificadaPor} ya garantiza en el dominio —una
 * rectificatoria sustituye sin editar—. Desde V54 eso ya no depende de que esta clase se acuerde:
 * {@code sgtm_app} tiene {@code UPDATE} sobre la columna {@code estado} y sobre ninguna otra.
 */
@Repository
public class DeclaracionJuradaRepositoryJdbc extends RepositorioJdbc
        implements DeclaracionJuradaRepository {

    private static final String COLUMNAS =
            "d.id, d.numero, d.ejercicio, d.contribuyente_id, d.tipo, d.predio_id, d.vehiculo_id,"
                    + " d.ficha_catastral_id, d.fecha_presentacion, d.fecha_limite, d.estado,"
                    + " d.dj_rectifica_id, d.usuario_registro, d.observacion";

    private static final String DESDE = " FROM declaracion_jurada d";

    public DeclaracionJuradaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<DeclaracionJurada> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE d.id = :id")
                .param("id", id)
                .query(DeclaracionJuradaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<DeclaracionJurada> porNumero(String numero, Ejercicio ejercicio) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE d.numero = :numero AND d.ejercicio = :ejercicio")
                .param("numero", numero)
                .param("ejercicio", ejercicio.valor())
                .query(DeclaracionJuradaRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * La lista blanca del {@code ORDER BY}: {@code fecha_presentacion} es el orden por omision de
     * la ficha unificada, y los otros tres son los que su grilla deja pinchar. El texto del cliente
     * no llega nunca a la consulta —{@link OrdenSeguro} traduce contra esta lista o no hay
     * consulta—.
     */
    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_presentacion", "ejercicio", "numero", "id");

    @Override
    public Pagina<DeclaracionJurada> deContribuyente(long contribuyenteId, Paginacion paginacion) {
        String desde = DESDE + " WHERE d.contribuyente_id = :contribuyenteId";
        return paginar(
                "SELECT " + COLUMNAS + desde,
                "SELECT count(*)" + desde,
                Map.of("contribuyenteId", contribuyenteId),
                paginacion,
                ORDEN,
                DeclaracionJuradaRepositoryJdbc::mapear);
    }

    /**
     * Las declaraciones vigentes de un lote de predios (#49, RF-055).
     *
     * <p>{@code d.predio_id = ANY(:predios)} y no {@code IN (:predios)}: con la primera forma el
     * lote viaja como <b>un</b> parametro —un arreglo— y el plan se cachea igual para paginas de
     * veinte y de cien; con {@code IN}, cada tamano de lote produce una consulta distinta.
     *
     * <p>Los estados vigentes van como arreglo por el mismo motivo, y salen de {@link
     * EstadoDeDeclaracion} en vez de escribirse aqui: la definicion de «vigente» vive en un solo
     * sitio.
     */
    @Override
    public java.util.List<DeclaracionJurada> vigentesDePredios(
            java.util.Collection<Long> predioIds, Ejercicio ejercicio) {
        if (predioIds.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE d.ejercicio = :ejercicio"
                                + "   AND d.predio_id = ANY(:predios)"
                                + "   AND d.estado = ANY(:estados)")
                .param("ejercicio", ejercicio.valor())
                .param("predios", predioIds.toArray(Long[]::new))
                .param("estados", EstadoDeDeclaracion.nombresDeLasVigentes())
                .query(DeclaracionJuradaRepositoryJdbc::mapear)
                .list();
    }

    /**
     * Los predios conciliados de un lote, para la lectura compuesta de ADR-0015 (#344).
     *
     * <p>Es la misma pregunta que {@link #vigentesDePredios} y va por el mismo indice —{@code
     * dj_ejercicio_predio_ix}, V39— pero devuelve <b>solo el identificador del predio</b>: quien
     * pinta la columna «Conciliada» no necesita la declaracion, y traerla entera pondria el numero
     * de la DJ y su contribuyente al alcance de una respuesta de catastro (ADR-0015 §2.2).
     *
     * <p>{@code DISTINCT} porque un predio puede tener mas de una fila vigente en el mismo
     * ejercicio y la respuesta es un si o un no, no un recuento.
     */
    /**
     * Todos los del ejercicio (#631). Va por el mismo indice —{@code dj_ejercicio_predio_ix}, V39—
     * y sin la condicion de la lista, que es lo unico que cambia.
     */
    @Override
    public java.util.Set<Long> prediosConDeclaracionVigente(Ejercicio ejercicio) {
        return new java.util.LinkedHashSet<>(
                jdbc().sql(
                                "SELECT DISTINCT d.predio_id"
                                        + DESDE
                                        + " WHERE d.ejercicio = :ejercicio"
                                        + "   AND d.predio_id IS NOT NULL"
                                        + "   AND d.estado = ANY(:estados)")
                        .param("ejercicio", ejercicio.valor())
                        .param("estados", EstadoDeDeclaracion.nombresDeLasVigentes())
                        .query(Long.class)
                        .list());
    }

    @Override
    public java.util.Set<Long> prediosConDeclaracionVigente(
            java.util.Collection<Long> predioIds, Ejercicio ejercicio) {
        if (predioIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.LinkedHashSet<>(
                jdbc().sql(
                                "SELECT DISTINCT d.predio_id"
                                        + DESDE
                                        + " WHERE d.ejercicio = :ejercicio"
                                        + "   AND d.predio_id = ANY(:predios)"
                                        + "   AND d.estado = ANY(:estados)")
                        .param("ejercicio", ejercicio.valor())
                        .param("predios", predioIds.toArray(Long[]::new))
                        .param("estados", EstadoDeDeclaracion.nombresDeLasVigentes())
                        .query(Long.class)
                        .list());
    }

    @Override
    public DeclaracionJurada insertar(DeclaracionJurada declaracion) {
        String usuario = OrigenContext.actual().usuario();

        Long id =
                jdbc().sql(
                                "INSERT INTO declaracion_jurada"
                                        + " (municipalidad_id, numero, ejercicio,"
                                        + "  contribuyente_id, tipo, predio_id, vehiculo_id,"
                                        + "  ficha_catastral_id, fecha_presentacion, fecha_limite,"
                                        + "  fuera_de_plazo, estado, dj_rectifica_id,"
                                        + "  usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :numero, :ejercicio, :contribuyenteId, :tipo,"
                                        + "  :predioId, :vehiculoId, :fichaCatastralId,"
                                        + "  :fechaPresentacion, :fechaLimite, :fueraDePlazo,"
                                        + "  :estado, :djRectificaId, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("numero", declaracion.numero())
                        .param("ejercicio", declaracion.ejercicio().valor())
                        .param("contribuyenteId", declaracion.contribuyenteId())
                        .param("tipo", declaracion.tipo().name())
                        .param("predioId", declaracion.predioId())
                        .param("vehiculoId", declaracion.vehiculoId())
                        .param("fichaCatastralId", declaracion.fichaCatastralId())
                        .param("fechaPresentacion", declaracion.fechaPresentacion())
                        .param("fechaLimite", declaracion.fechaLimite())
                        .param("fueraDePlazo", declaracion.fueraDePlazo())
                        .param("estado", declaracion.estado().name())
                        .param("djRectificaId", declaracion.djRectificaId())
                        .param("usuario", usuario)
                        .param("observacion", declaracion.observacion().texto())
                        .query(Long.class)
                        .single();

        return new DeclaracionJurada(
                id,
                declaracion.numero(),
                declaracion.ejercicio(),
                declaracion.contribuyenteId(),
                declaracion.tipo(),
                declaracion.predioId(),
                declaracion.vehiculoId(),
                declaracion.fichaCatastralId(),
                declaracion.fechaPresentacion(),
                declaracion.fechaLimite(),
                declaracion.estado(),
                declaracion.djRectificaId(),
                usuario,
                declaracion.observacion());
    }

    /**
     * El siguiente correlativo del ejercicio, en <b>una</b> sentencia (#365).
     *
     * <p>Mismo UPSERT que {@code licencia_correlativo} (V37) y {@code certificado_correlativo}
     * (V51), con una diferencia propia: <b>la fila se crea arrancando por encima del mayor numero
     * historico del ejercicio</b>, y no en 1.
     *
     * <p>Ese arranque no se pudo sembrar en la migracion, y el motivo vale anotarlo: {@code
     * declaracion_jurada} tiene RLS con {@code FORCE} y el migrador corre como {@code sgtm_owner}
     * <b>sin</b> contexto de tenant, asi que un {@code SELECT} sobre ella durante la migracion
     * falla con «unrecognized configuration parameter» (DAT-01 §0, cuarto hallazgo). Aqui si hay
     * contexto: la subconsulta ve las declaraciones de esta municipalidad y de ninguna otra.
     *
     * <p>La subconsulta solo se evalua la <b>primera</b> vez de cada ejercicio: a partir de ahi
     * gana la rama del conflicto, que se limita a incrementar. Y si dos peticiones la evaluan a la
     * vez, una inserta y la otra choca e incrementa lo insertado: los dos correlativos salen
     * distintos.
     *
     * <p>El {@code substring} lee la <b>ultima corrida de digitos</b> del numero, que es donde vive
     * el correlativo tanto si el historico se escribio {@code 000418} como si se escribio {@code
     * DJ-2026-000418}. Se limita a quince digitos para no desbordar {@code bigint} con una columna
     * que admite veinte caracteres.
     */
    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO dj_correlativo (municipalidad_id, ejercicio, ultimo)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio,"
                                        + "   (SELECT coalesce(max(coalesce(nullif(substring("
                                        + "        d.numero from '([0-9]{1,15})$'), '')::bigint,"
                                        + "        0)), 0) + 1"
                                        + "      FROM declaracion_jurada d"
                                        + "     WHERE d.ejercicio = :ejercicio))"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET ultimo = dj_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return java.util.Objects.requireNonNull(ultimo);
    }

    @Override
    public DeclaracionJurada marcar(long id, EstadoDeDeclaracion nuevo) {
        DeclaracionJurada anterior =
                findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No hay ninguna declaracion jurada con"
                                                        + " identificador "
                                                        + id
                                                        + " en esta municipalidad"));

        // La transicion se calcula ANTES de escribir: si es ilegal, no se escribe nada. La
        // comprobacion la hace el dominio, que es donde vive la maquina de estados.
        DeclaracionJurada doblada =
                switch (nuevo) {
                    case SUSTITUIDA -> anterior.sustituida();
                    case OBSERVADA -> anterior.observada();
                    case ANULADA -> anterior.anulada();
                    case PRESENTADA ->
                            throw new IllegalArgumentException(
                                    "Una declaracion jurada no vuelve a PRESENTADA: nace asi y"
                                            + " solo sale de ese estado (#365)");
                };

        int filas =
                jdbc().sql("UPDATE declaracion_jurada SET estado = :estado WHERE id = :id")
                        .param("estado", nuevo.name())
                        .param("id", id)
                        .update();
        if (filas == 0) {
            throw new IllegalStateException(
                    "No hay ninguna declaracion jurada con identificador "
                            + id
                            + " en esta municipalidad");
        }
        return doblada;
    }

    private static DeclaracionJurada mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        long ficha = fila.getLong("ficha_catastral_id");
        Long fichaCatastralId = fila.wasNull() ? null : ficha;
        long rectifica = fila.getLong("dj_rectifica_id");
        Long djRectificaId = fila.wasNull() ? null : rectifica;

        return new DeclaracionJurada(
                fila.getLong("id"),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("contribuyente_id"),
                TipoDeDeclaracion.valueOf(fila.getString("tipo")),
                predioId,
                vehiculoId,
                fichaCatastralId,
                fila.getDate("fecha_presentacion").toLocalDate(),
                fila.getDate("fecha_limite").toLocalDate(),
                EstadoDeDeclaracion.valueOf(fila.getString("estado")),
                djRectificaId,
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
