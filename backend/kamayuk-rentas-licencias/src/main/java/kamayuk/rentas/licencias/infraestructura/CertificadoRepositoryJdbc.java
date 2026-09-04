package kamayuk.rentas.licencias.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.CertificadoRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCertificados;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los certificados contra PostgreSQL (V51).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE certificado} ni un {@code DELETE}: V51
 * crea la tabla sin conceder ninguno de los dos a {@code sgtm_app}, y el escaner de fuentes rechaza
 * esas dos cadenas antes de que lleguen a ejecutarse. El unico {@code UPDATE} de esta clase es el
 * del contador de {@code certificado_correlativo}, que es infraestructura de numeracion y no un
 * documento entregable (mismo criterio que {@code licencia_correlativo} en V37).
 *
 * <p><b>El numero repetido y la clave repetida los rechaza el indice, no un {@code if}.</b> Se
 * inserta y se traducen los dos choques —{@code certificado_numero_uq} y {@code
 * certificado_idempotencia_uq}— a excepciones distintas: son dos situaciones distintas y se
 * arreglan de maneras distintas. Diez peticiones simultaneas pasan las diez por cualquier
 * comprobacion escrita en Java.
 *
 * <p><b>La busqueda por codigo predial se escribe como rango.</b> Bajo RLS un {@code LIKE
 * 'prefijo%'} no llega nunca al indice (DAT-01 §0, hallazgo 3), asi que pasa por {@link
 * RangoDePrefijo} igual que las cuatro busquedas de texto de #44 y #51.
 */
@Repository
public class CertificadoRepositoryJdbc extends RepositorioJdbc implements CertificadoRepository {

    private static final String COLUMNAS =
            "id, numero, tipo, predio_id, contribuyente_id, codigo_predial, direccion, expediente,"
                    + " fecha_emision, vigencia_hasta, recibo_id, derecho, derecho_a, documento_id,"
                    + " documento_numero, zonificacion, altura_maxima, area_libre_minima,"
                    + " retiro_municipal, coeficiente_edificacion, clave_idempotencia,"
                    + " usuario_registro, fecha_registro, observacion";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre(
                    "numero", "fecha_emision", "tipo", "codigo_predial", "vigencia_hasta");

    public CertificadoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(TipoDeCertificado tipo, Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos emisiones concurrentes del mismo tipo y ejercicio se serializan en el motor y
        // salen con numeros consecutivos. Nunca un SELECT seguido de un UPDATE: entre los dos cabe
        // otra emision, y las dos leerian el mismo numero.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO certificado_correlativo (municipalidad_id, tipo,"
                                        + " ejercicio, ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :tipo, :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, tipo, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = certificado_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("tipo", tipo.name())
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public Certificado emitir(Certificado certificado) {
        if (!certificado.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un certificado ya emitido no se vuelve a insertar ni se corrige: se sustituye"
                            + " emitiendo otro");
        }

        ParametrosUrbanisticos parametros = certificado.parametros();
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO certificado"
                                            + " (municipalidad_id, numero, tipo, predio_id,"
                                            + "  contribuyente_id, codigo_predial, direccion,"
                                            + "  expediente, fecha_emision, vigencia_hasta,"
                                            + "  recibo_id, derecho, derecho_a, documento_id,"
                                            + "  documento_numero, zonificacion, altura_maxima,"
                                            + "  area_libre_minima, retiro_municipal,"
                                            + "  coeficiente_edificacion, clave_idempotencia,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :tipo, :predio, :contribuyente,"
                                            + "  :codigoPredial, :direccion, :expediente, :emision,"
                                            + "  :vigencia, :recibo, :derecho, :derechoA,"
                                            + "  :documento, :documentoNumero, :zonificacion,"
                                            + "  :altura, :areaLibre, :retiro, :coeficiente,"
                                            + "  :clave, :usuario, :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("numero", certificado.numero())
                            .param("tipo", certificado.tipo().name())
                            .param("predio", certificado.predioId())
                            .param("contribuyente", certificado.contribuyenteId())
                            .param("codigoPredial", certificado.codigoPredial())
                            .param("direccion", certificado.direccion())
                            .param("expediente", certificado.expediente())
                            .param("emision", certificado.fechaEmision())
                            .param("vigencia", certificado.vigenciaHasta())
                            .param("recibo", certificado.reciboId())
                            .param("derecho", certificado.derecho().valor())
                            .param("derechoA", certificado.derechoA())
                            .param("documento", certificado.documentoId())
                            .param("documentoNumero", certificado.documentoNumero())
                            .param("zonificacion", parametros.zonificacion())
                            .param("altura", parametros.alturaMaxima())
                            .param("areaLibre", parametros.areaLibreMinima())
                            .param("retiro", parametros.retiroMunicipal())
                            .param("coeficiente", parametros.coeficienteEdificacion())
                            .param("clave", certificado.claveIdempotencia())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(certificado.registradoEn()))
                            .param("observacion", certificado.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException choque) {
            throw traducir(certificado, choque);
        }

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El certificado recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<Certificado> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM certificado WHERE numero = :numero")
                .param("numero", numero == null ? "" : numero.strip())
                .query(CertificadoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Certificado> porClaveDeIdempotencia(String clave) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM certificado WHERE clave_idempotencia = :clave")
                .param("clave", clave == null ? "" : clave.strip())
                .query(CertificadoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Certificado> buscar(CriterioDeCertificados criterio, Paginacion paginacion) {
        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.numero() != null) {
            donde.append(" AND numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.tipo() != null) {
            donde.append(" AND tipo = :tipo");
            parametros.put("tipo", criterio.tipo().name());
        }
        if (criterio.codigoPredial() != null) {
            RangoDePrefijo.condicion(
                    donde,
                    parametros,
                    "codigo_predial",
                    criterio.codigoPredial().toUpperCase(Locale.ROOT),
                    "predial");
        }
        if (criterio.desde() != null) {
            donde.append(" AND fecha_emision >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND fecha_emision <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        Set<Long> solicitantes = criterio.solicitantes();
        if (solicitantes != null) {
            // Vacio no llega aqui: `ConsultaDeCertificados` devuelve la pagina vacia antes, porque
            // un `IN ()` no es SQL valido y un filtro ignorado devolveria el padron entero.
            donde.append(" AND contribuyente_id IN (:solicitantes)");
            parametros.put("solicitantes", solicitantes);
        }

        return paginar(
                "SELECT " + COLUMNAS + " FROM certificado" + donde,
                "SELECT count(*) FROM certificado" + donde,
                parametros,
                paginacion,
                ORDEN,
                CertificadoRepositoryJdbc::mapear);
    }

    // ------------------------------------------------------------------

    private Optional<Certificado> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM certificado WHERE id = :id")
                .param("id", id)
                .query(CertificadoRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * Cual de los dos indices unicos choco.
     *
     * <p>Se distinguen por el mensaje de PostgreSQL, que nombra la restriccion. La distincion
     * importa: la clave repetida <b>no</b> es un defecto —el cliente reintento— y el numero
     * repetido si lo seria.
     */
    private static RuntimeException traducir(
            Certificado certificado, DuplicateKeyException choque) {
        String mensaje = String.valueOf(choque.getMessage());
        if (mensaje.contains("certificado_idempotencia_uq")) {
            return new ClaveRepetida(
                    "Otra peticion con la misma clave de idempotencia ya emitio este certificado."
                            + " El reintento es legitimo; lo que no puede salir de el es un segundo"
                            + " papel con otro numero por el mismo derecho pagado",
                    choque);
        }
        return new NumeroDuplicado(
                "Ya existe el certificado "
                        + certificado.numero()
                        + " en esta municipalidad: dos certificados con el mismo numero no se"
                        + " pueden distinguir en el expediente que los cita",
                choque);
    }

    private static Certificado mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Certificado(
                fila.getLong("id"),
                fila.getString("numero"),
                TipoDeCertificado.porNombre(fila.getString("tipo")),
                fila.getLong("predio_id"),
                fila.getLong("contribuyente_id"),
                fila.getString("codigo_predial"),
                fila.getString("direccion"),
                fila.getString("expediente"),
                fila.getDate("fecha_emision").toLocalDate(),
                fila.getDate("vigencia_hasta").toLocalDate(),
                fila.getLong("recibo_id"),
                new Dinero(fila.getBigDecimal("derecho")),
                fila.getDate("derecho_a").toLocalDate(),
                fila.getLong("documento_id"),
                fila.getString("documento_numero"),
                new ParametrosUrbanisticos(
                        fila.getString("zonificacion"),
                        fila.getString("altura_maxima"),
                        fila.getString("area_libre_minima"),
                        fila.getString("retiro_municipal"),
                        fila.getString("coeficiente_edificacion")),
                fila.getString("clave_idempotencia"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
