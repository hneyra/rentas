package kamayuk.rentas.rentas.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.CambioDePlaca;
import kamayuk.rentas.rentas.dominio.CriterioDeVehiculo;
import kamayuk.rentas.rentas.dominio.EstadoVehiculo;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoEncontrado;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Padron vehicular sobre PostgreSQL. */
@Repository
public class VehiculoRepositoryJdbc extends RepositorioJdbc implements VehiculoRepository {

    private static final String COLUMNAS =
            "id, placa, contribuyente_id, marca, modelo, categoria, anio_fabricacion,"
                    + " anio_inscripcion, numero_motor, numero_serie, estado";

    /** El nombre con el que la auditoria llavea al vehiculo. */
    static final String TABLA = "vehiculo";

    private static final OrdenSeguro ORDEN = OrdenSeguro.sobre("placa", "marca", "modelo", "id");

    /**
     * Como {@link #COLUMNAS}, calificadas para el {@code JOIN} de {@link #buscar}, con el titular.
     */
    private static final String COLUMNAS_CON_TITULAR =
            "v.id, v.placa, v.contribuyente_id, v.marca, v.modelo, v.categoria,"
                    + " v.anio_fabricacion, v.anio_inscripcion, v.numero_motor, v.numero_serie,"
                    + " v.estado, c.nombre_razon_social, c.codigo_contribuyente";

    public VehiculoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * Busca comparando la placa <b>sin su guion</b>, igual que la compara {@code Placa} y que la
     * exige el indice unico de V17.
     *
     * <p>{@code replace(placa,'-','')} sobre la columna es exactamente la expresion del indice, asi
     * que el planificador puede usarlo. Escrito de cualquier otra forma equivalente —un {@code
     * translate}, un {@code LIKE}— el indice deja de servir y la busqueda por placa, que es la mas
     * frecuente en ventanilla, pasa a recorrer el padron entero.
     */
    @Override
    public Optional<Vehiculo> findByPlaca(Placa placa) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM vehiculo WHERE replace(placa, '-', '') = :placa")
                .param("placa", placa.sinSeparador())
                .query(VehiculoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Vehiculo> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM vehiculo WHERE id = :id")
                .param("id", id)
                .query(VehiculoRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * El padron con el titular resuelto en el mismo {@code JOIN} (#25).
     *
     * <p>{@code placa} es por igualdad —sin el guion, como {@link #findByPlaca}— y no por prefijo:
     * un {@code LIKE} bajo RLS no llega nunca al indice (ver {@code FichaCatastralRepositoryJdbc}),
     * y la placa completa es lo que trae ventanilla cuando busca un vehiculo concreto.
     */
    @Override
    public Pagina<VehiculoEncontrado> buscar(CriterioDeVehiculo criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.placa() != null) {
            condiciones.add("replace(v.placa, '-', '') = :placa");
            parametros.put("placa", criterio.placa().replace("-", ""));
        }
        if (criterio.nroMotor() != null) {
            condiciones.add("v.numero_motor = :nroMotor");
            parametros.put("nroMotor", criterio.nroMotor());
        }
        if (criterio.contribuyente() != null) {
            condiciones.add("c.codigo_contribuyente = :contribuyente");
            parametros.put("contribuyente", criterio.contribuyente());
        }
        if (criterio.estado() != null) {
            condiciones.add("v.estado = :estado");
            parametros.put("estado", criterio.estado().name());
        }

        String desde = " FROM vehiculo v JOIN contribuyente c ON c.id = v.contribuyente_id";
        String donde = condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS_CON_TITULAR + desde + donde,
                "SELECT count(*)" + desde + donde,
                parametros,
                paginacion,
                ORDEN,
                VehiculoRepositoryJdbc::mapearEncontrado);
    }

    @Override
    public Vehiculo save(Vehiculo vehiculo) {
        return vehiculo.esNuevo() ? insertar(vehiculo) : actualizar(vehiculo);
    }

    /**
     * El historial de placas, leido de la auditoria por el identificador del vehiculo.
     *
     * <p>Se filtra por la existencia de la clave {@code placa} en el JSON —no por el texto de la
     * observacion—: una modificacion que no toco la placa no tiene ese campo, y asi el historial no
     * se llena de filas que no son cambios de placa.
     *
     * <p>Se usa {@code jsonb_exists(...)} y <b>no</b> el operador {@code ?}, que es su forma
     * habitual: JDBC lee el {@code ?} como marcador de parametro y la sentencia se rompe con «Not
     * allowed to mix named and traditional placeholders». Es un choque de sintaxis entre PostgreSQL
     * y JDBC, no un problema de esta consulta, y la funcion equivalente lo evita.
     */
    @Override
    public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
        return jdbc().sql(
                        """
                        SELECT datos_anteriores ->> 'placa' AS anterior,
                               datos_nuevos      ->> 'placa' AS nueva,
                               usuario_id, fecha, observacion
                          FROM auditoria
                         WHERE tabla = :tabla
                           AND clave = :clave
                           AND jsonb_exists(datos_anteriores, 'placa')
                           AND jsonb_exists(datos_nuevos, 'placa')
                         ORDER BY fecha DESC, id DESC
                        """)
                .param("tabla", TABLA)
                .param("clave", String.valueOf(vehiculoId))
                .query(
                        (ResultSet fila, int numero) ->
                                new CambioDePlaca(
                                        Placa.de(fila.getString("anterior")),
                                        Placa.de(fila.getString("nueva")),
                                        fila.getString("usuario_id"),
                                        fila.getObject("fecha", java.time.OffsetDateTime.class),
                                        fila.getString("observacion")))
                .list();
    }

    private Vehiculo insertar(Vehiculo vehiculo) {
        Long id =
                jdbc().sql(
                                "INSERT INTO vehiculo"
                                        + " (municipalidad_id, placa, contribuyente_id, marca,"
                                        + "  modelo, categoria, anio_fabricacion, anio_inscripcion,"
                                        + "  numero_motor, numero_serie, estado)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :placa, :contribuyente, :marca, :modelo, :categoria,"
                                        + "  :fabricacion, :inscripcion, :motor, :serie, :estado)"
                                        + " RETURNING id")
                        .params(parametros(vehiculo))
                        .query(Long.class)
                        .single();
        return new Vehiculo(
                id,
                vehiculo.placa(),
                vehiculo.contribuyenteId(),
                vehiculo.marca(),
                vehiculo.modelo(),
                vehiculo.categoria(),
                vehiculo.anioFabricacion(),
                vehiculo.anioInscripcion(),
                vehiculo.numeroMotor(),
                vehiculo.numeroSerie(),
                vehiculo.estado());
    }

    private Vehiculo actualizar(Vehiculo vehiculo) {
        long id =
                Objects.requireNonNull(vehiculo.id(), "Un vehiculo existente tiene identificador");
        int filas =
                jdbc().sql(
                                """
                                UPDATE vehiculo
                                   SET placa = :placa,
                                       contribuyente_id = :contribuyente,
                                       marca = :marca,
                                       modelo = :modelo,
                                       categoria = :categoria,
                                       anio_fabricacion = :fabricacion,
                                       anio_inscripcion = :inscripcion,
                                       numero_motor = :motor,
                                       numero_serie = :serie,
                                       estado = :estado
                                 WHERE id = :id
                                """)
                        .params(parametros(vehiculo))
                        .param("id", id)
                        .update();
        if (filas != 1) {
            throw new IllegalStateException("No se actualizo el vehiculo " + id);
        }
        return vehiculo;
    }

    private static java.util.Map<String, Object> parametros(Vehiculo vehiculo) {
        java.util.Map<String, Object> valores = new java.util.HashMap<>();
        valores.put("placa", vehiculo.placa().valor());
        valores.put("contribuyente", vehiculo.contribuyenteId());
        valores.put("marca", vehiculo.marca());
        valores.put("modelo", vehiculo.modelo());
        valores.put("categoria", vehiculo.categoria());
        valores.put("fabricacion", vehiculo.anioFabricacion().valor());
        valores.put("inscripcion", vehiculo.anioInscripcion().valor());
        valores.put("motor", vehiculo.numeroMotor());
        valores.put("serie", vehiculo.numeroSerie());
        valores.put("estado", vehiculo.estado().name());
        return valores;
    }

    private static VehiculoEncontrado mapearEncontrado(ResultSet fila, int numero)
            throws SQLException {
        return new VehiculoEncontrado(
                mapear(fila, numero),
                fila.getString("nombre_razon_social"),
                fila.getString("codigo_contribuyente"));
    }

    private static Vehiculo mapear(ResultSet fila, int numero) throws SQLException {
        return new Vehiculo(
                fila.getLong("id"),
                Placa.de(fila.getString("placa")),
                fila.getLong("contribuyente_id"),
                fila.getString("marca"),
                fila.getString("modelo"),
                fila.getString("categoria"),
                new Ejercicio(fila.getInt("anio_fabricacion")),
                new Ejercicio(fila.getInt("anio_inscripcion")),
                fila.getString("numero_motor"),
                fila.getString("numero_serie"),
                EstadoVehiculo.valueOf(fila.getString("estado")));
    }
}
