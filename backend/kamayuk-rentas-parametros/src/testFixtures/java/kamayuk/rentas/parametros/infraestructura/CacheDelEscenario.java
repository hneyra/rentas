package kamayuk.rentas.parametros.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: la cache, leida de las tablas del escenario.
 *
 * <h2>Que se sustituye y que no</h2>
 *
 * <p>Se sustituye <b>el almacen</b>, y nada mas. Lo que queda encima es {@link
 * kamayuk.rentas.parametros.aplicacion.LectorDeParametrosCacheados} <b>tal cual corre en
 * ventanilla</b>, con su resolucion de vigencias (#659), su reparto entre «lo vigente» y «el
 * conjunto que la determinacion guardo», y su rechazo de dos filas de la misma llave.
 *
 * <p><b>Por que se sustituye.</b> En produccion la cache se llena con una descarga, que ESCRIBE, y
 * escribir dentro de la lectura de otro es imposible: por eso {@code DescargaDeNormativa} abre su
 * propia transaccion. Veinte clases de prueba construyen su lector a mano, sin gestor de
 * transacciones que pueda abrir otra, asi que aqui la premisa se da por ya descargada — que es
 * exactamente lo que esas pruebas quieren decir con «esta municipalidad tiene un conjunto sellado
 * con estos valores».
 *
 * <p>Lo que <b>no</b> queda cubierto por esta via —la descarga, la huella y la transaccion propia—
 * tiene sus propias pruebas: {@code SnapshotDeNormativaFronteraTest} contra PostgreSQL y {@code
 * ClienteHttpDeNormativaTest} contra respuestas fabricadas.
 *
 * <p><b>El filtro de municipalidad va escrito a mano</b>, y en produccion no hace falta: alli
 * {@code conjunto_parametros} es tabla de tenant y su politica RLS lo pone sola. Las tablas del
 * escenario no llevan RLS —no son datos de nadie— asi que aqui hay que escribirlo, y omitirlo tuvo
 * consecuencias medidas: sin el, una prueba con dos municipalidades resolvia «el conjunto vigente»
 * al de la vecina —el de mayor id— y la valorizacion salia sin cuadro sobre un expediente cuya
 * municipalidad si lo tenia sellado.
 */
public class CacheDelEscenario implements CacheDeSnapshots {

    private final JdbcClient jdbc;

    public CacheDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tiene(long conjuntoId, String ambito) {
        return jdbc.sql(
                                """
                        SELECT count(*) FROM conjunto_parametros_de_prueba
                         WHERE id = :conjunto AND estado = 'SELLADO'
                                                   AND (municipalidad_id IS NULL
                                                        OR municipalidad_id
                                                           = NULLIF(current_setting('app.municipalidad_id', true), '')::bigint)
                        """)
                        .param("conjunto", conjuntoId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public Optional<Long> conjuntoCacheadoDe(Ejercicio ejercicio) {
        return jdbc.sql(
                        """
                        SELECT id FROM conjunto_parametros_de_prueba
                         WHERE ejercicio = :ejercicio AND estado = 'SELLADO'
                                                   AND (municipalidad_id IS NULL
                                                        OR municipalidad_id
                                                           = NULLIF(current_setting('app.municipalidad_id', true), '')::bigint)
                         ORDER BY version DESC, id DESC LIMIT 1
                        """)
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<IdentidadDelConjunto> identidadDe(long conjuntoId) {
        return jdbc.sql(
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
                                new IdentidadDelConjunto(
                                        new Ejercicio(fila.getInt("ejercicio")),
                                        fila.getInt("version")))
                .optional();
    }

    @Override
    public List<SnapshotDeNormativa.Parametro> parametrosDe(long conjuntoId) {
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

    /** No hace nada: en una prueba el escenario ya esta escrito. */
    @Override
    public void guardar(SnapshotDeNormativa snapshot) {
        // Intencionalmente vacio. Ver el javadoc de la clase.
    }

    private static String texto(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : valor.toString();
    }
}
