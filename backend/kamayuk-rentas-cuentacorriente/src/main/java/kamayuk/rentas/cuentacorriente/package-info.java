/**
 * El libro de asientos: cargos y abonos por contribuyente, tributo, periodo y unidad; altas (nota
 * de abono) y bajas (nota de cargo); saldo proyectado como cache reconstruible (ADR-0006).
 *
 * <p><b>Invariante:</b> inmutable. Sin UPDATE y sin DELETE; se reversa con otro asiento.
 *
 * <p>No conoce a nadie: recibe asientos y no sabe si vienen de un predial, de una papeleta o de una
 * licencia. Si tuviera que saberlo, el modelo estaria mal.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.cuentacorriente;
