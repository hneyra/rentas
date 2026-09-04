package kamayuk.rentas.parametros.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: {@code normativa}, servido desde las tablas {@code _de_prueba}.
 *
 * <h2>Que hace, y por que es fiel</h2>
 *
 * <p>Compone el snapshot con <b>las mismas cuatro consultas</b> que {@code ComponerSnapshot}/{@code
 * SnapshotRepositoryJdbc} hacen en {@code normativa}: el conjunto solo si esta SELLADO, sus
 * parametros por {@code conjunto_parametro_detalle}, y los tres cuadros por el {@code
 * publicacion_id} que ese detalle compuso. Lo unico que cambia es de que tablas —las {@code
 * _de_prueba} en vez de las reales, porque las reales se fueron a otro repositorio— y que no hay
 * HTTP de por medio.
 *
 * <p><b>Y lo que NO se sustituye es lo que se quiere probar.</b> Lo que este puerto alimenta es la
 * cadena de produccion entera: {@code LectorDeParametrosCacheados} resuelve el conjunto, descarga
 * una vez, escribe la cache local de `V3` y a partir de ahi lee de ella. Las veinte clases de
 * prueba que sembraban {@code parametro_tributario} siguen sembrando lo mismo con otro nombre de
 * tabla, y lo que ejercitan es el camino nuevo.
 *
 * <p>La huella es la de un escenario y se declara como tal: {@code sha256} de ceros, sin verificar.
 * Verificarla aqui probaria que este fixture sabe calcular sha256; lo que hay que probar es que el
 * <b>cliente HTTP</b> la comprueba, y eso lo hace {@code ClienteHttpDeNormativaTest} contra
 * respuestas fabricadas.
 *
 * <p><b>El filtro de municipalidad va escrito a mano</b>, y en produccion no hace falta: alli
 * {@code conjunto_parametros} es tabla de tenant y su politica RLS lo pone sola. Las tablas del
 * escenario no llevan RLS —no son datos de nadie— asi que aqui hay que escribirlo, y omitirlo tuvo
 * consecuencias medidas: sin el, una prueba con dos municipalidades resolvia «el conjunto vigente»
 * al de la vecina —el de mayor id— y la valorizacion salia sin cuadro sobre un expediente cuya
 * municipalidad si lo tenia sellado.
 */
public class NormativaDePrueba implements PublicadorDeNormativa {

    private final JdbcClient jdbc;

    public NormativaDePrueba(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long conjuntoVigenteEn(Ejercicio ejercicio) {
        return jdbc.sql(
                        """
                        SELECT id FROM conjunto_parametros_de_prueba
                         WHERE ejercicio = :ejercicio AND estado = 'SELLADO'
                                                   AND (municipalidad_id IS NULL
                                                        OR municipalidad_id
                                                           = NULLIF(current_setting('app.municipalidad_id', true), '')::bigint)
                         ORDER BY version DESC, id DESC
                         LIMIT 1
                        """)
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .optional()
                .orElseThrow(
                        () ->
                                new kamayuk.rentas.parametros.LectorDeParametros.EjercicioSinSellar(
                                        ejercicio));
    }

    @Override
    public SnapshotDeNormativa descargar(long conjuntoId, String ambito) {
        Fila cabecera =
                jdbc.sql(
                                """
                                SELECT ejercicio, version FROM conjunto_parametros_de_prueba
                                 WHERE id = :conjunto AND estado = 'SELLADO'
                                                   AND (municipalidad_id IS NULL
                                                        OR municipalidad_id
                                                           = NULLIF(current_setting('app.municipalidad_id', true), '')::bigint)
                                """)
                        .param("conjunto", conjuntoId)
                        .query(
                                (ResultSet fila, int numero) ->
                                        new Fila(fila.getInt("ejercicio"), fila.getInt("version")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new kamayuk.rentas.parametros.LectorDeParametros
                                                .ConjuntoNoSellado(
                                                kamayuk.rentas.parametros.IdentificadorDeConjunto
                                                        .de(conjuntoId)));

        return new SnapshotDeNormativa(
                conjuntoId,
                new Ejercicio(cabecera.ejercicio()),
                cabecera.version(),
                ambito,
                "0".repeat(64),
                "escenario de prueba",
                parametros(conjuntoId),
                valoresUnitarios(conjuntoId),
                depreciaciones(conjuntoId),
                valoresReferenciales(conjuntoId));
    }

    private record Fila(int ejercicio, int version) {}

    private List<SnapshotDeNormativa.Parametro> parametros(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT p.tipo, p.clave, p.valor_numerico, p.valor_texto,
                               p.vigencia_desde, p.vigencia_hasta, p.documento_fuente
                          FROM parametro_tributario_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d ON d.parametro_id = p.id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY p.id
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.Parametro(
                                        fila.getString("tipo"),
                                        fila.getString("clave"),
                                        texto(fila, "valor_numerico"),
                                        fila.getString("valor_texto"),
                                        texto(fila, "vigencia_desde"),
                                        texto(fila, "vigencia_hasta"),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.ValorUnitario> valoresUnitarios(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT v.partida, v.categoria, v.anio_construccion_desde,
                               v.anio_construccion_hasta, v.valor_m2, v.documento_fuente
                          FROM valor_unitario_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY v.partida, v.categoria, v.anio_construccion_desde
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.ValorUnitario(
                                        fila.getString("partida"),
                                        fila.getString("categoria"),
                                        fila.getInt("anio_construccion_desde"),
                                        entero(fila, "anio_construccion_hasta"),
                                        fila.getBigDecimal("valor_m2").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.Depreciacion> depreciaciones(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT p.uso, p.material, p.estado_conservacion, p.antiguedad_hasta,
                               p.porcentaje, p.documento_fuente
                          FROM depreciacion_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = p.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY p.uso, p.material, p.estado_conservacion,
                                  p.antiguedad_hasta NULLS LAST
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.Depreciacion(
                                        fila.getString("uso"),
                                        fila.getString("material"),
                                        fila.getString("estado_conservacion"),
                                        entero(fila, "antiguedad_hasta"),
                                        fila.getBigDecimal("porcentaje").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.ValorReferencial> valoresReferenciales(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT v.ejercicio, v.categoria, v.marca, v.modelo, v.anio_fabricacion,
                               v.valor, v.documento_fuente
                          FROM valor_referencial_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY v.categoria, v.marca, v.modelo, v.anio_fabricacion
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.ValorReferencial(
                                        fila.getInt("ejercicio"),
                                        fila.getString("categoria"),
                                        fila.getString("marca"),
                                        fila.getString("modelo"),
                                        fila.getInt("anio_fabricacion"),
                                        fila.getBigDecimal("valor").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private static String texto(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : valor.toString();
    }

    private static Integer entero(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : ((Number) valor).intValue();
    }
}
