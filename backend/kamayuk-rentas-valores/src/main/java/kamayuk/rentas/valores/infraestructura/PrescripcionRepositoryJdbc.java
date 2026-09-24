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
import kamayuk.rentas.valores.dominio.AlcanceDelHecho;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ClaseDeHecho;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.CriterioDePrescripciones;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import kamayuk.rentas.valores.dominio.PrescripcionRepository;
import kamayuk.rentas.valores.dominio.RelojDelEjercicio;
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
     * El reloj de la pagina entera, en UNA consulta (#230).
     *
     * <p>Sustituye al {@code string_agg} de los ejercicios prescritos que esta clase agregaba en la
     * propia seleccion: aquel publicaba <b>que</b> anios prescribieron y tiraba <b>cuando</b>, que
     * es la columna «Prescribe el» de {@code val-tip}. Y eran dos verdades sobre el mismo hecho
     * —una agregada en SQL, otra leida de las mismas filas—, asi que ahora {@code
     * PrescripcionEnLista.ejerciciosPrescritos()} se deriva de esto.
     *
     * <p><b>Una consulta por pagina, no una por fila</b>, que era el motivo por el que el computo
     * no viajaba: {@code = ANY(:ids)} con los identificadores de la pagina, igual que {@code
     * DirectorioDeContribuyentes.porIds} resuelve los nombres en esa misma transaccion. No filtra
     * por {@code municipalidad_id}: lo hace la politica RLS.
     */
    private List<RelojDeUnaFila> relojesDe(List<Long> ids) {
        return jdbc().sql(
                        "SELECT prescripcion_id, ejercicio, fecha_prescripcion, prescrita"
                                + " FROM prescripcion_ejercicio"
                                + " WHERE prescripcion_id = ANY(:ids)"
                                + " ORDER BY prescripcion_id, ejercicio")
                .param("ids", ids.toArray(Long[]::new))
                .query(
                        (ResultSet fila, int numero) ->
                                new RelojDeUnaFila(
                                        fila.getLong("prescripcion_id"),
                                        new RelojDelEjercicio(
                                                new Ejercicio(fila.getInt("ejercicio")),
                                                fila.getDate("fecha_prescripcion").toLocalDate(),
                                                fila.getBoolean("prescrita"))))
                .list();
    }

    /** Un reloj con la declaracion a la que pertenece, para repartirlos por fila. */
    private record RelojDeUnaFila(long prescripcionId, RelojDelEjercicio reloj) {}

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
                                    + "  fecha_desde, fecha_hasta, ejercicios)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :prescripcionId, :clase, :causal, :desde, :hasta,"
                                    + "  string_to_array(CAST(:ejercicios AS text), ',')::ejercicio[])")
                    .param("prescripcionId", id)
                    .param("clase", hecho.clase().name())
                    .param("causal", hecho.causal())
                    .param("desde", hecho.desde())
                    .param("hasta", hecho.hasta())
                    .param("ejercicios", ejerciciosComoTexto(hecho.alcance()))
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
     * <p><b>Una consulta por pagina, no una por fila.</b> El reloj de todos los ejercicios de la
     * pagina sale de {@link #relojesDe} con un solo {@code = ANY(:ids)}; leerlos con {@link
     * #ejerciciosDe} por cada fila serian veinte consultas para una pagina de veinte. Lo que sigue
     * sin traerse son los <b>hechos</b> alegados —la explicacion del computo—, que si serian una
     * consulta mas y que solo la resolucion dibuja.
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
                        + " p.resultado, p.resolucion, p.usuario_registro, p.observacion"
                        + desde;
        String conteo = "SELECT count(*)" + desde;

        Pagina<PrescripcionEnLista> pagina =
                paginar(seleccion, conteo, parametros, paginacion, ORDEN, this::mapearFila);
        if (pagina.estaVacia()) {
            return pagina;
        }

        List<Long> ids = pagina.contenido().stream().map(PrescripcionEnLista::id).toList();
        Map<Long, List<RelojDelEjercicio>> porFila = new LinkedHashMap<>();
        for (RelojDeUnaFila fila : relojesDe(ids)) {
            porFila.computeIfAbsent(fila.prescripcionId(), id -> new ArrayList<>())
                    .add(fila.reloj());
        }
        // `List.of()` no es «no se sabe»: una declaracion sin ninguna fila de computo no puede
        // existir —`Prescripcion` rechaza la lista vacia—, asi que esto solo ocurriria con la tabla
        // corrompida, y entonces la fila sale sin reloj en vez de tirar la pagina entera.
        return pagina.mapear(fila -> fila.con(porFila.getOrDefault(fila.id(), List.of())));
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
                // El reloj llega despues, para la pagina entera de una vez: ver `buscar`.
                List.of(),
                fila.getString("usuario_registro"),
                fila.getString("observacion"));
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
                                "SELECT clase, causal, fecha_desde, fecha_hasta,"
                                        // Un arreglo de un DOMINIO llega como PGobject; como
                                        // smallint[], como numeros.
                                        + " ejercicios::smallint[] AS ejercicios"
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
                                            hasta == null ? null : hasta.toLocalDate(),
                                            alcanceDe(fila.getArray("ejercicios")));
                                })
                        .list());
        return hechos;
    }

    /**
     * El alcance de un hecho como lo escribe {@code string_to_array} (#334): los anos separados por
     * comas, o {@code null} si no esta declarado —y entonces la columna queda nula—. Es el mismo
     * camino que {@code DeterminacionRepositoryJdbc} usa para {@code reglas_aplicadas}, porque un
     * parametro con nombre de {@code JdbcClient} no mapea una coleccion a un arreglo de PostgreSQL.
     * No deberia llegar nunca uno sin declarar: {@code DeclararPrescripcion} los resuelve todos
     * antes de guardar, y {@code prescripcion_hecho_ejercicios_ck} no mira si la columna es nula
     * porque las filas anteriores a V25 lo son.
     */
    private static @Nullable String ejerciciosComoTexto(AlcanceDelHecho alcance) {
        if (!alcance.declarado()) {
            return null;
        }
        StringBuilder texto = new StringBuilder();
        for (Ejercicio ejercicio : alcance.ejercicios()) {
            if (!texto.isEmpty()) {
                texto.append(',');
            }
            texto.append(ejercicio.valor());
        }
        return texto.toString();
    }

    /** Nulo es una fila anterior a V25: su alcance no consta, y no se inventa (ver V25). */
    private static AlcanceDelHecho alcanceDe(java.sql.@Nullable Array arreglo) throws SQLException {
        if (arreglo == null) {
            return AlcanceDelHecho.sinDeclarar();
        }
        List<Ejercicio> ejercicios = new ArrayList<>();
        for (Object anio : (Object[]) arreglo.getArray()) {
            ejercicios.add(new Ejercicio(((Number) anio).intValue()));
        }
        return AlcanceDelHecho.de(ejercicios);
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
