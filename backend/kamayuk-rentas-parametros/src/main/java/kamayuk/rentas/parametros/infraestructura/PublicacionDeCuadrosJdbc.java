package kamayuk.rentas.parametros.infraestructura;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.PublicacionDeCuadros;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Publicacion de cuadros normativos nacionales (D-13, ADR-0017).
 *
 * <p>{@code @Profile("batch")} por lo mismo que {@link PublicacionDeParametrosJdbc}: la escritura
 * de {@code valor_referencial_vehiculo} —y de las otras dos tablas de valuacion— solo la puede
 * ejecutar {@code rol_carga_parametros} desde V55, y esa credencial la lleva el Job de un solo uso.
 * Un bean disponible en el perfil por omision seria un camino que existe y no funciona.
 *
 * <p>Ninguna sentencia menciona {@code municipalidad_id} (regla 2), y la insercion no lo escribe:
 * la columna se queda nula, que es lo que {@code valor_referencial_nacional_ck} exige. No hay
 * contexto de tenant que fijar porque el dato no es de ninguna municipalidad.
 */
@Repository
@Profile("batch")
public class PublicacionDeCuadrosJdbc implements PublicacionDeCuadros {

    private final JdbcClient jdbc;

    public PublicacionDeCuadrosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Edicion> edicionPublicada(LlaveDeParametro llave) {
        // IS NOT DISTINCT FROM y no `=`, por lo mismo que en PublicacionDeParametrosJdbc: la clave
        // admite nulo, y `clave = NULL` no devuelve ninguna fila ni falla —la peor de las dos
        // respuestas—. Con `=`, una segunda corrida abriria una edicion homonima y el conjunto ya
        // no podria decir cual sello.
        return jdbc.sql(
                        "SELECT id, sellado FROM parametro_tributario"
                                + " WHERE tipo = :tipo"
                                + "   AND clave IS NOT DISTINCT FROM :clave"
                                + "   AND vigencia_desde = :desde"
                                + " ORDER BY id")
                .param("tipo", llave.tipo())
                .param("clave", llave.clave())
                .param("desde", Date.valueOf(llave.vigenciaDesde()))
                .query(
                        (fila, numero) ->
                                new Edicion(fila.getLong("id"), fila.getBoolean("sellado")))
                .optional();
    }

    @Override
    public long abrirEdicion(ParametroTributario cabecera, String transcribio, String verifico) {
        return jdbc.sql(
                        "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                + " valor_numerico, valor_texto, vigencia_desde, vigencia_hasta,"
                                + " documento_fuente, usuario_carga, usuario_aprueba)"
                                + " VALUES (NULL, :tipo, :clave, :numerico, :texto, :desde, :hasta,"
                                + " :fuente, :carga, :aprueba) RETURNING id")
                .param("tipo", cabecera.tipo())
                .param("clave", cabecera.clave())
                .param("numerico", cabecera.numero().map(ValorNormativo::valor).orElse(null))
                .param("texto", cabecera.valorTexto())
                .param("desde", fecha(cabecera.vigencia().desde()))
                .param("hasta", fecha(cabecera.vigencia().hasta()))
                .param("fuente", cabecera.documentoFuente())
                .param("carga", transcribio)
                .param("aprueba", verifico)
                .query(Long.class)
                .single();
    }

    @Override
    public void agregarDepreciacion(
            long edicion,
            String uso,
            String material,
            String estadoConservacion,
            @Nullable Integer antiguedadHasta,
            Alicuota porcentaje,
            String documentoFuente) {
        jdbc.sql(
                        "INSERT INTO depreciacion (publicacion_id, uso, material,"
                                + " estado_conservacion, antiguedad_hasta, porcentaje,"
                                + " documento_fuente)"
                                + " VALUES (:edicion, :uso, :material, :estado, :antiguedad,"
                                + " :porcentaje, :fuente)")
                .param("edicion", edicion)
                .param("uso", uso)
                .param("material", material)
                .param("estado", estadoConservacion)
                // Nulo es «mas de 50 anios» y entra tal cual: depreciacion_uq es NULLS NOT
                // DISTINCT (V57), asi que el tramo abierto no se puede duplicar.
                .param("antiguedad", antiguedadHasta)
                .param("porcentaje", porcentaje.valor())
                .param("fuente", documentoFuente)
                .update();
    }

    @Override
    public void agregarValorReferencial(
            long edicion,
            int ejercicio,
            String categoria,
            String marca,
            String modelo,
            int anioFabricacion,
            Dinero valor,
            String documentoFuente) {
        jdbc.sql(
                        "INSERT INTO valor_referencial_vehiculo (publicacion_id, ejercicio,"
                                + " categoria, marca, modelo, anio_fabricacion, valor,"
                                + " documento_fuente)"
                                + " VALUES (:edicion, :ejercicio, :categoria, :marca, :modelo,"
                                + " :anio, :valor, :fuente)")
                .param("edicion", edicion)
                .param("ejercicio", ejercicio)
                .param("categoria", categoria)
                .param("marca", marca)
                .param("modelo", modelo)
                .param("anio", anioFabricacion)
                .param("valor", valor.valor())
                .param("fuente", documentoFuente)
                .update();
    }

    @Override
    public void cerrar(long edicion) {
        jdbc.sql("UPDATE parametro_tributario SET sellado = true WHERE id = :edicion")
                .param("edicion", edicion)
                .update();
    }

    private static @Nullable Date fecha(@Nullable LocalDate valor) {
        return valor == null ? null : Date.valueOf(valor);
    }
}
