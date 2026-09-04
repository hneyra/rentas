package kamayuk.rentas.fiscalizacion.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeDeteccion;
import kamayuk.rentas.fiscalizacion.dominio.DeteccionRepository;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El cruce padrón-declaraciones contra PostgreSQL (#545, RF-055).
 *
 * <p>Por qué esta consulta vive aquí y no en {@code catastro} ni en {@code rentas} está escrito en
 * {@link DeteccionRepository}. Lo que hay que saber para leerla es esto:
 *
 * <ol>
 *   <li><b>La condición se escribe una sola vez.</b> {@link #CONDICION} entra en el {@code SELECT}
 *       de la subconsulta y el {@code WHERE} de fuera la filtra por su alias. Dos copias del mismo
 *       {@code CASE} —una para pintar la columna y otra para filtrar— es el defecto que #397 midió:
 *       divergen, y la fila que se lee acaba no siendo la que el filtro dejó pasar.
 *   <li><b>La declaración se elige con un {@code LATERAL} que trae una sola fila.</b> Un predio
 *       puede tener más de una declaración vigente del mismo ejercicio, y la que cuenta es la más
 *       reciente: comparar contra la vieja acusaría de subvaluación a quien ya corrigió. Es el
 *       mismo desempate que hacía {@code DeclaracionesDelEjercicioRentas}, ahora en el motor.
 *   <li><b>Los tres {@code JOIN} son externos, y los tres a propósito.</b> Sin sector, sin ficha o
 *       sin declaración son los tres casos que esta consulta existe para encontrar. El de {@code
 *       titularidad} no está: los titulares se resuelven por el puerto público de catastro.
 *   <li><b>El conteo tiene dos formas, y la elige el filtro (#561).</b> Con filtro de condición hay
 *       que evaluarla, y eso obliga a mirar la declaración de cada predio. Sin filtro no: la
 *       condición se <b>pinta</b> en la fila, y un {@code count(*)} no pinta filas. La forma corta
 *       lee el padrón una vez en lugar de 14 422 —de 32 293 páginas tocadas a 555, medido— y cuenta
 *       lo mismo, porque los dos {@code JOIN} que se deja no pueden cambiar el número de filas. Las
 *       dos formas se componen de los <b>mismos</b> trozos de texto, que es lo que impide que una
 *       diga una cosa y la otra otra.
 * </ol>
 *
 * <p>El filtro de sector va con la bandera {@code :conSector} en vez de con {@code :sector IS
 * NULL}: PostgreSQL no puede inferir el tipo de un parámetro que sólo aparece en un {@code IS
 * NULL}, y falla con «could not determine data type».
 */
@Repository
public class DeteccionRepositoryJdbc extends RepositorioJdbc implements DeteccionRepository {

    /**
     * La condición, transcrita de {@link
     * kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado#condicion}.
     *
     * <p>Las cinco preguntas de aquella función, en el mismo orden, para el cruce de gabinete:
     *
     * <ul>
     *   <li>{@code NO_UBICADO} no puede salir: no hay visita que pueda no ubicar nada. Lo que un
     *       cruce de gabinete «halla» es lo que el catastro tiene inscrito, y eso siempre está.
     *   <li>{@code OMISO} cuando no hay declaración vigente del ejercicio, y sólo entonces: quien
     *       declaró tarde llega aquí con su fila y sigue de largo (AC 3 de #49).
     *   <li>{@code SUBVALUADOR} cuando el área de la ficha vigente supera la de la ficha que la
     *       declaración referencia. Con cualquiera de las dos en nulo no hay comparación, y decir
     *       que la hay sería afirmar sobre lo que no se midió.
     *   <li>{@code USO_DISTINTO} tampoco sale, porque el uso hallado va en nulo: la detección
     *       compara superficies, que es lo único reproducible sin visita.
     *   <li>{@code CONFORME} en lo demás. Un área hallada <b>menor</b> que la declarada no es un
     *       hallazgo contra el contribuyente: declaró de más.
     * </ul>
     *
     * <p>Que esta transcripción no se separe de la función pura no lo garantiza este comentario: lo
     * garantiza {@code DeteccionDeOmisosJdbcTest}, que compara las dos caso por caso.
     */
    private static final String CONDICION =
            """
            CASE
              WHEN dj.id IS NULL THEN 'OMISO'
              WHEN f.area_terreno IS NOT NULL
               AND fd.area_terreno IS NOT NULL
               AND f.area_terreno > fd.area_terreno THEN 'SUBVALUADOR'
              ELSE 'CONFORME'
            END""";

    /**
     * El padrón: los predios activos con su sector y su ficha vigente a la fecha.
     *
     * <p>Está separado de la declaración porque el conteo sin filtro de condición <b>no la
     * necesita</b>, y ahí es donde estaba el coste (#561). Los dos trozos se concatenan para
     * componer {@link #DESDE}: lo que la página y el conteo comparten está escrito una sola vez,
     * que es lo que impide que diverjan (la lección de #397).
     */
    private static final String DESDE_EL_PADRON =
            """
             FROM predio_ref p
             LEFT JOIN ficha_ref f
               ON f.municipalidad_id = p.municipalidad_id
              AND f.predio_id = p.predio_id
              AND f.tipo = 'UNICA'
              AND f.vigencia_desde <= :fecha
              AND (f.vigencia_hasta IS NULL OR f.vigencia_hasta >= :fecha)
            """;

    /**
     * Y su declaración del ejercicio, que es lo único que la condición necesita y lo único que
     * cuesta el padrón entero.
     *
     * <p>Ninguno de los dos {@code JOIN} puede cambiar el número de filas, y por eso el conteo sin
     * filtro puede prescindir de ellos sin contar otra cosa: el {@code LATERAL} lleva {@code LIMIT
     * 1} y entra con {@code ON true} —o sea exactamente una fila por predio, con nulos si no hay
     * declaración—, y {@code fd} entra por la clave primaria de {@code ficha_catastral}, o sea a lo
     * sumo una. El que <b>podía</b> multiplicar —{@code f}— se queda en {@link #DESDE_EL_PADRON}, y
     * conviene decir por qué ya no es el mismo motivo: cuando esto se escribió (#561), {@code
     * ficha_vigente_uq} era <b>parcial</b> ({@code WHERE vigencia_hasta IS NULL}), así que una
     * versión abierta y una cerrada podían cubrir la misma fecha y la página devolvía dos filas de
     * ese predio. Ese hallazgo abrió #669, y desde {@code V72} la restricción {@code
     * ficha_vigencias_no_se_pisan} lo impide: el dato ya no puede existir. El {@code JOIN} se queda
     * igualmente porque quitarlo es un cambio de plan que nadie ha medido y su coste es cero —el
     * planificador lo elimina solo, que es justo lo que no puede hacer con el {@code LATERAL}—.
     */
    private static final String Y_SU_DECLARACION =
            """
             LEFT JOIN LATERAL (
                   SELECT d.id, d.fuera_de_plazo, d.ficha_catastral_id
                     FROM declaracion_jurada d
                    WHERE d.municipalidad_id = p.municipalidad_id
                      AND d.predio_id = p.predio_id
                      AND d.ejercicio = :ejercicio
                      AND d.estado = ANY(:estados)
                    ORDER BY d.fecha_presentacion DESC, d.id DESC
                    LIMIT 1
                 ) dj ON true
             LEFT JOIN ficha_ref fd
               ON fd.municipalidad_id = p.municipalidad_id
              AND fd.ficha_id = dj.ficha_catastral_id
            """;

    /** Lo que acota el conjunto, y lo hacen las dos formas por igual. */
    private static final String FILTRO_DEL_PADRON =
            """
            WHERE p.estado = :activo
              AND (NOT :conSector OR p.sector_codigo = :sector)
            """;

    private static final String DESDE = DESDE_EL_PADRON + Y_SU_DECLARACION + FILTRO_DEL_PADRON;

    /**
     * La diferencia de area, transcrita de {@link
     * kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado#diferenciaDeArea} (#608).
     *
     * <p>Es la <b>segunda</b> transcripcion de una funcion pura al motor que vive en este archivo,
     * y por el mismo motivo que la primera: se ordena por ella, y {@code ORDER BY} no puede llamar
     * a un metodo de Java. La funcion pura sigue siendo la que decide lo que la fila <b>enseña</b>
     * —{@code FilaDeOmisos.diferenciaDeArea()}—; esta columna solo decide en que orden salen.
     *
     * <p>Las tres preguntas de aquella funcion, en el mismo orden:
     *
     * <ul>
     *   <li>Sin uno de los dos lados no hay diferencia: {@code NULL}. Devolver cero diria que se
     *       midio y coincidio, que es lo contrario de lo que pasa —el caso mayoritario es el omiso,
     *       que no declaro nada—. <b>Esta rama es explicita y no operativa</b>: con cualquiera de
     *       los dos lados nulo, la comparacion de la rama siguiente ya es {@code NULL} y no se
     *       toma, y la resta del {@code ELSE} da {@code NULL} igual. Se escribe para que las tres
     *       preguntas de la funcion pura esten a la vista una a una; quitarla no cambia ninguna
     *       fila, y por eso ninguna mutacion la caza.
     *   <li>Un area hallada <b>menor o igual</b> que la declarada da cero: declarar de mas no es un
     *       hallazgo contra el contribuyente, y una diferencia negativa no existe.
     *   <li>Lo demas, lo que el catastro tiene de mas sobre lo declarado.
     * </ul>
     *
     * <p>Que esta transcripcion no se separe de la funcion pura tampoco lo garantiza este
     * comentario: lo garantiza {@code OrdenDeLaDeteccionFronteraTest}, que compara el <b>orden</b>
     * que produce el motor con el que produce la funcion pura sobre las mismas filas. Conviene no
     * subir la apuesta: compara el orden, no la cifra de cada fila caso por caso — eso es lo que
     * {@code LaCondicionCoincideConLaFuncionPura} hace para la condicion, y aqui no hay equivalente
     * porque la cifra que la fila enseña no sale de esta columna.
     */
    private static final String DIFERENCIA_DE_AREA =
            """
            CASE
              WHEN f.area_terreno IS NULL OR fd.area_terreno IS NULL THEN NULL::numeric
              WHEN f.area_terreno <= fd.area_terreno THEN 0
              ELSE f.area_terreno - fd.area_terreno
            END""";

    private static final String INTERIOR =
            "SELECT p.predio_id, p.codigo_ref_catastral, p.direccion,"
                    + " p.sector_codigo, f.area_terreno AS area_catastral,"
                    + " fd.area_terreno AS area_declarada,"
                    + " COALESCE(dj.fuera_de_plazo, false) AS fuera_de_plazo, "
                    + DIFERENCIA_DE_AREA
                    + " AS diferencia_de_area, "
                    + CONDICION
                    + " AS condicion"
                    + DESDE;

    /** El filtro de condición, sobre el alias de la subconsulta: la misma expresión, una vez. */
    private static final String FILTRO_DE_CONDICION =
            " WHERE (NOT :conCondicion OR d.condicion = :condicion)";

    /**
     * La página de la grilla. Visible en el paquete para que la prueba de plan le pida el plan a
     * <b>esta</b> cadena y no a una transcripción suya (la lección de #397).
     */
    static final String PAGINA = "SELECT d.* FROM (" + INTERIOR + ") d" + FILTRO_DE_CONDICION;

    /** El conteo cuando hay filtro de condición: hay que evaluarla, y eso cuesta el padrón. */
    static final String CONTEO_CON_CONDICION =
            "SELECT count(*) FROM (" + INTERIOR + ") d" + FILTRO_DE_CONDICION;

    /**
     * El conteo cuando <b>no</b> hay filtro de condición, que es como se abre la pantalla (#561).
     *
     * <p>Cuenta exactamente las mismas filas que {@link #CONTEO_CON_CONDICION} sin el filtro —mismo
     * {@code FROM}, mismo {@code WHERE}— y se ahorra los dos {@code JOIN} que sólo sirven para
     * <b>pintar</b> la condición. Ninguno de los dos puede cambiar el número de filas: eso está
     * razonado en {@link #Y_SU_DECLARACION} y comprobado contra el motor por {@code
     * ConteoDeLaDeteccionTest}, que compara las dos cifras predio a predio.
     *
     * <p>Lo que evita es el {@code LEFT JOIN LATERAL}: un descenso al índice de {@code
     * declaracion_jurada} <b>por cada predio del padrón</b>, con su {@code Sort} montado y
     * desmontado 14 422 veces. Medido sobre el padrón de Catacaos en dos municipalidades, como
     * {@code sgtm_app} y con RLS activa: <b>31 738 de las 32 293 páginas</b> que el conteo tocaba
     * eran eso, y sin ellos toca <b>555</b> (DAT-01 §7.2). Es el coste que no depende del tamaño de
     * página, que es el síntoma que da nombre a #561.
     */
    static final String CONTEO_SIN_CONDICION =
            "SELECT count(*)" + DESDE_EL_PADRON + FILTRO_DEL_PADRON;

    /**
     * Los dos estados en que una declaración sustenta algo, escritos aquí y no leídos de {@code
     * EstadoDeDeclaracion}: ese enumerado es {@code rentas.dominio}, y este contexto sólo importa
     * el paquete raíz de los demás (ARQ-01 §4 regla 1). Que los dos digan lo mismo lo comprueba
     * {@code DeteccionDeOmisosJdbcTest}, que siembra una declaración {@code SUSTITUIDA} y otra
     * {@code ANULADA} y exige que su predio salga OMISO.
     */
    private static final String[] ESTADOS_VIGENTES = {"PRESENTADA", "OBSERVADA"};

    /**
     * Solo predios <b>activos</b>, por lo mismo y escrito aqui por lo mismo: {@code EstadoPredio}
     * es {@code catastro.dominio}. Un predio dado de baja no genera obligacion, asi que marcarlo
     * como omiso seria abrir una fiscalizacion sobre algo que ya no existe.
     */
    private static final String PREDIO_ACTIVO = "ACTIVO";

    /**
     * Por lo que la fila <b>publica</b>, y por nada mas (#546, #608).
     *
     * <p>{@code codRefCatastral} es el nombre del campo de {@code OmisoResource}; el {@code
     * camelCase} automatico de la columna era {@code codigoRefCatastral}, o sea un segundo nombre
     * para el mismo dato que ademas era el unico que funcionaba. {@code direccion} sale de la lista
     * porque {@code OmisoResource} no la publica —ordenar por una columna que no esta en la fila no
     * se puede explicar en pantalla—, y {@code predio_id} pasa a <b>desempate</b>, que es para lo
     * que servia: da orden total sin ofrecerse como campo (#543).
     *
     * <h2>Los dos campos que #608 añade, y el que deja fuera</h2>
     *
     * <p>La pantalla dibuja siete columnas y hasta #608 se podia ordenar por <b>una</b>. Entran
     * {@code sector} —que la fila ya publica y la consulta ya filtraba— y {@code diferenciaDeArea}
     * —lo unico cuantificado que hoy distingue a un subvaluador—, que son ademas dos de los tres
     * que el «Ordenar por» del manual ofrece.
     *
     * <p>El tercero del manual, {@code impuestoOmitidoS}, <b>no entra</b>: es {@code null} en todas
     * las filas mientras D-02a siga abierta (#198), asi que ordenar por el no ordena nada —las dos
     * direcciones devolverian la misma primera fila— y la lista blanca lo sigue rechazando con
     * {@code 422 ORDEN_NO_ADMITIDO} nombrando el campo pedido. No se admite «porque este en el
     * manual»: se admitira el dia que tenga cifra.
     *
     * <p>Los dos que entran <b>admiten nulos</b>, y los dos por un motivo del dominio: el sector,
     * porque el predio sin sector es uno de los tres casos que esta consulta existe para encontrar;
     * la diferencia, porque sin las dos superficies no hay diferencia que calcular —el caso del
     * omiso, que es el mayoritario—. Sin {@code NULLS LAST}, «de mayor a menor» abriria por ellos.
     */
    static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("codigo_ref_catastral", "sector_codigo", "diferencia_de_area")
                    .publicandoComo("codRefCatastral", "codigo_ref_catastral")
                    .publicandoComo("sector", "sector_codigo")
                    .conNulosAlFinal("sector_codigo")
                    .conNulosAlFinal("diferencia_de_area")
                    .desempatandoPor("predio_id");

    public DeteccionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Pagina<FilaDeOmisos> detectar(CriterioDeDeteccion criterio, Paginacion paginacion) {
        String sector = criterio.sectorCodigo();
        CondicionFiscalizada condicion = criterio.condicion();

        Map<String, Object> parametros = new HashMap<>();
        parametros.put("fecha", criterio.aLaFecha());
        parametros.put("ejercicio", criterio.ejercicio().valor());
        parametros.put("estados", ESTADOS_VIGENTES);
        parametros.put("activo", PREDIO_ACTIVO);
        parametros.put("sector", sector == null ? "" : sector);
        parametros.put("conSector", sector != null);
        parametros.put("condicion", condicion == null ? "" : condicion.name());
        parametros.put("conCondicion", condicion != null);

        return paginar(
                PAGINA,
                condicion == null ? CONTEO_SIN_CONDICION : CONTEO_CON_CONDICION,
                Map.copyOf(parametros),
                paginacion,
                ORDEN,
                (fila, numeroDeFila) -> mapear(fila, criterio));
    }

    private static FilaDeOmisos mapear(ResultSet fila, CriterioDeDeteccion criterio)
            throws SQLException {
        return new FilaDeOmisos(
                fila.getLong("predio_id"),
                fila.getString("codigo_ref_catastral"),
                fila.getString("sector_codigo"),
                // Los titulares los resuelve el caso de uso, en una lectura por pagina.
                List.of(),
                criterio.ejercicio(),
                CondicionFiscalizada.porNombre(fila.getString("condicion")),
                fila.getBoolean("fuera_de_plazo"),
                area(fila.getBigDecimal("area_catastral")),
                area(fila.getBigDecimal("area_declarada")),
                null,
                null,
                null);
    }

    private static @Nullable AreaM2 area(@Nullable BigDecimal valor) {
        return valor == null ? null : new AreaM2(valor);
    }
}
