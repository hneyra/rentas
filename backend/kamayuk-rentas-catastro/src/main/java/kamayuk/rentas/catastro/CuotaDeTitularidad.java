package kamayuk.rentas.catastro;

import kamayuk.rentas.dominio.Porcentaje;

/**
 * Cuanto le corresponde a un contribuyente en un predio, para quien orquesta una transferencia
 * desde otro contexto acotado.
 *
 * <p>Vive en el paquete raiz de {@code catastro}, que es su API publica (ARQ-01 §4, regla 1): no
 * expone {@code Titularidad} completa —ni su condicion, ni sus fechas de vigencia— porque quien
 * transfiere no necesita saberlas; solo cuanto hay para transferir y cual es la fila que hay que
 * cerrar.
 *
 * @param titularidadId la fila que hay que cerrar si se transfiere esta cuota
 * @param predioId el predio del que es titular
 * @param contribuyenteId el titular
 * @param porcentaje cuanto tiene, hoy
 */
public record CuotaDeTitularidad(
        long titularidadId, long predioId, long contribuyenteId, Porcentaje porcentaje) {}
