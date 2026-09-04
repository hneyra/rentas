package kamayuk.rentas.coactiva.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code actos_coactivos} manda para dictar un acto (#41, RF-102).
 *
 * <p><b>Solo los campos que la opcion declara.</b> No hay aqui ni un importe ni un identificador de
 * contribuyente: el obligado y la deuda salen del expediente, y admitirlos por HTTP dejaria que la
 * peticion contradijera a la carpeta.
 *
 * @param tipo que acto se dicta: REC1, REC2, EMBARGO, MEDIDA_CAUTELAR, TASACION, REMATE,
 *     SUSPENSION, LEVANTAMIENTO, CONCLUSION u OTRO
 * @param fecha el dia del acto, en ISO; si falta, hoy
 * @param glosa la descripcion del acto
 * @param medida la forma de la medida cautelar; obligatoria en la REC-2, prohibida en los demas
 * @param formato en que formato sale el documento: PDF, XLS o RTF; si falta, PDF
 * @param observacion por que se dicta (regla 10, RNF-052). Sin ella no se guarda
 */
public record PeticionDeActoCoactivo(
        @Nullable String tipo,
        @Nullable String fecha,
        @Nullable String glosa,
        @Nullable String medida,
        @Nullable String formato,
        @Nullable String observacion) {}
