package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos.VehiculoConDeuda;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de {@code consulta_vehiculos}, tal como sale por HTTP. Campos en español {@code
 * camelCase} (ARQ-04 §3).
 *
 * <p>No lleva base imponible: el impuesto al patrimonio vehicular necesita la tabla de valores
 * referenciales, y eso sigue bloqueado por D-02 (ver el javadoc de {@code Vehiculo}). Un campo
 * ausente es lo honesto; inventar un cero no lo seria.
 *
 * <p>{@code afectoDesde}/{@code afectoHasta} son {@code Vehiculo#rangoDeAfectacion}: estructural,
 * los tres ejercicios desde la inscripcion, no el resultado de cruzar con un beneficio —una
 * exoneracion registrada no cambia estos campos—. Es el mismo par que el prototipo dibuja en la
 * columna «Afectación» como «2019 — 2021».
 *
 * <h2>{@code vehiculoId}, y por que hacia falta (#554)</h2>
 *
 * <p>{@code PeticionDeMovimiento} de {@code POST /rentas/deuda/&#123;altas,bajas&#125;} identifica
 * la unidad de la obligacion con {@code predioId} y {@code vehiculoId} —los dos <b>forman parte de
 * {@code ClaveDeSaldo}</b>, que compara por igualdad exacta: una obligacion con vehiculo y una sin
 * el son dos obligaciones distintas—. El predio se resolvia ({@code GET /catastro/predios} publica
 * {@code predioId}) y el vehiculo no: <b>esta fila</b>, que es la que la pantalla lee para
 * reconocer una placa, no publicaba ningun identificador interno.
 *
 * <p>Con lo que habia, un alta de patrimonio vehicular hecha desde ventanilla o se mandaba <b>sin
 * unidad</b> —y caia sobre una obligacion que no es la de la placa, invisible desde la ficha del
 * vehiculo y sin sumarse a lo que ya se le debe— o no se mandaba. La interfaz hacia lo segundo.
 *
 * <p>Se publica <b>aqui</b> y no se resuelve la placa en el cuerpo del movimiento: {@code
 * cuentacorriente} «no conoce a nadie» (ARQ-01 §4, regla 2) y no puede traducir una placa a un
 * identificador sin depender de {@code rentas}. Quien sabe esa correspondencia es esta lectura.
 *
 * <p>La ficha {@code GET /rentas/vehiculos/&#123;placa&#125;} ya lo publicaba —{@code
 * VehiculoResource.id}—, asi que el dato no era secreto: lo que faltaba era tenerlo <b>en la fila
 * que se lee</b>, sin una segunda peticion por placa reconocida.
 */
public record VehiculoEncontradoResource(
        long vehiculoId,
        String placa,
        @Nullable String clase,
        String marca,
        String modelo,
        int anioFabricacion,
        String estado,
        int afectoDesde,
        int afectoHasta,
        long contribuyenteId,
        String codigoContribuyente,
        String titular,
        ImporteActualizado deuda) {

    public static VehiculoEncontradoResource de(VehiculoConDeuda fila) {
        var vehiculo = fila.fila().vehiculo();
        var rango = vehiculo.rangoDeAfectacion();
        return new VehiculoEncontradoResource(
                java.util.Objects.requireNonNull(vehiculo.id(), "Un vehiculo leido tiene id"),
                vehiculo.placa().valor(),
                vehiculo.categoria(),
                vehiculo.marca(),
                vehiculo.modelo(),
                vehiculo.anioFabricacion().valor(),
                vehiculo.estado().name(),
                rango.desde().valor(),
                rango.hasta().valor(),
                vehiculo.contribuyenteId(),
                fila.fila().codigoContribuyente(),
                fila.fila().titular(),
                fila.deuda());
    }
}
