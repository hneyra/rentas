package kamayuk.rentas.catastro.prueba;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: la transferencia de titularidad, sobre el escenario (P5C).
 *
 * <h2>Que hace, y que NO comprueba</h2>
 *
 * <p>Cierra la cuota anterior el dia antes y abre la del adquiriente —y la del remanente, si la
 * transferencia es parcial—, que es lo que el puerto promete. Lo hace en la MISMA transaccion,
 * porque es lo que las pruebas de {@code rentas} necesitan para poder afirmar que la transferencia
 * es atomica con el acto que la registra.
 *
 * <p><b>No comprueba que los porcentajes no excedan 100.</b> Eso lo hace un disparador diferido de
 * {@code catastro} —`titularidad_no_excede_trg`— y `V6` lo retiro de esta base con su tabla; su
 * prueba vive alli. Repetirlo aqui mediria el fixture.
 */
public final class TitularidadDelEscenario implements GestorDeTitularidad {

    private final JdbcClient jdbc;

    public TitularidadDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CuotaDeTitularidad> vigenteDe(
            long predioId, long contribuyenteId, LocalDate fecha) {
        return jdbc.sql(
                        """
                        SELECT id, predio_id, contribuyente_id, porcentaje
                          FROM titularidad_de_prueba
                         WHERE predio_id = :predioId
                           AND contribuyente_id = :contribuyenteId
                           AND vigencia_desde <= :fecha
                           AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)
                         ORDER BY id
                         LIMIT 1
                        """)
                .param("predioId", predioId)
                .param("contribuyenteId", contribuyenteId)
                .param("fecha", Date.valueOf(fecha))
                .query(
                        (fila, numero) ->
                                new CuotaDeTitularidad(
                                        fila.getLong("id"),
                                        fila.getLong("predio_id"),
                                        fila.getLong("contribuyente_id"),
                                        Porcentaje.de(
                                                fila.getBigDecimal("porcentaje").toPlainString())))
                .optional();
    }

    @Override
    public CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        Fila anterior =
                jdbc.sql(
                                "SELECT municipalidad_id, predio_id, contribuyente_id, porcentaje"
                                        + "  FROM titularidad_de_prueba WHERE id = :id")
                        .param("id", titularidadAnteriorId)
                        .query(
                                (f, n) ->
                                        new Fila(
                                                f.getLong("municipalidad_id"),
                                                f.getLong("predio_id"),
                                                f.getLong("contribuyente_id"),
                                                Porcentaje.de(
                                                        f.getBigDecimal("porcentaje")
                                                                .toPlainString())))
                        .single();

        if (porcentajeTransferido.valor().compareTo(anterior.porcentaje().valor()) > 0) {
            throw new IllegalArgumentException(
                    "No se puede transferir "
                            + porcentajeTransferido
                            + ": la titularidad "
                            + titularidadAnteriorId
                            + " solo tiene "
                            + anterior.porcentaje());
        }

        jdbc.sql("UPDATE titularidad_de_prueba SET vigencia_hasta = :hasta" + " WHERE id = :id")
                .param("hasta", Date.valueOf(fecha.minusDays(1)))
                .param("id", titularidadAnteriorId)
                .update();

        long nueva = abrir(anterior, adquirienteId, porcentajeTransferido, fecha, documentoOrigen);

        java.math.BigDecimal remanente =
                anterior.porcentaje().valor().subtract(porcentajeTransferido.valor());
        if (remanente.signum() > 0) {
            abrir(
                    anterior,
                    anterior.contribuyenteId(),
                    Porcentaje.de(remanente.toPlainString()),
                    fecha,
                    documentoOrigen);
        }
        return new CuotaDeTitularidad(
                nueva, anterior.predioId(), adquirienteId, porcentajeTransferido);
    }

    private long abrir(
            Fila anterior,
            long contribuyenteId,
            Porcentaje porcentaje,
            LocalDate fecha,
            String documentoOrigen) {
        Long id =
                jdbc.sql(
                                "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id,"
                                        + " contribuyente_id, condicion, porcentaje,"
                                        + " vigencia_desde, documento_origen)"
                                        + " VALUES (:muni, :predio, :contribuyente,"
                                        + " 'PROPIETARIO_UNICO', :porcentaje, :desde, :documento)"
                                        + " RETURNING id")
                        .param("muni", anterior.municipalidadId())
                        .param("predio", anterior.predioId())
                        .param("contribuyente", contribuyenteId)
                        .param("porcentaje", porcentaje.valor())
                        .param("desde", Date.valueOf(fecha))
                        .param("documento", documentoOrigen)
                        .query(Long.class)
                        .single();
        return id == null ? 0 : id;
    }

    private record Fila(
            long municipalidadId, long predioId, long contribuyenteId, Porcentaje porcentaje) {}
}
