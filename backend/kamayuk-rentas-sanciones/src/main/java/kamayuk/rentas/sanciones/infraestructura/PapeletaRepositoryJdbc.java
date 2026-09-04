package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las papeletas —de las dos familias, #46 y #47— contra PostgreSQL. Sigue la plantilla de {@code
 * CodigoInfraccionRepositoryJdbc} (#43): ninguna consulta filtra por {@code municipalidad_id} —lo
 * hace la política RLS—, y no hay ningún {@code DELETE} (regla 4, RNF-051; {@code papeleta} está en
 * {@code TABLAS_PROTEGIDAS}).
 */
@Repository
public class PapeletaRepositoryJdbc extends RepositorioJdbc implements PapeletaRepository {

    private static final String COLUMNAS =
            "p.id, p.familia, p.numero, p.codigo_infraccion_id, p.fecha_infraccion,"
                    + " p.hora_infraccion, p.lugar, p.placa, p.vehiculo_id, p.licencia_conducir,"
                    + " p.infractor_id, p.propietario_id, p.contribuyente_id, p.predio_id,"
                    + " p.notificacion_previa_id, p.obligado_id, p.base_imponible,"
                    + " p.porcentaje_infraccion,"
                    + " p.importe_infraccion, p.porcentaje_a_cobrar, p.importe_a_pagar,"
                    + " p.importe_con_beneficio, p.estado, p.usuario_registro, p.observacion";

    private static final String DESDE = " FROM papeleta p";

    private static final OrdenSeguro ORDEN = OrdenSeguro.sobre("fecha_infraccion", "numero", "id");

    public PapeletaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Papeleta insertar(Papeleta papeleta) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("familia", papeleta.familia().name());
        campos.put("numero", papeleta.numero());
        campos.put("codigoInfraccionId", papeleta.codigoInfraccionId());
        campos.put("fechaInfraccion", papeleta.fechaInfraccion());
        campos.put(
                "horaInfraccion",
                papeleta.horaInfraccion() == null ? null : Time.valueOf(papeleta.horaInfraccion()));
        campos.put("lugar", papeleta.lugar());
        campos.put("placa", papeleta.placa());
        campos.put("vehiculoId", papeleta.vehiculoId());
        campos.put("licenciaConducir", papeleta.licenciaConducir());
        campos.put("infractorId", papeleta.infractorId());
        campos.put("propietarioId", papeleta.propietarioId());
        campos.put("contribuyenteId", papeleta.contribuyenteId());
        campos.put("predioId", papeleta.predioId());
        campos.put("notificacionPreviaId", papeleta.notificacionPreviaId());
        campos.put("obligadoId", papeleta.obligadoId());
        campos.put("baseImponible", papeleta.baseImponible().valor());
        campos.put("porcentajeInfraccion", papeleta.porcentajeInfraccion().valor());
        campos.put("importeInfraccion", papeleta.importeInfraccion().valor());
        campos.put("porcentajeACobrar", papeleta.porcentajeACobrar().valor());
        campos.put("importeAPagar", papeleta.importeAPagar().valor());
        campos.put(
                "importeConBeneficio",
                papeleta.importeConBeneficio() == null
                        ? null
                        : papeleta.importeConBeneficio().valor());
        campos.put("estado", papeleta.estado().name());
        campos.put("observacion", papeleta.observacion().texto());
        String usuario = kamayuk.rentas.auditoria.OrigenContext.actual().usuario();
        campos.put("usuario", usuario);

        Long id =
                jdbc().sql(
                                "INSERT INTO papeleta"
                                        + " (municipalidad_id, familia, numero, codigo_infraccion_id,"
                                        + "  fecha_infraccion, hora_infraccion, lugar, placa,"
                                        + "  vehiculo_id, licencia_conducir, infractor_id,"
                                        + "  propietario_id, contribuyente_id, predio_id,"
                                        + "  notificacion_previa_id, obligado_id, base_imponible,"
                                        + "  porcentaje_infraccion, importe_infraccion,"
                                        + "  porcentaje_a_cobrar, importe_a_pagar,"
                                        + "  importe_con_beneficio, estado, usuario_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :familia, :numero, :codigoInfraccionId,"
                                        + "  :fechaInfraccion, :horaInfraccion, :lugar, :placa,"
                                        + "  :vehiculoId, :licenciaConducir, :infractorId,"
                                        + "  :propietarioId, :contribuyenteId, :predioId,"
                                        + "  :notificacionPreviaId, :obligadoId, :baseImponible,"
                                        + "  :porcentajeInfraccion, :importeInfraccion,"
                                        + "  :porcentajeACobrar, :importeAPagar,"
                                        + "  :importeConBeneficio, :estado, :usuario, :observacion)"
                                        + " RETURNING id")
                        .params(campos)
                        .query(Long.class)
                        .single();

        return conId(papeleta, id, usuario);
    }

    @Override
    public Optional<Papeleta> porNumero(String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE p.familia = 'TRANSITO' AND p.numero = :numero")
                .param("numero", numero.strip().toUpperCase(java.util.Locale.ROOT))
                .query(PapeletaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Papeleta> porNumero(Familia familia, String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE p.familia = :familia AND p.numero = :numero")
                .param("familia", familia.name())
                .param("numero", numero.strip().toUpperCase(java.util.Locale.ROOT))
                .query(PapeletaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Papeleta> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE p.id = :id")
                .param("id", id)
                .query(PapeletaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Papeleta> buscar(CriterioDePapeleta criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();
        String desde = DESDE;

        condiciones.add("p.familia = :familia");
        parametros.put("familia", criterio.familia().name());

        if (criterio.numero() != null) {
            condiciones.add("p.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.placa() != null) {
            condiciones.add("p.placa = :placa");
            parametros.put("placa", criterio.placa());
        }
        if (criterio.documentoInfractor() != null) {
            desde = desde + " JOIN contribuyente ci ON ci.id = p.infractor_id";
            condiciones.add("ci.numero_documento = :documentoInfractor");
            parametros.put("documentoInfractor", criterio.documentoInfractor());
        }
        if (criterio.documentoAdministrado() != null) {
            desde = desde + " JOIN contribuyente ca ON ca.id = p.contribuyente_id";
            condiciones.add("ca.numero_documento = :documentoAdministrado");
            parametros.put("documentoAdministrado", criterio.documentoAdministrado());
        }
        if (criterio.codigoInfraccion() != null) {
            desde = desde + " JOIN codigo_infraccion cx ON cx.id = p.codigo_infraccion_id";
            condiciones.add("cx.codigo = :codigoInfraccion");
            parametros.put("codigoInfraccion", criterio.codigoInfraccion());
        }
        if (criterio.desde() != null) {
            condiciones.add("p.fecha_infraccion >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            condiciones.add("p.fecha_infraccion <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        if (criterio.estado() != null) {
            condiciones.add("p.estado = :estado");
            parametros.put("estado", criterio.estado().name());
        }
        if (criterio.ingresadoPor() != null) {
            condiciones.add("p.usuario_registro = :ingresadoPor");
            parametros.put("ingresadoPor", criterio.ingresadoPor());
        }
        if (criterio.soloPendientes()) {
            condiciones.add("p.estado NOT IN ('PAGADA', 'ANULADA', 'PRESCRITA')");
        }

        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + desde + donde,
                "SELECT count(*)" + desde + donde,
                parametros,
                paginacion,
                ORDEN,
                PapeletaRepositoryJdbc::mapear);
    }

    @Override
    public Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo) {
        String nuevoLimpio = numeroNuevo.strip().toUpperCase(java.util.Locale.ROOT);
        String usuario = kamayuk.rentas.auditoria.OrigenContext.actual().usuario();

        String numeroAnterior =
                jdbc().sql("SELECT numero" + DESDE + " WHERE p.id = :id")
                        .param("id", papeletaId)
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No hay ninguna papeleta con identificador "
                                                        + papeletaId
                                                        + " en esta municipalidad"));

        jdbc().sql("UPDATE papeleta SET numero = :numeroNuevo WHERE id = :id")
                .param("numeroNuevo", nuevoLimpio)
                .param("id", papeletaId)
                .update();

        jdbc().sql(
                        "INSERT INTO papeleta_cambio_numero"
                                + " (municipalidad_id, papeleta_id, numero_anterior, numero_nuevo,"
                                + "  usuario, motivo)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :papeletaId, :numeroAnterior, :numeroNuevo, :usuario, :motivo)")
                .param("papeletaId", papeletaId)
                .param("numeroAnterior", numeroAnterior)
                .param("numeroNuevo", nuevoLimpio)
                .param("usuario", usuario)
                .param("motivo", motivo)
                .update();

        return porNumero(nuevoLimpio)
                .orElseThrow(
                        () -> new IllegalStateException("La papeleta desaparecio tras el cambio"));
    }

    private static Papeleta conId(Papeleta papeleta, long id, String usuarioRegistro) {
        return new Papeleta(
                id,
                papeleta.familia(),
                papeleta.numero(),
                papeleta.codigoInfraccionId(),
                papeleta.fechaInfraccion(),
                papeleta.horaInfraccion(),
                papeleta.lugar(),
                papeleta.placa(),
                papeleta.vehiculoId(),
                papeleta.licenciaConducir(),
                papeleta.infractorId(),
                papeleta.propietarioId(),
                papeleta.contribuyenteId(),
                papeleta.predioId(),
                papeleta.notificacionPreviaId(),
                papeleta.obligadoId(),
                papeleta.baseImponible(),
                papeleta.porcentajeInfraccion(),
                papeleta.importeInfraccion(),
                papeleta.porcentajeACobrar(),
                papeleta.importeAPagar(),
                papeleta.importeConBeneficio(),
                papeleta.estado(),
                usuarioRegistro,
                papeleta.observacion());
    }

    private static Papeleta mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        Time hora = fila.getTime("hora_infraccion");
        LocalTime horaInfraccion = hora == null ? null : hora.toLocalTime();
        Long vehiculoId = (Long) fila.getObject("vehiculo_id");
        Long infractorId = (Long) fila.getObject("infractor_id");
        Long propietarioId = (Long) fila.getObject("propietario_id");
        Long contribuyenteId = (Long) fila.getObject("contribuyente_id");
        Long predioId = (Long) fila.getObject("predio_id");
        Long notificacionPreviaId = (Long) fila.getObject("notificacion_previa_id");
        java.math.BigDecimal importeConBeneficio = fila.getBigDecimal("importe_con_beneficio");

        return new Papeleta(
                fila.getLong("id"),
                Familia.valueOf(fila.getString("familia")),
                fila.getString("numero"),
                fila.getLong("codigo_infraccion_id"),
                fila.getDate("fecha_infraccion").toLocalDate(),
                horaInfraccion,
                fila.getString("lugar"),
                fila.getString("placa"),
                vehiculoId,
                fila.getString("licencia_conducir"),
                infractorId,
                propietarioId,
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                fila.getLong("obligado_id"),
                new Dinero(fila.getBigDecimal("base_imponible")),
                new Alicuota(fila.getBigDecimal("porcentaje_infraccion")),
                new Dinero(fila.getBigDecimal("importe_infraccion")),
                new Alicuota(fila.getBigDecimal("porcentaje_a_cobrar")),
                new Dinero(fila.getBigDecimal("importe_a_pagar")),
                importeConBeneficio == null ? null : new Dinero(importeConBeneficio),
                EstadoDePapeleta.valueOf(fila.getString("estado")),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
