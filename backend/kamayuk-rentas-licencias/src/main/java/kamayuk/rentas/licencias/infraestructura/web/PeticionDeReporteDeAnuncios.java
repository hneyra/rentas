package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code anuncios_reportes} manda para emitir el padron (#51, RF-114).
 *
 * <p>«El padron de autorizaciones de anuncio y propaganda por contribuyente, direccion, estado o
 * intervalo de fechas», que es lo que el contrato describe.
 *
 * <p><b>{@code aLaFecha} no es un filtro: es la fecha de corte</b> (regla 9, RNF-075). El estado de
 * cada autorizacion y el total devengado dependen del dia al que se pregunte, asi que un padron
 * emitido hoy y otro emitido manana pueden diferir y los dos tienen que decir de cuando son.
 * Reimprimir el de la semana pasada es volver a pedirlo con su misma fecha.
 *
 * @param aLaFecha el dia de corte; si falta, el de hoy segun el reloj inyectado
 */
public record PeticionDeReporteDeAnuncios(
        @Nullable String contribuyente,
        @Nullable String direccion,
        @Nullable String claseAnuncio,
        @Nullable String desde,
        @Nullable String hasta,
        @Nullable String aLaFecha,
        @Nullable Integer pagina,
        @Nullable Integer tamano) {}
