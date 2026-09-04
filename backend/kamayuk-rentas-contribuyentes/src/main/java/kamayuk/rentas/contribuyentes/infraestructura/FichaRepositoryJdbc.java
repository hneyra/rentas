package kamayuk.rentas.contribuyentes.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.FichaRepository;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import kamayuk.rentas.contribuyentes.dominio.TipoContacto;
import kamayuk.rentas.contribuyentes.dominio.TipoDomicilio;
import kamayuk.rentas.contribuyentes.dominio.Vinculo;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Domicilios, contactos y responsables solidarios contra PostgreSQL.
 *
 * <p>Las consultas de vigencia comparan <b>en SQL</b> y no en memoria: filtrar en Java obligaria a
 * traer todo el historial de cada contribuyente para quedarse con una fila, y en una emision masiva
 * eso son cientos de miles de filas que no se usan.
 *
 * <p>Ningun {@code DELETE}: los domicilios y los vinculos se cierran, los contactos se dan de baja.
 */
@Repository
public class FichaRepositoryJdbc extends RepositorioJdbc implements FichaRepository {

    private static final String COLUMNAS_DOMICILIO =
            "id, contribuyente_id, tipo, direccion, referencia, ubigeo, vigencia_desde,"
                    + " vigencia_hasta, documento_origen";

    private static final String COLUMNAS_CONTACTO =
            "id, contribuyente_id, tipo, valor, nombre, documento, observacion, vigente";

    private static final String COLUMNAS_RESPONSABLE =
            "id, contribuyente_id, responsable_id, vinculo, porcentaje, vigencia_desde,"
                    + " vigencia_hasta, documento_origen";

    /**
     * Rige en la fecha: empezo antes o ese mismo dia, y o sigue abierto o se cerro despues o ese
     * mismo dia. Los dos extremos entran, igual que en {@code Domicilio.rigeEn}.
     */
    private static final String VIGENTE_A_LA_FECHA =
            " vigencia_desde <= :fecha"
                    + " AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)";

    public FichaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    // ---------- Domicilios ----------

    @Override
    public Optional<Domicilio> domicilioVigenteA(
            long contribuyenteId, TipoDomicilio tipo, LocalDate fecha) {
        Objects.requireNonNull(fecha, "La direccion se pide a una fecha, nunca «la ultima»");
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_DOMICILIO
                                + " FROM domicilio"
                                + " WHERE contribuyente_id = :contribuyente"
                                + "   AND tipo = :tipo"
                                + "   AND"
                                + VIGENTE_A_LA_FECHA
                                // Un indice parcial garantiza uno solo abierto, pero a una fecha
                                // pasada puede haber dos si alguien registro solapado: gana el que
                                // empezo despues, que es el que la notificacion habria usado.
                                + " ORDER BY vigencia_desde DESC, id DESC"
                                + " LIMIT 1")
                .param("contribuyente", contribuyenteId)
                .param("tipo", tipo.name())
                .param("fecha", fecha)
                .query(FichaRepositoryJdbc::mapearDomicilio)
                .optional();
    }

    @Override
    public List<Domicilio> historialDeDomicilios(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_DOMICILIO
                                + " FROM domicilio"
                                + " WHERE contribuyente_id = :contribuyente"
                                + " ORDER BY vigencia_desde DESC, id DESC")
                .param("contribuyente", contribuyenteId)
                .query(FichaRepositoryJdbc::mapearDomicilio)
                .list();
    }

    @Override
    public Domicilio guardar(Domicilio domicilio) {
        return domicilio.esNuevo() ? insertar(domicilio) : actualizar(domicilio);
    }

    private Domicilio insertar(Domicilio domicilio) {
        Long id =
                jdbc().sql(
                                "INSERT INTO domicilio"
                                        + " (municipalidad_id, contribuyente_id, tipo, direccion,"
                                        + "  referencia, ubigeo, vigencia_desde, vigencia_hasta,"
                                        + "  documento_origen)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :contribuyente, :tipo, :direccion, :referencia,"
                                        + "  :ubigeo, :desde, :hasta, :documento)"
                                        + " RETURNING id")
                        .param("contribuyente", domicilio.contribuyenteId())
                        .param("tipo", domicilio.tipo().name())
                        .param("direccion", domicilio.direccion())
                        .param("referencia", domicilio.referencia())
                        .param("ubigeo", domicilio.ubigeo())
                        .param("desde", domicilio.vigenciaDesde())
                        .param("hasta", domicilio.vigenciaHasta())
                        .param("documento", domicilio.documentoOrigen())
                        .query(Long.class)
                        .single();
        return new Domicilio(
                id,
                domicilio.contribuyenteId(),
                domicilio.tipo(),
                domicilio.direccion(),
                domicilio.referencia(),
                domicilio.ubigeo(),
                domicilio.vigenciaDesde(),
                domicilio.vigenciaHasta(),
                domicilio.documentoOrigen());
    }

    /**
     * Lo unico que se actualiza de un domicilio es su cierre.
     *
     * <p>La direccion no: cambiar de direccion es abrir otro domicilio. Por eso el {@code UPDATE}
     * toca una sola columna, y ademas exige que siguiera abierta — asi dos cierres simultaneos no
     * se pisan y el segundo se entera.
     */
    private Domicilio actualizar(Domicilio domicilio) {
        long id = Objects.requireNonNull(domicilio.id(), "Un domicilio existente tiene id");
        int filas =
                jdbc().sql(
                                "UPDATE domicilio SET vigencia_hasta = :hasta"
                                        + " WHERE id = :id AND vigencia_hasta IS NULL")
                        .param("id", id)
                        .param("hasta", domicilio.vigenciaHasta())
                        .update();
        if (filas == 0) {
            throw new DomicilioNoVigente(id);
        }
        return domicilio;
    }

    // ---------- Contactos ----------

    @Override
    public List<Contacto> contactosDe(long contribuyenteId, boolean soloVigentes) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_CONTACTO
                                + " FROM contacto"
                                + " WHERE contribuyente_id = :contribuyente"
                                + (soloVigentes ? " AND vigente" : "")
                                + " ORDER BY tipo, id")
                .param("contribuyente", contribuyenteId)
                .query(FichaRepositoryJdbc::mapearContacto)
                .list();
    }

    @Override
    public Contacto guardar(Contacto contacto) {
        if (contacto.esNuevo()) {
            Long id =
                    jdbc().sql(
                                    "INSERT INTO contacto"
                                            + " (municipalidad_id, contribuyente_id, tipo, valor,"
                                            + "  nombre, documento, observacion, vigente)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :contribuyente, :tipo, :valor, :nombre,"
                                            + "  :documento, :observacion, :vigente)"
                                            + " RETURNING id")
                            .param("contribuyente", contacto.contribuyenteId())
                            .param("tipo", contacto.tipo().name())
                            .param("valor", contacto.valor())
                            .param("nombre", contacto.nombre())
                            .param("documento", contacto.documento())
                            .param("observacion", contacto.observacion())
                            .param("vigente", contacto.vigente())
                            .query(Long.class)
                            .single();
            return new Contacto(
                    id,
                    contacto.contribuyenteId(),
                    contacto.tipo(),
                    contacto.valor(),
                    contacto.nombre(),
                    contacto.documento(),
                    contacto.observacion(),
                    contacto.vigente());
        }

        long id = Objects.requireNonNull(contacto.id(), "Un contacto existente tiene id");
        int filas =
                jdbc().sql(
                                "UPDATE contacto"
                                        + "   SET valor = :valor, nombre = :nombre,"
                                        + "       documento = :documento,"
                                        + "       observacion = :observacion, vigente = :vigente"
                                        + " WHERE id = :id")
                        .param("id", id)
                        .param("valor", contacto.valor())
                        .param("nombre", contacto.nombre())
                        .param("documento", contacto.documento())
                        .param("observacion", contacto.observacion())
                        .param("vigente", contacto.vigente())
                        .update();
        if (filas == 0) {
            throw new ContactoNoEncontrado(id);
        }
        return contacto;
    }

    // ---------- Responsables solidarios ----------

    @Override
    public List<ResponsableSolidario> responsablesDe(long contribuyenteId, LocalDate fecha) {
        Objects.requireNonNull(fecha, "Quien responde se pregunta a una fecha (regla 9)");
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_RESPONSABLE
                                + " FROM responsable_solidario"
                                + " WHERE contribuyente_id = :contribuyente AND"
                                + VIGENTE_A_LA_FECHA
                                + " ORDER BY vinculo, id")
                .param("contribuyente", contribuyenteId)
                .param("fecha", fecha)
                .query(FichaRepositoryJdbc::mapearResponsable)
                .list();
    }

    @Override
    public List<ResponsableSolidario> responsabilidadesDe(long responsableId, LocalDate fecha) {
        Objects.requireNonNull(fecha, "De quien responde se pregunta a una fecha (regla 9)");
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_RESPONSABLE
                                + " FROM responsable_solidario"
                                + " WHERE responsable_id = :responsable AND"
                                + VIGENTE_A_LA_FECHA
                                + " ORDER BY vinculo, id")
                .param("responsable", responsableId)
                .param("fecha", fecha)
                .query(FichaRepositoryJdbc::mapearResponsable)
                .list();
    }

    @Override
    public ResponsableSolidario guardar(ResponsableSolidario responsable) {
        if (responsable.esNuevo()) {
            Long id =
                    jdbc().sql(
                                    "INSERT INTO responsable_solidario"
                                            + " (municipalidad_id, contribuyente_id,"
                                            + "  responsable_id, vinculo, porcentaje,"
                                            + "  vigencia_desde, vigencia_hasta, documento_origen)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :contribuyente, :responsable, :vinculo,"
                                            + "  :porcentaje, :desde, :hasta, :documento)"
                                            + " RETURNING id")
                            .param("contribuyente", responsable.contribuyenteId())
                            .param("responsable", responsable.responsableId())
                            .param("vinculo", responsable.vinculo().name())
                            .param(
                                    "porcentaje",
                                    responsable.porcentaje() == null
                                            ? null
                                            : responsable.porcentaje().valor())
                            .param("desde", responsable.vigenciaDesde())
                            .param("hasta", responsable.vigenciaHasta())
                            .param("documento", responsable.documentoOrigen())
                            .query(Long.class)
                            .single();
            return new ResponsableSolidario(
                    id,
                    responsable.contribuyenteId(),
                    responsable.responsableId(),
                    responsable.vinculo(),
                    responsable.porcentaje(),
                    responsable.vigenciaDesde(),
                    responsable.vigenciaHasta(),
                    responsable.documentoOrigen());
        }

        long id = Objects.requireNonNull(responsable.id(), "Un vinculo existente tiene id");
        int filas =
                jdbc().sql(
                                "UPDATE responsable_solidario SET vigencia_hasta = :hasta"
                                        + " WHERE id = :id AND vigencia_hasta IS NULL")
                        .param("id", id)
                        .param("hasta", responsable.vigenciaHasta())
                        .update();
        if (filas == 0) {
            throw new VinculoNoVigente(id);
        }
        return responsable;
    }

    // ---------- Mapeos ----------

    private static Domicilio mapearDomicilio(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Domicilio(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                TipoDomicilio.valueOf(fila.getString("tipo")),
                fila.getString("direccion"),
                fila.getString("referencia"),
                fila.getString("ubigeo"),
                fila.getDate("vigencia_desde").toLocalDate(),
                fechaOpcional(fila, "vigencia_hasta"),
                fila.getString("documento_origen"));
    }

    private static Contacto mapearContacto(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Contacto(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                TipoContacto.valueOf(fila.getString("tipo")),
                fila.getString("valor"),
                fila.getString("nombre"),
                fila.getString("documento"),
                fila.getString("observacion"),
                fila.getBoolean("vigente"));
    }

    private static ResponsableSolidario mapearResponsable(ResultSet fila, int numeroDeFila)
            throws SQLException {
        java.math.BigDecimal porcentaje = fila.getBigDecimal("porcentaje");
        return new ResponsableSolidario(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                fila.getLong("responsable_id"),
                Vinculo.valueOf(fila.getString("vinculo")),
                porcentaje == null ? null : new Porcentaje(porcentaje),
                fila.getDate("vigencia_desde").toLocalDate(),
                fechaOpcional(fila, "vigencia_hasta"),
                fila.getString("documento_origen"));
    }

    private static @Nullable LocalDate fechaOpcional(ResultSet fila, String columna)
            throws SQLException {
        java.sql.Date fecha = fila.getDate(columna);
        return fecha == null ? null : fecha.toLocalDate();
    }

    /** Se intento cerrar un domicilio que ya estaba cerrado, o que no existe aqui. */
    public static final class DomicilioNoVigente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DomicilioNoVigente(long id) {
            super(
                    "El domicilio "
                            + id
                            + " no esta vigente en esta municipalidad; cerrarlo otra vez"
                            + " reescribiria el historial");
        }
    }

    /** Se intento cerrar un vinculo que ya estaba cerrado, o que no existe aqui. */
    public static final class VinculoNoVigente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VinculoNoVigente(long id) {
            super("El vinculo " + id + " no esta vigente en esta municipalidad");
        }
    }

    /** No existe, o es de otra municipalidad. */
    public static final class ContactoNoEncontrado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContactoNoEncontrado(long id) {
            super("No hay ningun contacto con identificador " + id + " en esta municipalidad");
        }
    }
}
