package kamayuk.rentas.valores.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.UnidadDePlazo;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ClaseDeHecho;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.CriterioDePrescripciones;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import kamayuk.rentas.valores.dominio.PrescripcionRepository;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las declaraciones de prescripcion contra PostgreSQL (V28).
 *
 * <p>Tres tablas, una sola operacion: la cabecera, el computo de cada ejercicio y los hechos
 * alegados. Sin {@code UPDATE} y sin {@code DELETE}: una resolucion no se edita.
 *
 * <p>El plazo se guarda partido en {@code plazo_anios} porque asi lo expresa el art. 43 del TUO del
 * Codigo Tributario -en anios- y asi tiene que poder consultarse. La unidad no viaja a la base: si
 * manana un plazo se parametrizara en dias, {@code plazo_anios} dejaria de servir y habria que
 * migrar la columna, que es preferible a guardar "20" sin decir de que.
 */
@Repository
public class PrescripcionRepositoryJdbc extends RepositorioJdbc implements PrescripcionRepository {

    private static final String COLUMNAS =
            "id, contribuyente_id, tributo, ejercicio_desde, ejercicio_hasta, fecha_presentacion,"
                    + " causal, plazo_anios, conjunto_id, resultado, resolucion, usuario_registro,"
                    + " observacion";

    /**
     * Por lo que se recorre una relacion de prescripciones: cuando se presento la solicitud.
     *
     * <p>{@code desempatandoPor("id")} porque {@code fecha_presentacion} es una <b>fecha</b>, no un
     * instante: varias solicitudes del mismo dia empatan, y sin orden total dos paginas
     * consecutivas pueden repetir una fila y omitir otra —la solicitud que se busca no aparece
     * nunca— (#543, #548).
     */
    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre(
                            "fecha_presentacion",
                            "tributo",
                            "ejercicio_desde",
                            "ejercicio_hasta",
                            "resultado")
                    .desempatandoPor("id");

    /**
     * Los ejercicios que de verdad prescribieron, en una sola consulta y no una por fila.
     *
     * <p>Correlacionada con {@code municipalidad_id} ademas de con {@code prescripcion_id}: la RLS
     * ya acota las dos tablas, y nombrarlo deja explicito que la fila hija es de la misma
     * municipalidad que su cabecera, como hace el {@code NOT EXISTS} de {@code
     * AsientoRepositoryJdbc}.
     */
    private static final String EJERCICIOS_PRESCRITOS =
            "(SELECT string_agg(pe.ejercicio::text, ',' ORDER BY pe.ejercicio)"
                    + "   FROM prescripcion_ejercicio pe"
                    + "  WHERE pe.municipalidad_id = p.municipalidad_id"
                    + "    AND pe.prescripcion_id = p.id"
                    + "    AND pe.prescrita) AS ejercicios_prescritos";

    public PrescripcionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Prescripcion insertar(Prescripcion prescripcion) {
        if (!prescripcion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una prescripcion ya declarada no se vuelve a insertar ni se corrige");
        }
        if (prescripcion.plazo().unidad() != UnidadDePlazo.ANIOS) {
            throw new IllegalArgumentException(
                    "El art. 43 expresa la prescripcion en anios; este plazo esta en "
                            + prescripcion.plazo().unidad());
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO prescripcion"
                                        + " (municipalidad_id, contribuyente_id, tributo,"
                                        + "  ejercicio_desde, ejercicio_hasta, fecha_presentacion,"
                                        + "  causal, plazo_anios, conjunto_id, resultado,"
                                        + "  resolucion, usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :contribuyenteId, :tributo, :desde, :hasta, :fecha,"
                                        + "  :causal, :plazo, :conjuntoId, :resultado,"
                                        + "  :resolucion, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("contribuyenteId", prescripcion.contribuyenteId())
                        .param("tributo", prescripcion.tributo())
                        .param("desde", prescripcion.ejercicioDesde().valor())
                        .param("hasta", prescripcion.ejercicioHasta().valor())
                        .param("fecha", prescripcion.fechaPresentacion())
                        .param("causal", prescripcion.causal().name())
                        .param("plazo", prescripcion.plazo().cantidad())
                        .param("conjuntoId", prescripcion.conjuntoId())
                        .param("resultado", prescripcion.resultado().name())
                        .param("resolucion", prescripcion.resolucion())
                        .param("usuario", usuarioActual())
                        .param("observacion", prescripcion.observacion().texto())
                        .query(Long.class)
                        .single();

        for (ComputoDeEjercicio computo : prescripcion.ejercicios()) {
            jdbc().sql(
                            "INSERT INTO prescripcion_ejercicio"
                                    + " (municipalidad_id, prescripcion_id, ejercicio,"
                                    + "  inicio_computo, inicio_vigente, fecha_prescripcion,"
                                    + "  prescrita)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :prescripcionId, :ejercicio, :inicio, :vigente,"
                                    + "  :prescripcion, :prescrita)")
                    .param("prescripcionId", id)
                    .param("ejercicio", computo.ejercicio().valor())
                    .param("inicio", computo.inicioComputo())
                    .param("vigente", computo.inicioVigente())
                    .param("prescripcion", computo.fechaPrescripcion())
                    .param("prescrita", computo.prescrita())
                    .update();
        }

        for (HechoDelComputo hecho : prescripcion.hechos()) {
            jdbc().sql(
                            "INSERT INTO prescripcion_hecho"
                                    + " (municipalidad_id, prescripcion_id, clase, causal,"
                                    + "  fecha_desde, fecha_hasta)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :prescripcionId, :clase, :causal, :desde, :hasta)")
                    .param("prescripcionId", id)
                    .param("clase", hecho.clase().name())
                    .param("causal", hecho.causal())
                    .param("desde", hecho.desde())
                    .param("hasta", hecho.hasta())
                    .update();
        }

        return porId(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La prescripcion " + id + " se desvanecio al releerla"));
    }

    @Override
    public Optional<Prescripcion> porId(long id) {
        Optional<Cabecera> cabecera =
                jdbc().sql("SELECT " + COLUMNAS + " FROM prescripcion WHERE id = :id")
                        .param("id", id)
                        .query(this::mapearCabecera)
                        .optional();
        if (cabecera.isEmpty()) {
            return Optional.empty();
        }
        Cabecera datos = cabecera.get();
        return Optional.of(
                new Prescripcion(
                        datos.id(),
                        datos.contribuyenteId(),
                        datos.tributo(),
                        datos.ejercicioDesde(),
                        datos.ejercicioHasta(),
                        datos.fechaPresentacion(),
                        datos.causal(),
                        datos.plazo(),
                        datos.conjuntoId(),
                        datos.resultado(),
                        datos.resolucion(),
                        ejerciciosDe(id),
                        hechosDe(id),
                        datos.usuarioRegistro(),
                        datos.observacion()));
    }

    /**
     * La relacion de declaraciones (#674).
     *
     * <p><b>Una consulta por pagina, no una por fila.</b> Los ejercicios que prescribieron se
     * agregan en la propia seleccion con {@link #EJERCICIOS_PRESCRITOS}; leerlos con {@link
     * #ejerciciosDe} por cada fila serian veinte consultas para una pagina de veinte, y ademas
     * traerian el computo entero —los dos inicios y la fecha de cada ejercicio—, que es la
     * resolucion y no la relacion.
     *
     * <p><b>Sin indice nuevo, y medido en vez de supuesto.</b> {@code prescripcion} crece una fila
     * por solicitud presentada, no una por predio ni por asiento: el padron de Catacaos tiene 10
     * 603 contribuyentes y las solicitudes de prescripcion de un ano se cuentan por decenas. La
     * clave primaria es {@code (municipalidad_id, id)} y la politica RLS acota por su primera
     * columna, asi que el recorrido ya esta acotado al inquilino. Un indice aqui seria una
     * migracion para una tabla que cabe en una pagina.
     */
    @Override
    public Pagina<PrescripcionEnLista> buscar(
            CriterioDePrescripciones criterio, Paginacion paginacion) {

        Map<String, Object> parametros = new LinkedHashMap<>();
        StringBuilder condiciones = new StringBuilder("1 = 1");

        if (criterio.contribuyenteId() != null) {
            condiciones.append(" AND p.contribuyente_id = :contribuyenteId");
            parametros.put("contribuyenteId", criterio.contribuyenteId());
        }
        if (criterio.tributo() != null && !criterio.tributo().isBlank()) {
            condiciones.append(" AND p.tributo = :tributo");
            parametros.put("tributo", criterio.tributo().strip());
        }
        if (criterio.ejercicio() != null) {
            // El rango SOLICITADO, no los que prescribieron: ver CriterioDePrescripciones.
            condiciones.append(
                    " AND p.ejercicio_desde <= :ejercicio AND p.ejercicio_hasta >= :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio());
        }
        if (criterio.resultado() != null) {
            condiciones.append(" AND p.resultado = :resultado");
            parametros.put("resultado", criterio.resultado().name());
        }

        String desde = " FROM prescripcion p WHERE " + condiciones;
        String seleccion =
                "SELECT p.id, p.contribuyente_id, p.tributo, p.ejercicio_desde,"
                        + " p.ejercicio_hasta, p.fecha_presentacion, p.causal, p.plazo_anios,"
                        + " p.resultado, p.resolucion, p.usuario_registro, p.observacion, "
                        + EJERCICIOS_PRESCRITOS
                        + desde;
        String conteo = "SELECT count(*)" + desde;

        return paginar(seleccion, conteo, parametros, paginacion, ORDEN, this::mapearFila);
    }

    private PrescripcionEnLista mapearFila(ResultSet fila, int numeroDeFila) throws SQLException {
        return new PrescripcionEnLista(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                fila.getString("tributo"),
                new Ejercicio(fila.getInt("ejercicio_desde")),
                new Ejercicio(fila.getInt("ejercicio_hasta")),
                fila.getDate("fecha_presentacion").toLocalDate(),
                CausalDePrescripcion.valueOf(fila.getString("causal")),
                new Plazo(fila.getInt("plazo_anios"), UnidadDePlazo.ANIOS),
                ResultadoDeLaSolicitud.valueOf(fila.getString("resultado")),
                fila.getString("resolucion"),
                prescritos(fila.getString("ejercicios_prescritos")),
                fila.getString("usuario_registro"),
                fila.getString("observacion"));
    }

    /** Nulo es «ninguno prescribio», que es una lista vacia y no una falta de dato. */
    private static List<Ejercicio> prescritos(@Nullable String agregados) {
        if (agregados == null || agregados.isBlank()) {
            return List.of();
        }
        List<Ejercicio> ejercicios = new ArrayList<>();
        for (String anio : agregados.split(",")) {
            ejercicios.add(new Ejercicio(Integer.parseInt(anio.strip())));
        }
        return ejercicios;
    }

    private List<ComputoDeEjercicio> ejerciciosDe(long prescripcionId) {
        return jdbc().sql(
                        "SELECT id, ejercicio, inicio_computo, inicio_vigente, fecha_prescripcion,"
                                + " prescrita"
                                + " FROM prescripcion_ejercicio"
                                + " WHERE prescripcion_id = :id"
                                + " ORDER BY ejercicio")
                .param("id", prescripcionId)
                .query(
                        (ResultSet fila, int numero) ->
                                new ComputoDeEjercicio(
                                        fila.getLong("id"),
                                        new Ejercicio(fila.getInt("ejercicio")),
                                        fila.getDate("inicio_computo").toLocalDate(),
                                        fila.getDate("inicio_vigente").toLocalDate(),
                                        fila.getDate("fecha_prescripcion").toLocalDate(),
                                        fila.getBoolean("prescrita")))
                .list();
    }

    private List<HechoDelComputo> hechosDe(long prescripcionId) {
        List<HechoDelComputo> hechos = new ArrayList<>();
        hechos.addAll(
                jdbc().sql(
                                "SELECT clase, causal, fecha_desde, fecha_hasta"
                                        + " FROM prescripcion_hecho"
                                        + " WHERE prescripcion_id = :id"
                                        + " ORDER BY fecha_desde, id")
                        .param("id", prescripcionId)
                        .query(
                                (ResultSet fila, int numero) -> {
                                    java.sql.Date hasta = fila.getDate("fecha_hasta");
                                    return new HechoDelComputo(
                                            ClaseDeHecho.valueOf(fila.getString("clase")),
                                            fila.getString("causal"),
                                            fila.getDate("fecha_desde").toLocalDate(),
                                            hasta == null ? null : hasta.toLocalDate());
                                })
                        .list());
        return hechos;
    }

    private Cabecera mapearCabecera(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Cabecera(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                fila.getString("tributo"),
                new Ejercicio(fila.getInt("ejercicio_desde")),
                new Ejercicio(fila.getInt("ejercicio_hasta")),
                fila.getDate("fecha_presentacion").toLocalDate(),
                CausalDePrescripcion.valueOf(fila.getString("causal")),
                new Plazo(fila.getInt("plazo_anios"), UnidadDePlazo.ANIOS),
                fila.getLong("conjunto_id"),
                ResultadoDeLaSolicitud.valueOf(fila.getString("resultado")),
                fila.getString("resolucion"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    /** La fila de {@code prescripcion} sin sus dos listas, que se leen aparte. */
    private record Cabecera(
            long id,
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            java.time.LocalDate fechaPresentacion,
            CausalDePrescripcion causal,
            Plazo plazo,
            long conjuntoId,
            ResultadoDeLaSolicitud resultado,
            @Nullable String resolucion,
            @Nullable String usuarioRegistro,
            Observacion observacion) {}

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
