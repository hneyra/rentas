package kamayuk.rentas.contribuyentes.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.dominio.CondicionEspecial;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import kamayuk.rentas.contribuyentes.dominio.TipoPersona;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.TipoDocumento;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El padron contra PostgreSQL. Sigue la plantilla de {@code ViaRepositoryJdbc} y anade lo unico que
 * el catalogo vial no necesitaba: <b>busqueda por aproximacion de nombre</b>.
 *
 * <p>La aproximacion se resuelve con {@code pg_trgm} sobre {@code nombre_normalizado(...)}
 * —minusculas, sin tildes, sin espacios repetidos— que {@code V11} declaro {@code IMMUTABLE}
 * justamente para poder indexarla. Traer el padron a memoria para compararlo en Java es lo que hace
 * que la caja tarde.
 *
 * <p>Igual que en el catalogo vial: ninguna consulta filtra por {@code municipalidad_id} —lo hace
 * la politica RLS— y no hay ningun {@code DELETE}.
 */
@Repository
public class ContribuyenteRepositoryJdbc extends RepositorioJdbc
        implements ContribuyenteRepository {

    private static final String COLUMNAS =
            "id, codigo_contribuyente, tipo_documento, numero_documento, tipo_persona,"
                    + " nombre_razon_social, condicion_especial, fecha_nacimiento, estado_civil,"
                    + " conyuge_id, activo";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre(
                    "codigo_contribuyente", "nombre_razon_social", "numero_documento", "id");

    /**
     * Cuanto parecido basta para considerar que dos nombres son el mismo.
     *
     * <p>No es una cifra normativa —la regla 5 habla de UIT, tramos y alicuotas—, es un umbral de
     * busqueda. Se calibro midiendo contra PostgreSQL: «pena garsia maria» frente a «PEÑA GARCIA,
     * MARIA DEL CARMEN» da 0,45, y frente a dos nombres sin relacion da 0,05 y 0,02. La prueba
     * {@code laAproximacionDistingueUnParecidoDeUnoQueNoLoEs} fija ese comportamiento, para que
     * bajar el umbral por comodidad rompa algo visible.
     *
     * <p>En {@code BigDecimal} y no en {@code double} porque la regla 1 no admite coma flotante en
     * ningun sitio. Aqui no habria centimos que perder, pero una regla con excepciones «para casos
     * donde no importa» deja de ser una regla: la siguiente excepcion si seria un importe.
     */
    private static final BigDecimal PARECIDO_MINIMO = new BigDecimal("0.30");

    public ContribuyenteRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Contribuyente> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM contribuyente WHERE id = :id")
                .param("id", id)
                .query(ContribuyenteRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<Contribuyente> findAllById(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            // Un IN vacio no es SQL valido, y mandarlo al motor para que se queje seria dejar que
            // el caso mas corriente —una pagina sin titulares— llegue como error de sintaxis.
            return List.of();
        }
        return jdbc().sql("SELECT " + COLUMNAS + " FROM contribuyente WHERE id IN (:ids)")
                .param("ids", ids)
                .query(ContribuyenteRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<Contribuyente> findByCodigo(CodigoContribuyente codigo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM contribuyente WHERE codigo_contribuyente = :codigo")
                .param("codigo", codigo.valor())
                .query(ContribuyenteRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Contribuyente> findByDocumento(DocumentoIdentidad documento) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM contribuyente"
                                + " WHERE tipo_documento = :tipo AND numero_documento = :numero")
                .param("tipo", documento.tipo().name())
                .param("numero", documento.numero())
                .query(ContribuyenteRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Contribuyente> buscar(CriterioDeBusqueda criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.codigo() != null) {
            condiciones.add("codigo_contribuyente = :codigo");
            parametros.put("codigo", criterio.codigo().toUpperCase(java.util.Locale.ROOT));
        }
        if (criterio.numeroDocumento() != null) {
            condiciones.add("numero_documento = :numeroDocumento");
            parametros.put("numeroDocumento", criterio.numeroDocumento());
            if (criterio.tipoDocumento() != null) {
                condiciones.add("tipo_documento = :tipoDocumento");
                parametros.put("tipoDocumento", criterio.tipoDocumento().name());
            }
        }
        if (criterio.soloActivos()) {
            condiciones.add("activo");
        }
        String nombre = criterio.nombreAproximado();
        if (nombre != null) {
            condiciones.add(
                    "similarity(nombre_normalizado(nombre_razon_social),"
                            + " nombre_normalizado(:nombre)) >= :parecidoMinimo");
            parametros.put("nombre", nombre);
            parametros.put("parecidoMinimo", PARECIDO_MINIMO);
        }

        String donde = condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);

        if (nombre == null) {
            return paginar(
                    "SELECT " + COLUMNAS + " FROM contribuyente" + donde,
                    "SELECT count(*) FROM contribuyente" + donde,
                    parametros,
                    paginacion,
                    ORDEN,
                    ContribuyenteRepositoryJdbc::mapear);
        }
        return paginarPorParecido(donde, parametros, paginacion);
    }

    /**
     * Cuando hay nombre, el orden lo decide el parecido y no la paginacion.
     *
     * <p>Ordenar alfabeticamente un resultado por aproximacion esconde la fila que se buscaba en la
     * pagina cuatro, que para quien atiende equivale a no haberla encontrado. Por eso esta consulta
     * no pasa por {@link OrdenSeguro}: no hay campo que elegir, y el {@code ORDER BY} no lleva nada
     * que venga de fuera.
     */
    private Pagina<Contribuyente> paginarPorParecido(
            String donde, Map<String, Object> parametros, Paginacion paginacion) {

        long total =
                jdbc().sql("SELECT count(*) FROM contribuyente" + donde)
                        .params(parametros)
                        .query(Long.class)
                        .optional()
                        .orElse(0L);
        if (total == 0) {
            return Pagina.vacia(paginacion);
        }

        List<Contribuyente> contenido =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM contribuyente"
                                        + donde
                                        + " ORDER BY similarity(nombre_normalizado("
                                        + "nombre_razon_social), nombre_normalizado(:nombre)) DESC,"
                                        // Desempate estable: sin el, dos nombres con el mismo
                                        // parecido pueden salir en distinto orden en cada pagina
                                        // y una fila aparecer dos veces o ninguna.
                                        + " codigo_contribuyente ASC"
                                        + " LIMIT :sgtmLimite OFFSET :sgtmDesplazamiento")
                        .params(parametros)
                        .param("sgtmLimite", paginacion.tamano())
                        .param("sgtmDesplazamiento", paginacion.desplazamiento())
                        .query(ContribuyenteRepositoryJdbc::mapear)
                        .list();

        return Pagina.de(contenido, paginacion, total);
    }

    @Override
    public Contribuyente save(Contribuyente contribuyente) {
        return contribuyente.esNuevo() ? insertar(contribuyente) : actualizar(contribuyente);
    }

    private Contribuyente insertar(Contribuyente contribuyente) {
        Long id =
                jdbc().sql(
                                "INSERT INTO contribuyente"
                                        + " (municipalidad_id, codigo_contribuyente, tipo_documento,"
                                        + "  numero_documento, tipo_persona, nombre_razon_social,"
                                        + "  condicion_especial, fecha_nacimiento, estado_civil,"
                                        + "  conyuge_id, activo, usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :codigo, :tipoDocumento, :numeroDocumento,"
                                        + "  :tipoPersona, :nombre, :condicion, :fechaNacimiento,"
                                        + "  :estadoCivil, :conyugeId, :activo, :usuario)"
                                        + " RETURNING id")
                        .params(camposDe(contribuyente))
                        .param("usuario", usuarioActual())
                        .query(Long.class)
                        .single();

        return new Contribuyente(
                id,
                contribuyente.codigo(),
                contribuyente.documento(),
                contribuyente.tipoPersona(),
                contribuyente.nombreRazonSocial(),
                contribuyente.condicionEspecial(),
                contribuyente.fechaNacimiento(),
                contribuyente.estadoCivil(),
                contribuyente.conyugeId(),
                contribuyente.activo());
    }

    private Contribuyente actualizar(Contribuyente contribuyente) {
        long id =
                Objects.requireNonNull(
                        contribuyente.id(), "Un contribuyente existente tiene identificador");
        Map<String, Object> campos = new HashMap<>(camposDe(contribuyente));
        campos.put("id", id);

        int filas =
                jdbc().sql(
                                """
                                UPDATE contribuyente
                                   SET codigo_contribuyente = :codigo,
                                       tipo_documento       = :tipoDocumento,
                                       numero_documento     = :numeroDocumento,
                                       tipo_persona         = :tipoPersona,
                                       nombre_razon_social  = :nombre,
                                       condicion_especial   = :condicion,
                                       fecha_nacimiento     = :fechaNacimiento,
                                       estado_civil         = :estadoCivil,
                                       conyuge_id           = :conyugeId,
                                       activo               = :activo
                                 WHERE id = :id
                                """)
                        .params(campos)
                        .update();
        if (filas == 0) {
            // No existe, o existe en otra municipalidad y la politica lo esconde. Desde
            // aqui son indistinguibles, y esta bien que lo sean.
            throw new ContribuyenteNoEncontrado(id);
        }
        return contribuyente;
    }

    private static Map<String, Object> camposDe(Contribuyente contribuyente) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("codigo", contribuyente.codigo().valor());
        campos.put("tipoDocumento", contribuyente.documento().tipo().name());
        campos.put("numeroDocumento", contribuyente.documento().numero());
        campos.put("tipoPersona", contribuyente.tipoPersona().name());
        campos.put("nombre", contribuyente.nombreRazonSocial());
        campos.put("condicion", nombreDe(contribuyente.condicionEspecial()));
        campos.put("fechaNacimiento", contribuyente.fechaNacimiento());
        campos.put("estadoCivil", contribuyente.estadoCivil());
        campos.put("conyugeId", contribuyente.conyugeId());
        campos.put("activo", contribuyente.activo());
        return campos;
    }

    private static @Nullable String nombreDe(@Nullable CondicionEspecial condicion) {
        return condicion == null ? null : condicion.name();
    }

    /**
     * Quien registra. La columna es {@code NOT NULL} y el manual la exige; sale del contexto de
     * origen, igual que en la auditoria.
     */
    private static String usuarioActual() {
        return OrigenContext.actual().usuario();
    }

    private static Contribuyente mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        String condicion = fila.getString("condicion_especial");
        java.sql.Date nacimiento = fila.getDate("fecha_nacimiento");
        // wasNull() se pregunta inmediatamente despues de leer la columna: consultarlo
        // mas tarde responde por la ultima leida, que seria otra.
        long conyuge = fila.getLong("conyuge_id");
        Long conyugeId = fila.wasNull() ? null : conyuge;

        return new Contribuyente(
                fila.getLong("id"),
                CodigoContribuyente.de(fila.getString("codigo_contribuyente")),
                new DocumentoIdentidad(
                        TipoDocumento.valueOf(fila.getString("tipo_documento")),
                        fila.getString("numero_documento")),
                TipoPersona.valueOf(fila.getString("tipo_persona")),
                fila.getString("nombre_razon_social"),
                condicion == null ? null : CondicionEspecial.valueOf(condicion),
                nacimiento == null ? null : nacimiento.toLocalDate(),
                fila.getString("estado_civil"),
                conyugeId,
                fila.getBoolean("activo"));
    }

    /** No existe, o existe en otra municipalidad. Desde la aplicacion es lo mismo. */
    public static final class ContribuyenteNoEncontrado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteNoEncontrado(long id) {
            super("No hay ningun contribuyente con identificador " + id + " en esta municipalidad");
        }
    }
}
