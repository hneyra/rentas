/**
 * Impuesto al patrimonio vehicular: el cálculo sobre el valor referencial de un vehículo afecto
 * (#32, RF-025).
 *
 * <p>Sin Spring y sin JPA (regla 7). El plazo de afectación —tres ejercicios desde el año siguiente
 * a la inscripción— ya vive en {@link kamayuk.rentas.rentas.dominio.Vehiculo#afectoEn}; aquí solo
 * está la fórmula que aplica la alícuota sobre el valor referencial, sin ninguna cifra escrita
 * (regla 5).
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.rentas.dominio.vehicular;
