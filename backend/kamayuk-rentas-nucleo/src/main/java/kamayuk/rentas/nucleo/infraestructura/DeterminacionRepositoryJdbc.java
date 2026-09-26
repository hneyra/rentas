package kamayuk.rentas.nucleo.infraestructura;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.OrigenDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.OrigenDelAutovaluo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las determinaciones prediales contra PostgreSQL (#30).
 *
 * <p>{@link #insertar} escribe la cabecera y el detalle en dos pasos, dentro de la misma
 * transaccion que abre el caso de uso que la llama ({@code RepositorioJdbc} no abre la suya):
 * primero la cabecera, para obtener el {@code id} que el detalle referencia por la clave foranea
 * compuesta {@code (municipalidad_id, ejercicio, determinacion_id)}.
 *
 * <p>{@code reglas_aplicadas} es {@code varchar(200)[]}: se escribe con {@code string_to_array}
 * sobre una cadena separada por comas —ningun identificador de regla lleva coma (ver {@code
 * IdentificadorDeRegla})— porque un parametro con nombre de {@code JdbcClient} no mapea un {@code
 * String[]} de Java a un arreglo de PostgreSQL sin pasar por {@code Connection.createArrayOf}.
 */
@Repository
public class DeterminacionRepositoryJdbc extends RepositorioJdbc
        implements DeterminacionRepository {

    private static final String COLUMNAS_CABECERA =
            "d.id, d.ejercicio, d.tributo, d.periodo, d.contribuyente_id, d.predio_id,"
                    + " d.vehiculo_id, d.conjunto_id, d.base_imponible, d.monto_determinado,"
                    + " d.reglas_aplicadas, d.origen, d.estado, d.usuario_calculo,"
                    + " d.modalidad";

    private static final String COLUMNAS_DETALLE =
            "t.id, t.predio_id, t.autovaluo, t.valuo_exonerado, t.porcentaje_propiedad,"
                    + " t.base_imponible_predio, t.autovaluo_origen, t.valuacion_conjunto_id,"
                    + " t.valuacion_huella, t.autovaluo_declarado";

    public DeterminacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Determinacion> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS_CABECERA + " FROM determinacion d WHERE d.id = :id")
                .param("id", id)
                .query(DeterminacionRepositoryJdbc::mapearCabecera)
                .optional();
    }

    @Override
    public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
        // DISTINCT ON se queda con la primera fila de cada grupo del ORDER BY, asi que el orden
        // «contribuyente, id DESC» entrega exactamente la ultima determinacion de cada uno. La
        // alternativa —un MAX(id) agrupado y un segundo cruce— lee la tabla dos veces para lo
        // mismo.
        return jdbc().sql(
                        "SELECT DISTINCT ON (d.contribuyente_id) "
                                + COLUMNAS_CABECERA
                                + " FROM determinacion d"
                                + " WHERE d.ejercicio = :ejercicio AND d.tributo = 'PREDIAL'"
                                + " ORDER BY d.contribuyente_id, d.id DESC")
                .param("ejercicio", ejercicio.valor())
                .query(DeterminacionRepositoryJdbc::mapearCabecera)
                .list();
    }

    @Override
    public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_CABECERA
                                + " FROM determinacion d"
                                + " WHERE d.ejercicio = :ejercicio AND d.tributo = 'PREDIAL'"
                                + "   AND d.contribuyente_id = :contribuyenteId"
                                + " ORDER BY d.id DESC LIMIT 1")
                .param("ejercicio", ejercicio.valor())
                .param("contribuyenteId", contribuyenteId)
                .query(DeterminacionRepositoryJdbc::mapearCabecera)
                .optional();
    }

    @Override
    public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_DETALLE
                                + " FROM determinacion_predio_detalle t"
                                + " WHERE t.determinacion_id = :determinacionId"
                                + " ORDER BY t.id")
                .param("determinacionId", determinacionId)
                .query(DeterminacionRepositoryJdbc::mapearDetalle)
                .list();
    }

    @Override
    public Determinacion insertar(
            Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
        if (detalle.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una determinacion predial sin ningun predio en el detalle no tiene de donde"
                            + " salir su base (NEG-05 §1)");
        }
        String usuario = OrigenContext.actual().usuario();
        Determinacion guardada = insertarCabecera(determinacion, usuario);
        long id = exigirId(guardada);

        for (DetalleDeterminacionPredio fila : detalle) {
            jdbc().sql(
                            "INSERT INTO determinacion_predio_detalle"
                                    + " (municipalidad_id, ejercicio, determinacion_id, predio_id,"
                                    + "  autovaluo, valuo_exonerado, porcentaje_propiedad,"
                                    + "  base_imponible_predio, autovaluo_origen,"
                                    + "  valuacion_conjunto_id, valuacion_huella,"
                                    + "  autovaluo_declarado)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :ejercicio, :determinacionId, :predioId, :autovaluo,"
                                    + "  :exonerado, :porcentaje, :baseImponiblePredio,"
                                    + "  :origen, :conjuntoDeLaValuacion, :huellaDeLaValuacion,"
                                    + "  :autovaluoDeclarado)")
                    .param("ejercicio", determinacion.ejercicio().valor())
                    .param("determinacionId", id)
                    .param("predioId", fila.predioId())
                    .param("autovaluo", fila.autovaluo().valor())
                    .param("exonerado", fila.valuoExonerado().valor())
                    .param("porcentaje", fila.porcentajePropiedad().valor())
                    .param("baseImponiblePredio", fila.baseImponiblePredio().valor())
                    .param("origen", fila.origen().name())
                    .param("conjuntoDeLaValuacion", fila.valuacionConjuntoId())
                    .param("huellaDeLaValuacion", fila.valuacionHuella())
                    // La declarada, cuando mando la sellada (#362, V32): sin esta linea la cifra
                    // que la fiscalizacion contrasta se quedaba en memoria.
                    .param(
                            "autovaluoDeclarado",
                            fila.autovaluoDeclarado() == null
                                    ? null
                                    : fila.autovaluoDeclarado().valor(),
                            java.sql.Types.NUMERIC)
                    .update();
        }

        return guardada;
    }

    @Override
    public Determinacion insertar(Determinacion determinacion) {
        return insertarCabecera(determinacion, OrigenContext.actual().usuario());
    }

    /**
     * Escribe la cabecera y devuelve <b>lo que quedo en la fila</b>, no lo que se le paso (#378).
     *
     * <p>{@code base_imponible} y {@code monto_determinado} son {@code dinero numeric(15,2)} sin
     * {@code CHECK} de escala: PostgreSQL <b>coacciona</b> un importe con mas decimales al
     * guardarlo y no da error. Hasta #378 esto pedia solo {@code RETURNING id} y devolvia el objeto
     * en memoria, asi que un importe que llegara sin redondear salia en la respuesta y en la
     * auditoria con una cifra y quedaba en la fila con otra —que es lo que pasaba con el vehicular,
     * la alcabala y los espectaculos—. Devolviendo las dos columnas tal como quedaron, la segunda
     * verdad no puede volver aunque alguien vuelva a olvidar redondear.
     */
    private Determinacion insertarCabecera(Determinacion determinacion, String usuario) {
        return jdbc().sql(
                        "INSERT INTO determinacion"
                                + " (municipalidad_id, ejercicio, tributo, periodo,"
                                + "  contribuyente_id, predio_id, vehiculo_id, conjunto_id,"
                                + "  base_imponible, monto_determinado, reglas_aplicadas,"
                                + "  origen, estado, usuario_calculo, modalidad)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ejercicio, :tributo, :periodo, :contribuyenteId,"
                                + "  :predioId, :vehiculoId, :conjuntoId, :baseImponible,"
                                + "  :montoDeterminado,"
                                + "  string_to_array(:reglas, ',')::varchar(200)[],"
                                + "  :origen, :estado, :usuario, :modalidad)"
                                + " RETURNING id, base_imponible, monto_determinado")
                .param("ejercicio", determinacion.ejercicio().valor())
                .param("tributo", determinacion.tributo())
                .param("periodo", determinacion.periodo())
                .param("contribuyenteId", determinacion.contribuyenteId())
                .param("predioId", determinacion.predioId())
                .param("vehiculoId", determinacion.vehiculoId())
                .param("conjuntoId", determinacion.conjuntoId())
                .param("baseImponible", determinacion.baseImponible().valor())
                .param("montoDeterminado", determinacion.montoDeterminado().valor())
                .param("reglas", String.join(",", determinacion.reglasAplicadas()))
                .param("origen", determinacion.origen().name())
                .param("estado", determinacion.estado().name())
                .param("usuario", usuario)
                // `name()` y no `toString()`: lo que la columna guarda es el nombre del
                // enumerado, que es lo que `determinacion_modalidad_ck` admite (V21). Nulo en
                // todo tributo que no sea el predial.
                .param(
                        "modalidad",
                        determinacion.modalidad() == null ? null : determinacion.modalidad().name())
                .query(
                        (fila, numero) ->
                                guardada(
                                        determinacion,
                                        fila.getLong("id"),
                                        new Dinero(fila.getBigDecimal("base_imponible")),
                                        new Dinero(fila.getBigDecimal("monto_determinado")),
                                        usuario))
                .single();
    }

    private static long exigirId(Determinacion guardada) {
        Long id = guardada.id();
        if (id == null) {
            throw new IllegalStateException("Una determinacion recien insertada tiene id");
        }
        return id;
    }

    /** La determinacion como quedo: su id y sus dos importes, leidos de la fila (#378). */
    private static Determinacion guardada(
            Determinacion determinacion,
            long id,
            Dinero baseImponible,
            Dinero montoDeterminado,
            String usuario) {
        return new Determinacion(
                id,
                determinacion.ejercicio(),
                determinacion.tributo(),
                determinacion.periodo(),
                determinacion.contribuyenteId(),
                determinacion.predioId(),
                determinacion.vehiculoId(),
                determinacion.conjuntoId(),
                baseImponible,
                montoDeterminado,
                determinacion.reglasAplicadas(),
                determinacion.origen(),
                determinacion.estado(),
                usuario,
                determinacion.modalidad());
    }

    private static Determinacion mapearCabecera(ResultSet fila, int numeroDeFila)
            throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        int periodo = fila.getInt("periodo");
        Integer periodoValor = fila.wasNull() ? null : periodo;

        return new Determinacion(
                fila.getLong("id"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getString("tributo"),
                periodoValor,
                fila.getLong("contribuyente_id"),
                predioId,
                vehiculoId,
                fila.getLong("conjunto_id"),
                new Dinero(fila.getBigDecimal("base_imponible")),
                new Dinero(fila.getBigDecimal("monto_determinado")),
                reglasDe(fila.getArray("reglas_aplicadas")),
                OrigenDeDeterminacion.valueOf(fila.getString("origen")),
                EstadoDeDeterminacion.valueOf(fila.getString("estado")),
                fila.getString("usuario_calculo"),
                modalidadDe(fila.getString("modalidad")));
    }

    /**
     * La modalidad de la fila, o {@code null} si la fila es <b>anterior a V21</b>.
     *
     * <p>Se LEE de la columna y no se supone. Devolver {@code TRIMESTRAL} cuando la columna viene
     * vacia haria indistinguible una determinacion que se emitio en cuatro cuotas de una de la que
     * no consta nada, que es el defecto entero de #234 movido un piso mas abajo.
     */
    private static @Nullable ModalidadDelPredial modalidadDe(@Nullable String columna) {
        return columna == null ? null : ModalidadDelPredial.valueOf(columna);
    }

    private static List<String> reglasDe(Array arreglo) throws SQLException {
        List<String> reglas = new ArrayList<>();
        for (Object regla : (Object[]) arreglo.getArray()) {
            reglas.add((String) regla);
        }
        return reglas;
    }

    private static DetalleDeterminacionPredio mapearDetalle(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new DetalleDeterminacionPredio(
                fila.getLong("id"),
                fila.getLong("predio_id"),
                new Dinero(fila.getBigDecimal("autovaluo")),
                new Dinero(fila.getBigDecimal("valuo_exonerado")),
                new Porcentaje(fila.getBigDecimal("porcentaje_propiedad")),
                new Dinero(fila.getBigDecimal("base_imponible_predio")),
                OrigenDelAutovaluo.valueOf(fila.getString("autovaluo_origen")),
                fila.getObject("valuacion_conjunto_id", Long.class),
                fila.getString("valuacion_huella"),
                dineroONulo(fila.getBigDecimal("autovaluo_declarado")));
    }

    private static @Nullable Dinero dineroONulo(@Nullable BigDecimal valor) {
        return valor == null ? null : new Dinero(valor);
    }
}
