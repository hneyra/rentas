package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla «Vehículos en depósito» (#50, RF-064).
 *
 * <h2>Días sí, importe no</h2>
 *
 * <p>El prototipo dibuja «Tasa diaria S/» y «Custodia S/» en la grilla. Aquí no están, y no es un
 * olvido: la tarifa de la custodia vive en {@code tasa} y su ordenanza es D-02b, que sigue abierta.
 * Publicar una cifra compuesta con una tarifa inventada sería peor que no publicarla —el
 * administrado pagaría lo que la pantalla diga—. Lo que sí se puede decir sin inventar nada es
 * cuántos <b>días</b> lleva el vehículo y con qué <b>concepto</b> del TUPA se cobra; la tarifa la
 * pone la caja al cobrar, que es donde vive (regla 5).
 *
 * @param id el identificador del internamiento
 * @param placa la placa del vehículo
 * @param numeroPapeleta el número de la papeleta que dispuso la medida; nulo si no hubo
 * @param deposito dónde está o estuvo
 * @param fechaIngreso el día que entró
 * @param fechaSalida el día que salió; nulo si sigue dentro
 * @param dias cuántos días lleva —o llevó— en el depósito, a {@link #calculadoA}
 * @param calculadoA la fecha con la que se contaron los días (regla 9, RNF-075)
 * @param estado la situación derivada de los movimientos
 * @param tasaCustodia el concepto del TUPA con que se cobra la custodia
 * @param acta el número del acta de ingreso
 */
public record InternamientoEnConsulta(
        long id,
        String placa,
        @Nullable String numeroPapeleta,
        String deposito,
        LocalDate fechaIngreso,
        @Nullable LocalDate fechaSalida,
        int dias,
        LocalDate calculadoA,
        EstadoDeInternamiento estado,
        String tasaCustodia,
        String acta) {

    public InternamientoEnConsulta {
        Objects.requireNonNull(placa, "La fila necesita la placa");
        Objects.requireNonNull(deposito, "La fila necesita el deposito");
        Objects.requireNonNull(fechaIngreso, "La fila necesita la fecha de ingreso");
        Objects.requireNonNull(
                calculadoA, "Los dias se cuentan a una fecha, y la fila la dice (regla 9)");
        Objects.requireNonNull(estado, "La fila necesita su estado");
        Objects.requireNonNull(tasaCustodia, "La fila dice con que concepto se cobra la custodia");
        Objects.requireNonNull(acta, "La fila necesita el acta de ingreso");
        if (dias < 0) {
            throw new IllegalArgumentException("Los dias en deposito no pueden ser negativos");
        }
    }
}
