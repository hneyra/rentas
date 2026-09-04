/**
 * La simulacion del acogimiento a una campana de beneficio (#72, RF-107).
 *
 * <p>Sin Spring y sin JPA (regla 7), y sin ninguna cifra dentro (regla 5): la campana —su nombre,
 * el porcentaje que condona, sobre que parte de la deuda se aplica y con que redondeo— llega como
 * <b>argumento</b>, resuelta del conjunto de parametros sellado por {@code
 * rentas.aplicacion.CampaniasDeBeneficioParametrizadas}. Los mismos argumentos dan el mismo centimo
 * hoy y en 2037 (regla 6).
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.rentas.dominio.beneficios;
