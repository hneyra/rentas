package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que se manda para renovar, cesar o retirar una autorizacion de anuncio (#51, RF-114).
 *
 * <p>Un solo cuerpo para los tres actos, porque los tres piden lo mismo: <b>cuando</b> y <b>por
 * que</b>. Lo que cambia es cual de los dos campos opcionales usa cada uno —la renovacion lleva
 * {@code fecVenc} y no lleva motivo; el cese y el retiro llevan motivo y no mueven la vigencia—, y
 * eso lo comprueba el caso de uso, no el DTO: un record con los campos de los tres es honesto sobre
 * lo que el transporte admite, y las reglas viven donde se pueden probar sin HTTP.
 *
 * @param fecha el dia del acto; si falta, el de hoy segun el reloj inyectado
 * @param fecVenc hasta cuando queda prorrogada; solo en la renovacion
 * @param motivo por que se cesa o se retira; obligatorio en esos dos
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeActoDeAnuncio(
        @Nullable String fecha,
        @Nullable String fecVenc,
        @Nullable String motivo,
        @Nullable String observacion) {}
