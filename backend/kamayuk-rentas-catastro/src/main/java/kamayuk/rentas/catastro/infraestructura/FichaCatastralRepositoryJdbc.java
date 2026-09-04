package kamayuk.rentas.catastro.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.CategoriasConstructivas;
import kamayuk.rentas.catastro.dominio.Colindante;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.EstadoDeConservacion;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.MaterialEstructural;
import kamayuk.rentas.catastro.dominio.Orientacion;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.ParticipacionComun;
import kamayuk.rentas.catastro.dominio.Riego;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las versiones de la ficha contra PostgreSQL.
 *
 * <p>Hay un {@code INSERT} y hay un {@code UPDATE} que toca <b>una sola columna</b>, {@code
 * vigencia_hasta}. No hay ninguno que cambie los datos de una version: modificar una ficha es crear
 * otra. Si algun dia aparece aqui un {@code UPDATE ... SET area_terreno}, el versionado dejo de
 * existir aunque las tablas sigan igual.
 */
@Repository
public class FichaCatastralRepositoryJdbc extends RepositorioJdbc
        implements FichaCatastralRepository {

    private static final String COLUMNAS_FICHA =
            "id, predio_id, tipo, version, area_terreno, uso, frontis, condicion_propiedad,"
                    + " tipo_edificacion, denominacion, informacion_complementaria,"
                    + " vigencia_desde, vigencia_hasta, origen, documento_origen, observacion";

    private static final String COLUMNAS_ACTIVIDAD =
            "id, ficha_id, conductor, nombre_comercial, ciiu, area_ocupada, licencia_numero,"
                    + " licencia_fecha, anuncio_numero, anuncio_fecha, vigencia_desde";

    private static final String COLUMNAS_BIEN =
            "id, ficha_id, descripcion, area, material_estructural, estado_conservacion,"
                    + " anio_construccion";

    private static final String COLUMNAS_PARTICIPACION = "id, ficha_id, predio_id, porcentaje";

    private static final String COLUMNAS_TIERRA =
            "id, ficha_id, clasificacion, calidad_agrologica, riego, cantidad_hectareas,"
                    + " cantidad_hectareas_comun";

    private static final String COLUMNAS_COLINDANTE = "id, ficha_id, orientacion, descripcion";

    /**
     * Lista blanca del orden de la grilla. Sin ella, {@code ordenarPor} seria una via de inyeccion
     * y ademas dejaria ordenar por columnas sin indice, que es como se degrada un padron grande.
     */
    private static final OrdenSeguro ORDEN_CONSULTA =
            OrdenSeguro.sobre("cod_ref_catastral", "direccion", "uso", "vigencia_desde", "id");

    /**
     * El uso de la ficha con las tildes plegadas, para poder compararlo con lo que manda la
     * pantalla.
     *
     * <p>{@code translate} y no la extension {@code unaccent}: esta ultima habria que instalarla en
     * cada base, y para doce vocales no compensa depender de una extension. Solo se pliegan las
     * vocales acentuadas y la dieresis; la {@code ñ} se queda como esta, porque plegarla haria que
     * «AÑO» y «ANO» fueran la misma palabra.
     */
    private static final String USO_SIN_TILDES = "translate(f.uso, 'ÁÉÍÓÚÜáéíóúü', 'AEIOUUaeiouu')";

    /** Las mismas doce vocales, del lado de Java. */
    private static final String CON_TILDES = "ÁÉÍÓÚÜáéíóúü";

    private static final String SIN_TILDES = "AEIOUUaeiouu";

    /**
     * La grilla y el titular en una sola pasada por la base.
     *
     * <p>El {@code LEFT JOIN LATERAL} sobre {@code titularidad} trae <b>un</b> titular por predio
     * —el de mayor porcentaje— sin multiplicar las filas: un predio con tres copropietarios tiene
     * que salir una vez en la grilla, no tres. Y es {@code LEFT} a proposito: un predio sin titular
     * vigente es exactamente el que hay que revisar, y esconderlo del listado esconde el problema.
     */
    private static final String DESDE_LA_GRILLA =
            " FROM ficha_catastral f"
                    + " JOIN predio p ON p.id = f.predio_id"
                    + " LEFT JOIN manzana m ON m.id = p.manzana_id"
                    + " LEFT JOIN LATERAL ("
                    + "   SELECT t.contribuyente_id FROM titularidad t"
                    + "    WHERE t.predio_id = p.id"
                    + "      AND t.vigencia_desde <= :fecha"
                    + "      AND (t.vigencia_hasta IS NULL OR t.vigencia_hasta >= :fecha)"
                    + "    ORDER BY t.porcentaje DESC, t.id"
                    + "    LIMIT 1) tit ON true";

    private static final String COLUMNAS_CONSTRUCCION =
            "id, ficha_id, piso, area_construida, anio_construccion, material_estructural,"
                    + " estado_conservacion, categoria_muros, categoria_techos, categoria_pisos,"
                    + " categoria_puertas, categoria_revestim, categoria_banios,"
                    + " categoria_instalac, porcentaje_construido";

    /**
     * El area construida de <b>las fichas de la pagina</b>, sumada por la base (RNF-083, #290).
     *
     * <h2>Por que es otra consulta y no una columna mas del listado</h2>
     *
     * <p>Porque asi se suma <b>despues</b> del {@code LIMIT}. Como subconsulta en la lista de
     * campos, o como {@code LATERAL} del {@code FROM} de la grilla, la agregacion queda por debajo
     * del orden y el limite en el plan: se sumaria para todas las fichas que cumplen el filtro —el
     * padron entero cuando no hay filtro— para despues tirar todas menos las veinte que se ven. Y
     * ademas cambiaria el plan de la grilla, que la prueba de volumen de {@code
     * ConsultaDeFichasTest} fija con {@code EXPLAIN}; aqui ese plan no se toca.
     *
     * <p>Tampoco se traen las construcciones para sumarlas arriba: una pagina de veinte fichas de
     * cuatro pisos son ochenta filas viajando para producir veinte numeros, y la suma acabaria
     * escrita en Java, donde cada pantalla puede hacerla distinta.
     *
     * <p>La ficha sin construcciones <b>no aparece</b> en el resultado, y por eso su area sale nula
     * y no cero: ver {@link FichaEncontrada}.
     */
    private static final String AREA_CONSTRUIDA_DE_LAS_FICHAS =
            "SELECT ficha_id, sum(area_construida) AS area_construida"
                    + "  FROM construccion"
                    + " WHERE ficha_id IN (:fichas)"
                    + " GROUP BY ficha_id";

    private static final String COLUMNAS_INSTALACION =
            "id, ficha_id, descripcion, unidad_medida, cantidad, anio_construccion,"
                    + " estado_conservacion";

    public FichaCatastralRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha) {
        Objects.requireNonNull(fecha, "La ficha se pide a una fecha, nunca «la ultima» (regla 9)");
        Optional<FichaCatastral> ficha =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS_FICHA
                                        + " FROM ficha_catastral"
                                        + " WHERE predio_id = :predio AND tipo = :tipo"
                                        + "   AND vigencia_desde <= :fecha"
                                        + "   AND (vigencia_hasta IS NULL"
                                        + "        OR vigencia_hasta >= :fecha)"
                                        + " ORDER BY version DESC"
                                        + " LIMIT 1")
                        .param("predio", predioId)
                        .param("tipo", tipo.name())
                        .param("fecha", fecha)
                        .query(FichaCatastralRepositoryJdbc::mapearFicha)
                        .optional();
        return ficha.map(this::conSusPartes);
    }

    @Override
    public List<FichaCatastral> historial(long predioId, TipoFicha tipo) {
        List<FichaCatastral> versiones =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS_FICHA
                                        + " FROM ficha_catastral"
                                        + " WHERE predio_id = :predio AND tipo = :tipo"
                                        + " ORDER BY version DESC")
                        .param("predio", predioId)
                        .param("tipo", tipo.name())
                        .query(FichaCatastralRepositoryJdbc::mapearFicha)
                        .list();
        List<FichaCatastral> completas = new ArrayList<>();
        for (FichaCatastral version : versiones) {
            completas.add(conSusPartes(version));
        }
        return List.copyOf(completas);
    }

    @Override
    public Optional<FichaCatastral> ultimaVersion(long predioId, TipoFicha tipo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_FICHA
                                + " FROM ficha_catastral"
                                + " WHERE predio_id = :predio AND tipo = :tipo"
                                + " ORDER BY version DESC LIMIT 1")
                .param("predio", predioId)
                .param("tipo", tipo.name())
                .query(FichaCatastralRepositoryJdbc::mapearFicha)
                .optional()
                .map(this::conSusPartes);
    }

    @Override
    public Optional<FichaCatastral> porId(long fichaId) {
        return jdbc().sql("SELECT " + COLUMNAS_FICHA + " FROM ficha_catastral" + " WHERE id = :id")
                .param("id", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearFicha)
                .optional()
                .map(this::conSusPartes);
    }

    @Override
    public FichaCatastral insertar(FichaCatastral ficha) {
        if (!ficha.esNueva()) {
            throw new IllegalArgumentException(
                    "Una version ya registrada no se vuelve a insertar; modificar la ficha es"
                            + " crear la version siguiente");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO ficha_catastral"
                                        + " (municipalidad_id, predio_id, tipo, version, area_terreno,"
                                        + "  uso, frontis, condicion_propiedad, tipo_edificacion,"
                                        + "  denominacion, informacion_complementaria,"
                                        + "  vigencia_desde, vigencia_hasta, origen, documento_origen,"
                                        + "  observacion, usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :predio, :tipo, :version, :area, :uso, :frontis,"
                                        + "  :condicion, :edificacion, :denominacion,"
                                        + "  :complementaria, :desde, :hasta, :origen,"
                                        + "  :documento, :observacion, :usuario)"
                                        + " RETURNING id")
                        .param("predio", ficha.predioId())
                        .param("tipo", ficha.tipo().name())
                        .param("version", ficha.version())
                        .param("area", ficha.areaTerreno().valor())
                        .param("uso", ficha.uso())
                        .param(
                                "frontis",
                                ficha.frontis() == null ? null : ficha.frontis().magnitud())
                        .param("condicion", ficha.condicionPropiedad())
                        .param("edificacion", ficha.tipoEdificacion())
                        .param("denominacion", ficha.denominacion())
                        .param("complementaria", complementariaDe(ficha))
                        .param("desde", ficha.vigenciaDesde())
                        .param("hasta", ficha.vigenciaHasta())
                        .param("origen", ficha.origen().name())
                        .param("documento", ficha.documentoOrigen())
                        .param("observacion", ficha.observacion().texto())
                        .param("usuario", usuarioActual())
                        .query(Long.class)
                        .single();

        for (Construccion construccion : ficha.construcciones()) {
            insertarConstruccion(construccion.enLaFicha(id));
        }
        for (OtraInstalacion instalacion : ficha.instalaciones()) {
            insertarInstalacion(instalacion.enLaFicha(id));
        }
        insertarDetalle(id, ficha.detalle());

        return conSusPartes(conIdentificador(ficha, id));
    }

    @Override
    public FichaCatastral cerrar(FichaCatastral ficha) {
        long id = Objects.requireNonNull(ficha.id(), "Una version registrada tiene identificador");
        // Una sola columna, y solo si seguia abierta: dos cierres simultaneos no se pisan.
        int filas =
                jdbc().sql(
                                "UPDATE ficha_catastral SET vigencia_hasta = :hasta"
                                        + " WHERE id = :id AND vigencia_hasta IS NULL")
                        .param("id", id)
                        .param("hasta", ficha.vigenciaHasta())
                        .update();
        if (filas == 0) {
            throw new VersionNoVigente(id);
        }
        return ficha;
    }

    @Override
    public List<Construccion> construccionesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_CONSTRUCCION
                                + " FROM construccion WHERE ficha_id = :ficha ORDER BY piso, id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearConstruccion)
                .list();
    }

    @Override
    public List<OtraInstalacion> instalacionesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_INSTALACION
                                + " FROM otra_instalacion WHERE ficha_id = :ficha"
                                + " ORDER BY descripcion, id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearInstalacion)
                .list();
    }

    @Override
    public Pagina<FichaEncontrada> consultar(
            FiltroDeFichas filtro, List<Long> titulares, LocalDate fecha, Paginacion paginacion) {

        Objects.requireNonNull(fecha, "La grilla se pide a una fecha, nunca «la ultima» (regla 9)");

        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("fecha", fecha);

        // Solo la version vigente a la fecha: la grilla muestra un predio una vez, no una vez por
        // version. El historico es otra consulta y otra pantalla.
        condiciones.add("f.vigencia_desde <= :fecha");
        condiciones.add("(f.vigencia_hasta IS NULL OR f.vigencia_hasta >= :fecha)");

        if (filtro.codRefCatastral() != null) {
            // Por prefijo y no por igualdad: el codigo se compone de sector, manzana, lote y
            // unidad, asi que «2501010010» —todo ese sector— es una pregunta legitima.
            //
            // Y por RANGO, no por LIKE. Ver #prefijo: bajo RLS, un LIKE no llega nunca al indice.
            String desde = filtro.codRefCatastral();
            String hasta = siguienteAlPrefijo(desde);
            if (hasta == null) {
                condiciones.add("p.codigo_ref_catastral LIKE :codigo || '%'");
                parametros.put("codigo", desde);
            } else {
                condiciones.add(
                        "p.codigo_ref_catastral ~>=~ :codigoDesde"
                                + " AND p.codigo_ref_catastral ~<~ :codigoHasta");
                parametros.put("codigoDesde", desde);
                parametros.put("codigoHasta", hasta);
            }
        }
        if (filtro.manzana() != null) {
            condiciones.add("m.codigo = :manzana");
            parametros.put("manzana", filtro.manzana());
        }
        if (filtro.lote() != null) {
            condiciones.add("p.lote = :lote");
            parametros.put("lote", filtro.lote());
        }
        if (filtro.tipo() != null) {
            condiciones.add("f.tipo = :tipo");
            parametros.put("tipo", filtro.tipo().name());
        }
        if (filtro.uso() != null) {
            // Por igualdad, plegando mayusculas y tildes en los dos lados.
            //
            // `uso` es texto libre de 60 caracteres: no hay catalogo cerrado en el dominio, y lo
            // escribe quien registra la ficha. El desplegable de la pantalla, en cambio, manda
            // «CASA HABITACIÓN» con tilde. Comparar tal cual devolveria cero filas sobre un padron
            // que si las tiene, que es la peor respuesta posible: parece un padron vacio.
            //
            // Sin indice a proposito: el uso tiene un punado de valores distintos, asi que un
            // btree sobre el no lo elegiria el planificador ni aunque existiera. La condicion
            // selectiva de esta consulta sigue siendo la vigencia (ficha_vigencia_ix, V14).
            condiciones.add("upper(" + USO_SIN_TILDES + ") = :uso");
            parametros.put("uso", sinTildes(filtro.uso()).toUpperCase(Locale.ROOT));
        }
        if (filtro.porContribuyente().isPresent()) {
            if (titulares.isEmpty()) {
                // El usuario escribio un nombre y el padron no encontro a nadie. Devolver la
                // pagina vacia es la respuesta; ignorar el filtro devolveria el padron entero, que
                // es la respuesta que hace que alguien crea que busco mal.
                return Pagina.vacia(paginacion);
            }
            condiciones.add("tit.contribuyente_id IN (:titulares)");
            parametros.put("titulares", titulares);
        }

        // La acotacion por predio entra en el MISMO where que los filtros del usuario, y ahi
        // esta todo el asunto de #631: la pagina y el `count(*)` de abajo se construyen con
        // esta misma cadena, asi que no pueden decir cosas distintas. Filtrar despues, sobre
        // la pagina ya devuelta, dejaba `totalElementos` contando el padron entero — 722
        // paginas de cero filas.
        switch (filtro.acotacion().modo()) {
            case SOLO_ESTOS -> {
                if (filtro.acotacion().predios().isEmpty()) {
                    // Ningun predio puede estar en un conjunto vacio. Se devuelve aqui para no
                    // mandar un `= ANY('{}')` que el motor tiene que evaluar fila a fila.
                    return Pagina.vacia(paginacion);
                }
                condiciones.add("p.id = ANY(:prediosAcotados)");
                parametros.put(
                        "prediosAcotados", filtro.acotacion().predios().toArray(Long[]::new));
            }
            case TODOS_MENOS_ESTOS -> {
                condiciones.add("NOT (p.id = ANY(:prediosAcotados))");
                parametros.put(
                        "prediosAcotados", filtro.acotacion().predios().toArray(Long[]::new));
            }
            default -> {
                // TODOS: no acota. Va como `default` y no como caso nombrado porque Checkstyle
                // exige la rama por omision (MissingSwitchDefault) y el enumerado tiene tres.
            }
        }

        String donde = " WHERE " + String.join(" AND ", condiciones);

        // El alias no es cosmetico: OrdenSeguro deriva el campo que acepta el cliente del
        // nombre de la columna, y con codigo_ref_catastral aceptaria «codigoRefCatastral»
        // mientras el recurso publica «codRefCatastral». Dos nombres para el mismo campo
        // es una pantalla que ordena y recibe un 422.
        Pagina<FichaEncontrada> pagina =
                paginar(
                        "SELECT f.id, f.predio_id, p.codigo_ref_catastral AS cod_ref_catastral,"
                                + " p.direccion, m.codigo AS manzana,"
                                + " p.lote, f.tipo, f.version, f.area_terreno, f.uso,"
                                + " f.vigencia_desde, tit.contribuyente_id"
                                + DESDE_LA_GRILLA
                                + donde,
                        "SELECT count(*)" + DESDE_LA_GRILLA + donde,
                        parametros,
                        paginacion,
                        ORDEN_CONSULTA,
                        FichaCatastralRepositoryJdbc::mapearEncontrada);

        // La suma va aparte y despues del LIMIT. El porque, en AREA_CONSTRUIDA_DE_LAS_FICHAS.
        return conAreaConstruida(pagina);
    }

    /**
     * Las mismas doce vocales que {@link #USO_SIN_TILDES}, plegadas en Java.
     *
     * <p>Escrito a mano y no con {@code Normalizer}: la descomposicion NFD pliega mucho mas —la
     * {@code ñ} incluida—, y entonces los dos lados de la comparacion dejarian de hacer lo mismo.
     * Que sean dos listas identicas es el punto: si divergen, el filtro deja de encontrar.
     */
    private static String sinTildes(String texto) {
        StringBuilder resultado = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char caracter = texto.charAt(i);
            int posicion = CON_TILDES.indexOf(caracter);
            resultado.append(posicion < 0 ? caracter : SIN_TILDES.charAt(posicion));
        }
        return resultado.toString();
    }

    /** Pone en cada fila de la pagina el area construida de su version, sumada por la base. */
    private Pagina<FichaEncontrada> conAreaConstruida(Pagina<FichaEncontrada> pagina) {
        if (pagina.estaVacia()) {
            return pagina;
        }
        List<Long> fichas =
                pagina.contenido().stream().map(FichaEncontrada::fichaId).distinct().toList();

        Map<Long, AreaM2> areas =
                jdbc()
                        .sql(AREA_CONSTRUIDA_DE_LAS_FICHAS)
                        .param("fichas", fichas)
                        .query(
                                (fila, numeroDeFila) ->
                                        Map.entry(
                                                fila.getLong("ficha_id"),
                                                new AreaM2(fila.getBigDecimal("area_construida"))))
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));

        // La ficha que no esta en el mapa no declara construcciones: sale nula, que no es cero.
        return pagina.mapear(fila -> fila.conAreaConstruida(areas.get(fila.fichaId())));
    }

    /**
     * El primer texto que ya <b>no</b> empieza por ese prefijo, para buscar por rango en vez de con
     * {@code LIKE}.
     *
     * <h2>Por que no se usa LIKE</h2>
     *
     * <p>Bajo Row Level Security, <b>un {@code LIKE} no llega nunca al indice</b>. Se midio contra
     * PostgreSQL 16, misma tabla, mismo indice, mismos datos y el mismo rol de aplicacion:
     *
     * <pre>
     *   LIKE 'prefijo%'          → Seq Scan          (coste 925)
     *   ~&gt;=~ 'prefijo' AND ~&lt;~ … → Bitmap Index Scan (coste 308)
     * </pre>
     *
     * <p>El motivo es que {@code textlike} no es <i>leakproof</i> ({@code pg_proc.proleakproof =
     * false}), y PostgreSQL no evalua una condicion que no lo sea antes de la politica de
     * seguridad: podria filtrar por un mensaje de error filas de otra municipalidad. Asi que el
     * {@code LIKE} se queda como {@code Filter} despues del recorrido, y el indice sobra. Los
     * operadores de {@code text_pattern_ops} —{@code ~&gt;=~}, {@code ~&lt;~}— si son leakproof, y
     * expresan exactamente el mismo prefijo como un rango.
     *
     * <p>No es una peculiaridad de esta consulta: le pasa a <b>toda</b> busqueda por prefijo del
     * sistema, y por eso esta anotado en DAT-01 §0 junto a los otros dos hallazgos de RLS.
     *
     * @return el limite superior exclusivo, o {@code null} si el prefijo no es ASCII imprimible y
     *     hay que conformarse con {@code LIKE}: incrementar el ultimo caracter en UTF-16 no
     *     equivale a incrementarlo en bytes, y una comparacion por bytes con un limite calculado en
     *     caracteres dejaria filas fuera
     */
    static @Nullable String siguienteAlPrefijo(String prefijo) {
        if (prefijo.isEmpty()) {
            return null;
        }
        for (int i = 0; i < prefijo.length(); i++) {
            char caracter = prefijo.charAt(i);
            if (caracter < ' ' || caracter > '~') {
                return null;
            }
        }
        char ultimo = prefijo.charAt(prefijo.length() - 1);
        if (ultimo == '~') {
            return null;
        }
        return prefijo.substring(0, prefijo.length() - 1) + (char) (ultimo + 1);
    }

    @Override
    public List<VersionDeLaFicha> versionesDe(long predioId, TipoFicha tipo) {
        return jdbc().sql(
                        "SELECT id, version, area_terreno, uso, vigencia_desde, vigencia_hasta,"
                                + " origen, documento_origen, observacion, usuario_registro,"
                                + " fecha_registro"
                                + " FROM ficha_catastral"
                                + " WHERE predio_id = :predio AND tipo = :tipo"
                                + " ORDER BY version DESC")
                .param("predio", predioId)
                .param("tipo", tipo.name())
                .query(FichaCatastralRepositoryJdbc::mapearVersion)
                .list();
    }

    private static FichaEncontrada mapearEncontrada(ResultSet fila, int numeroDeFila)
            throws SQLException {
        long titular = fila.getLong("contribuyente_id");
        boolean sinTitular = fila.wasNull();
        return new FichaEncontrada(
                fila.getLong("id"),
                fila.getLong("predio_id"),
                CodigoReferenciaCatastral.de(fila.getString("cod_ref_catastral")),
                fila.getString("direccion"),
                fila.getString("manzana"),
                fila.getString("lote"),
                TipoFicha.valueOf(fila.getString("tipo")),
                fila.getInt("version"),
                new AreaM2(fila.getBigDecimal("area_terreno")),
                // La suma llega despues, en conAreaConstruida: aqui no hay con que calcularla.
                null,
                fila.getString("uso"),
                fila.getDate("vigencia_desde").toLocalDate(),
                sinTitular ? null : titular,
                null);
    }

    private static VersionDeLaFicha mapearVersion(ResultSet fila, int numeroDeFila)
            throws SQLException {
        java.sql.Date hasta = fila.getDate("vigencia_hasta");
        return new VersionDeLaFicha(
                fila.getLong("id"),
                fila.getInt("version"),
                new AreaM2(fila.getBigDecimal("area_terreno")),
                fila.getString("uso"),
                fila.getDate("vigencia_desde").toLocalDate(),
                hasta == null ? null : hasta.toLocalDate(),
                OrigenDeLaFicha.valueOf(fila.getString("origen")),
                fila.getString("documento_origen"),
                Observacion.de(fila.getString("observacion")),
                fila.getString("usuario_registro"),
                fila.getObject("fecha_registro", java.time.OffsetDateTime.class));
    }

    @Override
    public Optional<DetalleDeLaFicha> detalleDe(long fichaId, TipoFicha tipo) {
        return switch (tipo) {
            // Su detalle son las construcciones, que ya viajan en la ficha.
            case UNICA -> Optional.empty();
            case ECONOMICA ->
                    Optional.of(
                            new DetalleEconomico(
                                    actividadesDe(fichaId), complementariaDe(fichaId)));
            case BIENES_COMUNES ->
                    Optional.of(
                            new DetalleDeBienesComunes(
                                    bienesDe(fichaId), participacionesDe(fichaId)));
            case RURAL -> Optional.of(new DetalleRural(tierrasDe(fichaId), colindantesDe(fichaId)));
        };
    }

    private List<ActividadEconomica> actividadesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_ACTIVIDAD
                                + " FROM actividad_economica WHERE ficha_id = :ficha"
                                + " ORDER BY id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearActividad)
                .list();
    }

    /**
     * Una lectura mas, y a proposito: {@code detalleDe} es parte de la interfaz y tiene que
     * funcionar con solo el identificador. Pasarle la columna desde {@code mapearFicha} lo ataria a
     * quien lo llama, y solo la ficha economica hace este viaje.
     */
    private @Nullable String complementariaDe(long fichaId) {
        return jdbc().sql(
                        "SELECT informacion_complementaria FROM ficha_catastral WHERE id = :ficha")
                .param("ficha", fichaId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private List<BienComun> bienesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_BIEN
                                + " FROM bien_comun WHERE ficha_id = :ficha"
                                + " ORDER BY descripcion, id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearBien)
                .list();
    }

    private List<ParticipacionComun> participacionesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_PARTICIPACION
                                + " FROM participacion_comun WHERE ficha_id = :ficha"
                                + " ORDER BY predio_id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearParticipacion)
                .list();
    }

    private List<TierraRural> tierrasDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_TIERRA
                                + " FROM tierra_rural WHERE ficha_id = :ficha"
                                + " ORDER BY clasificacion, id")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearTierra)
                .list();
    }

    private List<Colindante> colindantesDe(long fichaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_COLINDANTE
                                + " FROM colindante_rural WHERE ficha_id = :ficha"
                                + " ORDER BY orientacion")
                .param("ficha", fichaId)
                .query(FichaCatastralRepositoryJdbc::mapearColindante)
                .list();
    }

    /**
     * Escribe lo propio del tipo. Es el reverso exacto de {@link #detalleDe}: si aparece aqui una
     * rama que alli no esta, o al reves, versionar dejaria de copiar algo.
     */
    private void insertarDetalle(long fichaId, @Nullable DetalleDeLaFicha detalle) {
        switch (detalle) {
            case null -> {
                // La ficha UNICA no tiene detalle propio.
            }
            case DetalleEconomico economico -> {
                for (ActividadEconomica actividad : economico.actividades()) {
                    insertarActividad(actividad.enLaFicha(fichaId));
                }
            }
            case DetalleDeBienesComunes comunes -> {
                for (BienComun bien : comunes.bienes()) {
                    insertarBien(bien.enLaFicha(fichaId));
                }
                for (ParticipacionComun participacion : comunes.participaciones()) {
                    insertarParticipacion(participacion.enLaFicha(fichaId));
                }
            }
            case DetalleRural rural -> {
                for (TierraRural tierra : rural.tierras()) {
                    insertarTierra(tierra.enLaFicha(fichaId));
                }
                for (Colindante colindante : rural.colindantes()) {
                    insertarColindante(colindante.enLaFicha(fichaId));
                }
            }
        }
    }

    private void insertarActividad(ActividadEconomica actividad) {
        jdbc().sql(
                        "INSERT INTO actividad_economica"
                                + " (municipalidad_id, ficha_id, conductor, nombre_comercial, ciiu,"
                                + "  area_ocupada, licencia_numero, licencia_fecha, anuncio_numero,"
                                + "  anuncio_fecha, vigencia_desde)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :conductor, :comercial, :ciiu, :area, :licencia,"
                                + "  :licenciaFecha, :anuncio, :anuncioFecha, :desde)")
                .param("ficha", actividad.fichaId())
                .param("conductor", actividad.conductor())
                .param("comercial", actividad.nombreComercial())
                .param("ciiu", actividad.ciiu())
                .param(
                        "area",
                        actividad.areaOcupada() == null ? null : actividad.areaOcupada().valor())
                .param("licencia", actividad.licenciaNumero())
                .param("licenciaFecha", actividad.licenciaFecha())
                .param("anuncio", actividad.anuncioNumero())
                .param("anuncioFecha", actividad.anuncioFecha())
                .param("desde", actividad.vigenciaDesde())
                .update();
    }

    private void insertarBien(BienComun bien) {
        jdbc().sql(
                        "INSERT INTO bien_comun"
                                + " (municipalidad_id, ficha_id, descripcion, area,"
                                + "  material_estructural, estado_conservacion, anio_construccion)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :descripcion, :area, :material, :estado, :anio)")
                .param("ficha", bien.fichaId())
                .param("descripcion", bien.descripcion())
                .param("area", bien.area().valor())
                .param("material", nombreDe(bien.material()))
                .param("estado", nombreDe(bien.estadoConservacion()))
                .param(
                        "anio",
                        bien.anioConstruccion() == null ? null : bien.anioConstruccion().valor())
                .update();
    }

    private void insertarParticipacion(ParticipacionComun participacion) {
        jdbc().sql(
                        "INSERT INTO participacion_comun"
                                + " (municipalidad_id, ficha_id, predio_id, porcentaje)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :predio, :porcentaje)")
                .param("ficha", participacion.fichaId())
                .param("predio", participacion.predioId())
                .param("porcentaje", participacion.porcentaje().valor())
                .update();
    }

    private void insertarTierra(TierraRural tierra) {
        jdbc().sql(
                        "INSERT INTO tierra_rural"
                                + " (municipalidad_id, ficha_id, clasificacion, calidad_agrologica,"
                                + "  riego, cantidad_hectareas, cantidad_hectareas_comun)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :clasificacion, :calidad, :riego, :hectareas,"
                                + "  :comunes)")
                .param("ficha", tierra.fichaId())
                .param("clasificacion", tierra.clasificacion())
                .param("calidad", tierra.calidadAgrologica())
                .param("riego", tierra.riego().name())
                .param("hectareas", tierra.hectareas().magnitud())
                .param(
                        "comunes",
                        tierra.hectareasComunes() == null
                                ? null
                                : tierra.hectareasComunes().magnitud())
                .update();
    }

    private void insertarColindante(Colindante colindante) {
        jdbc().sql(
                        "INSERT INTO colindante_rural"
                                + " (municipalidad_id, ficha_id, orientacion, descripcion)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :orientacion, :descripcion)")
                .param("ficha", colindante.fichaId())
                .param("orientacion", colindante.orientacion().name())
                .param("descripcion", colindante.descripcion())
                .update();
    }

    private static ActividadEconomica mapearActividad(ResultSet fila, int numeroDeFila)
            throws SQLException {
        BigDecimal area = fila.getBigDecimal("area_ocupada");
        return new ActividadEconomica(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getString("conductor"),
                fila.getString("nombre_comercial"),
                fila.getString("ciiu"),
                area == null ? null : new AreaM2(area),
                fila.getString("licencia_numero"),
                fecha(fila, "licencia_fecha"),
                fila.getString("anuncio_numero"),
                fecha(fila, "anuncio_fecha"),
                fecha(fila, "vigencia_desde"));
    }

    private static BienComun mapearBien(ResultSet fila, int numeroDeFila) throws SQLException {
        int anio = fila.getInt("anio_construccion");
        Ejercicio ejercicio = fila.wasNull() ? null : new Ejercicio(anio);
        return new BienComun(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getString("descripcion"),
                new AreaM2(fila.getBigDecimal("area")),
                valorDe(MaterialEstructural.class, fila.getString("material_estructural")),
                valorDe(EstadoDeConservacion.class, fila.getString("estado_conservacion")),
                ejercicio);
    }

    private static ParticipacionComun mapearParticipacion(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ParticipacionComun(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getLong("predio_id"),
                new Porcentaje(fila.getBigDecimal("porcentaje")));
    }

    private static TierraRural mapearTierra(ResultSet fila, int numeroDeFila) throws SQLException {
        BigDecimal comunes = fila.getBigDecimal("cantidad_hectareas_comun");
        return new TierraRural(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getString("clasificacion"),
                fila.getString("calidad_agrologica"),
                Riego.valueOf(fila.getString("riego")),
                new Medida(fila.getBigDecimal("cantidad_hectareas"), TierraRural.HECTAREA),
                comunes == null ? null : new Medida(comunes, TierraRural.HECTAREA));
    }

    private static Colindante mapearColindante(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new Colindante(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                Orientacion.valueOf(fila.getString("orientacion")),
                fila.getString("descripcion"));
    }

    private static @Nullable LocalDate fecha(ResultSet fila, String columna) throws SQLException {
        java.sql.Date valor = fila.getDate(columna);
        return valor == null ? null : valor.toLocalDate();
    }

    /** Lo que va a la columna de la ficha; solo la economica lo tiene. */
    private static @Nullable String complementariaDe(FichaCatastral ficha) {
        return ficha.detalle() instanceof DetalleEconomico economico
                ? economico.informacionComplementaria()
                : null;
    }

    // ------------------------------------------------------------------

    private FichaCatastral conSusPartes(FichaCatastral ficha) {
        long id = Objects.requireNonNull(ficha.id(), "Una ficha leida tiene identificador");
        return ficha.con(construccionesDe(id))
                .conInstalaciones(instalacionesDe(id))
                .conDetalle(detalleDe(id, ficha.tipo()).orElse(null));
    }

    private void insertarConstruccion(Construccion construccion) {
        CategoriasConstructivas categorias = construccion.categorias();
        jdbc().sql(
                        "INSERT INTO construccion"
                                + " (municipalidad_id, ficha_id, piso, area_construida,"
                                + "  anio_construccion, material_estructural, estado_conservacion,"
                                + "  categoria_muros, categoria_techos, categoria_pisos,"
                                + "  categoria_puertas, categoria_revestim, categoria_banios,"
                                + "  categoria_instalac, porcentaje_construido)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :piso, :area, :anio, :material, :estado, :muros,"
                                + "  :techos, :pisos, :puertas, :revestim, :banios, :instalac,"
                                + "  :porcentaje)")
                .param("ficha", construccion.fichaId())
                .param("piso", construccion.piso())
                .param("area", construccion.areaConstruida().valor())
                .param(
                        "anio",
                        construccion.anioConstruccion() == null
                                ? null
                                : construccion.anioConstruccion().valor())
                .param("material", nombreDe(construccion.material()))
                .param("estado", nombreDe(construccion.estadoConservacion()))
                .param("muros", texto(categorias.muros()))
                .param("techos", texto(categorias.techos()))
                .param("pisos", texto(categorias.pisos()))
                .param("puertas", texto(categorias.puertas()))
                .param("revestim", texto(categorias.revestimientos()))
                .param("banios", texto(categorias.banios()))
                .param("instalac", texto(categorias.instalaciones()))
                .param(
                        "porcentaje",
                        construccion.porcentajeConstruido() == null
                                ? null
                                : construccion.porcentajeConstruido().valor())
                .update();
    }

    private void insertarInstalacion(OtraInstalacion instalacion) {
        jdbc().sql(
                        "INSERT INTO otra_instalacion"
                                + " (municipalidad_id, ficha_id, descripcion, unidad_medida,"
                                + "  cantidad, anio_construccion, estado_conservacion)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ficha, :descripcion, :unidad, :cantidad, :anio, :estado)")
                .param("ficha", instalacion.fichaId())
                .param("descripcion", instalacion.descripcion())
                .param("unidad", instalacion.cantidad().unidad())
                .param("cantidad", instalacion.cantidad().magnitud())
                .param(
                        "anio",
                        instalacion.anioConstruccion() == null
                                ? null
                                : instalacion.anioConstruccion().valor())
                .param("estado", nombreDe(instalacion.estadoConservacion()))
                .update();
    }

    private static FichaCatastral conIdentificador(FichaCatastral ficha, Long id) {
        return new FichaCatastral(
                id,
                ficha.predioId(),
                ficha.tipo(),
                ficha.version(),
                ficha.areaTerreno(),
                ficha.uso(),
                ficha.frontis(),
                ficha.condicionPropiedad(),
                ficha.tipoEdificacion(),
                ficha.denominacion(),
                ficha.vigenciaDesde(),
                ficha.vigenciaHasta(),
                ficha.origen(),
                ficha.documentoOrigen(),
                ficha.observacion(),
                ficha.construcciones(),
                ficha.instalaciones(),
                ficha.detalle());
    }

    private static FichaCatastral mapearFicha(ResultSet fila, int numeroDeFila)
            throws SQLException {
        java.sql.Date hasta = fila.getDate("vigencia_hasta");
        return new FichaCatastral(
                fila.getLong("id"),
                fila.getLong("predio_id"),
                TipoFicha.valueOf(fila.getString("tipo")),
                fila.getInt("version"),
                new AreaM2(fila.getBigDecimal("area_terreno")),
                fila.getString("uso"),
                frontisDe(fila),
                fila.getString("condicion_propiedad"),
                fila.getString("tipo_edificacion"),
                fila.getString("denominacion"),
                fila.getDate("vigencia_desde").toLocalDate(),
                hasta == null ? null : hasta.toLocalDate(),
                OrigenDeLaFicha.valueOf(fila.getString("origen")),
                fila.getString("documento_origen"),
                Observacion.de(fila.getString("observacion")),
                List.of(),
                List.of(),
                null);
    }

    private static Construccion mapearConstruccion(ResultSet fila, int numeroDeFila)
            throws SQLException {
        int anio = fila.getInt("anio_construccion");
        Ejercicio ejercicio = fila.wasNull() ? null : new Ejercicio(anio);
        BigDecimal porcentaje = fila.getBigDecimal("porcentaje_construido");

        return new Construccion(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getString("piso"),
                new AreaM2(fila.getBigDecimal("area_construida")),
                ejercicio,
                valorDe(MaterialEstructural.class, fila.getString("material_estructural")),
                valorDe(EstadoDeConservacion.class, fila.getString("estado_conservacion")),
                new CategoriasConstructivas(
                        caracter(fila, "categoria_muros"),
                        caracter(fila, "categoria_techos"),
                        caracter(fila, "categoria_pisos"),
                        caracter(fila, "categoria_puertas"),
                        caracter(fila, "categoria_revestim"),
                        caracter(fila, "categoria_banios"),
                        caracter(fila, "categoria_instalac")),
                porcentaje == null ? null : new Porcentaje(porcentaje));
    }

    private static OtraInstalacion mapearInstalacion(ResultSet fila, int numeroDeFila)
            throws SQLException {
        int anio = fila.getInt("anio_construccion");
        Ejercicio ejercicio = fila.wasNull() ? null : new Ejercicio(anio);

        return new OtraInstalacion(
                fila.getLong("id"),
                fila.getLong("ficha_id"),
                fila.getString("descripcion"),
                new Medida(fila.getBigDecimal("cantidad"), fila.getString("unidad_medida")),
                ejercicio,
                valorDe(EstadoDeConservacion.class, fila.getString("estado_conservacion")));
    }

    private static <E extends Enum<E>> @Nullable E valorDe(Class<E> tipo, @Nullable String nombre) {
        return nombre == null ? null : Enum.valueOf(tipo, nombre);
    }

    private static @Nullable Character caracter(ResultSet fila, String columna)
            throws SQLException {
        String valor = fila.getString(columna);
        return valor == null || valor.isEmpty() ? null : valor.charAt(0);
    }

    /** El frontis se guarda en metros lineales; la columna no lleva unidad porque solo hay una. */
    private static @Nullable Medida frontisDe(ResultSet fila) throws SQLException {
        BigDecimal valor = fila.getBigDecimal("frontis");
        return valor == null ? null : new Medida(valor, "ML");
    }

    private static @Nullable String texto(@Nullable Character categoria) {
        return categoria == null ? null : String.valueOf(categoria);
    }

    private static @Nullable String nombreDe(@Nullable Enum<?> valor) {
        return valor == null ? null : valor.name();
    }

    /** Quien registra. La columna es {@code NOT NULL} y sale del contexto de origen. */
    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }

    /** Se quiso cerrar una version que ya estaba cerrada, o que no existe aqui. */
    public static final class VersionNoVigente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VersionNoVigente(long id) {
            super(
                    "La version "
                            + id
                            + " no esta vigente en esta municipalidad; cerrarla otra vez"
                            + " reescribiria el historial");
        }
    }
}
