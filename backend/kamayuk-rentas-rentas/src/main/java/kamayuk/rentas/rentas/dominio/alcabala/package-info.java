/**
 * Impuesto de alcabala: la elección de base entre el valor de transferencia y el autovalúo
 * ajustado, y el cálculo sobre el exceso del tramo inafecto (#32, RF-026).
 *
 * <p>Sin Spring y sin JPA (regla 7). Grava únicamente la transferencia de un predio (TUO Ley de
 * Tributación Municipal, D.S. 156-2004-EF, arts. 21 a 29); un vehículo nunca paga alcabala.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.rentas.dominio.alcabala;
