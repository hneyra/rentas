package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * El acto que cambia de titular un predio o un vehiculo (RF-026 parte de registro, RF-027, RF-030,
 * #29).
 *
 * <p><b>No calcula nada.</b> {@code afectaAlcabala} es una clasificacion —el tipo de transferencia
 * decide si el impuesto aplica—, no el importe del impuesto: eso es {@code POST /rentas/alcabala} y
 * sigue bloqueado por D-02.
 *
 * <p><b>La deuda anterior no se toca aqui.</b> Registrar una transferencia no genera ni mueve
 * ningun asiento de cuenta corriente: la obligacion sigue del transferente hasta que una decision
 * explicita —con su propio sustento— la traslade por {@code POST /rentas/deuda/altas} o {@code
 * .../bajas} (#24). Estructurarlo de otra forma exigiria decidir <b>cuanto</b> trasladar, y eso es
 * calculo, bloqueado por D-02.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param objeto que se transfiere
 * @param predioId el predio, si {@code objeto} es PREDIO
 * @param vehiculoId el vehiculo, si {@code objeto} es VEHICULO
 * @param transferenteId quien sale
 * @param adquirienteId quien entra
 * @param tipoTransferencia que acto fue, de los nueve que el manual dibuja ({@link
 *     TipoTransferencia})
 * @param fechaTransferencia cuando ocurrio el acto
 * @param valorTransferencia el valor declarado del acto
 * @param porcentajeTransferido cuanto cambia de manos; en un vehiculo siempre el total
 * @param afectaAlcabala si el tipo de transferencia grava alcabala
 * @param documentoOrigen la escritura, el parte notarial o la resolucion que la sustenta
 * @param observacion por que se registra (regla 10)
 * @param usuarioRegistro quien la registro; nulo en una transferencia que todavia no se guardo
 */
public record Transferencia(
        @Nullable Long id,
        ObjetoDeTransferencia objeto,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        long transferenteId,
        long adquirienteId,
        TipoTransferencia tipoTransferencia,
        LocalDate fechaTransferencia,
        Dinero valorTransferencia,
        Porcentaje porcentajeTransferido,
        boolean afectaAlcabala,
        String documentoOrigen,
        Observacion observacion,
        @Nullable String usuarioRegistro) {

    private static final int DOCUMENTO_MAXIMO = 80;

    public Transferencia {
        Objects.requireNonNull(objeto, "La transferencia necesita que se diga que se transfiere");
        Objects.requireNonNull(tipoTransferencia, "La transferencia necesita su tipo");
        Objects.requireNonNull(fechaTransferencia, "La transferencia necesita su fecha");
        Objects.requireNonNull(valorTransferencia, "La transferencia necesita su valor declarado");
        if (valorTransferencia.esNegativo()) {
            throw new IllegalArgumentException("El valor de transferencia no puede ser negativo");
        }
        Objects.requireNonNull(
                porcentajeTransferido, "La transferencia necesita cuanto cambia de manos");
        Objects.requireNonNull(
                documentoOrigen,
                "La transferencia necesita el documento que la sustenta: escritura, parte notarial"
                        + " o resolucion");
        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda una transferencia (regla 10)");
        if (transferenteId <= 0) {
            throw new IllegalArgumentException(
                    "El transferente es obligatorio: identificador invalido " + transferenteId);
        }
        if (adquirienteId <= 0) {
            throw new IllegalArgumentException(
                    "El adquiriente es obligatorio: identificador invalido " + adquirienteId);
        }
        if (transferenteId == adquirienteId) {
            throw new IllegalArgumentException(
                    "El transferente y el adquiriente no pueden ser el mismo contribuyente");
        }
        if (objeto == ObjetoDeTransferencia.PREDIO) {
            Objects.requireNonNull(predioId, "Una transferencia de predio necesita el predio");
            if (vehiculoId != null) {
                throw new IllegalArgumentException("Una transferencia de predio no lleva vehiculo");
            }
        } else {
            Objects.requireNonNull(
                    vehiculoId, "Una transferencia de vehiculo necesita el vehiculo");
            if (predioId != null) {
                throw new IllegalArgumentException("Una transferencia de vehiculo no lleva predio");
            }
        }
    }

    /** Transferencia de una cuota de un predio: {@code porcentajeTransferido} puede ser parcial. */
    public static Transferencia dePredio(
            long predioId,
            long transferenteId,
            long adquirienteId,
            TipoTransferencia tipoTransferencia,
            LocalDate fecha,
            Dinero valorTransferencia,
            Porcentaje porcentajeTransferido,
            boolean afectaAlcabala,
            String documentoOrigen,
            Observacion observacion) {
        return new Transferencia(
                null,
                ObjetoDeTransferencia.PREDIO,
                predioId,
                null,
                transferenteId,
                adquirienteId,
                tipoTransferencia,
                fecha,
                valorTransferencia,
                porcentajeTransferido,
                afectaAlcabala,
                documentoOrigen,
                observacion,
                null);
    }

    /** Transferencia de un vehiculo: siempre el total, un vehiculo no tiene copropietarios. */
    public static Transferencia deVehiculo(
            long vehiculoId,
            long transferenteId,
            long adquirienteId,
            TipoTransferencia tipoTransferencia,
            LocalDate fecha,
            Dinero valorTransferencia,
            boolean afectaAlcabala,
            String documentoOrigen,
            Observacion observacion) {
        return new Transferencia(
                null,
                ObjetoDeTransferencia.VEHICULO,
                null,
                vehiculoId,
                transferenteId,
                adquirienteId,
                tipoTransferencia,
                fecha,
                valorTransferencia,
                Porcentaje.total(),
                afectaAlcabala,
                documentoOrigen,
                observacion,
                null);
    }

    public boolean esNueva() {
        return id == null;
    }
}
