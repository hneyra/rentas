package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la opcion {@code licencia_resolucion_duplicado} manda (#44, RF-111).
 *
 * @param fecha el dia de la autorizacion; si no viene, el de hoy segun el reloj inyectado
 * @param motivo por que se pide: extravio, deterioro, robo
 * @param nDeRecibo el recibo del derecho de tramite del duplicado
 * @param formato en que formato salen la resolucion y la licencia reimpresa
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeDuplicado(
        @Nullable String fecha,
        @Nullable String motivo,
        @Nullable String nDeRecibo,
        @Nullable String formato,
        @Nullable String observacion) {}
