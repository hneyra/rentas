package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code licencia_padron} manda para emitir el padron (#54, RF-115).
 *
 * <p>Las claves son las de su seccion «Filtrado por»: {@code nLicenciaSerie}, {@code
 * nLicenciaNumero}, {@code estado}, {@code tipoLic}, {@code ciiu}, {@code direccion}, {@code
 * fecLicDesde}, {@code fecLicHasta}.
 *
 * <p><b>{@code aLaFecha} no es un filtro: es la fecha de corte</b> (regla 9, RNF-075, AC 1 de #54).
 * El estado de cada licencia depende del dia al que se pregunte —una temporal vence, una cancelada
 * lo esta desde la fecha de su resolucion—, asi que un padron emitido hoy y otro emitido manana
 * pueden diferir y los dos tienen que decir de cuando son. Reimprimir el de la semana pasada es
 * volver a pedirlo con su misma fecha, y por eso entra como dato y no como {@code LocalDate.now()}.
 *
 * <p>{@code nLicenciaSerie} y {@code nLicenciaNumero} son las dos mitades con que la pantalla
 * teclea un numero de licencia. Se unen aqui —{@code serie-numero}— porque {@code
 * licencia_funcionamiento} guarda el numero entero: partirlo en la base obligaria a decidir donde
 * esta la frontera, y eso es la plantilla de D-09, que sigue abierta.
 *
 * <p><b>El formato no viaja aqui</b>, sino como parametro de consulta {@code ?formato=PDF|XLS|RTF}.
 * Es el mismo reparto que {@code ReporteController} (#20) hace con la ficha del contribuyente: sin
 * el, la misma ruta devuelve el JSON que la pantalla pinta; con el, devuelve el documento (RF-132).
 * Tenerlo en los dos sitios dejaria dos formas de pedir lo mismo, y un dia dirian cosas distintas.
 *
 * @param aLaFecha el dia de corte; si falta, el de hoy segun el reloj inyectado
 */
public record PeticionDeReporteDeLicencias(
        @Nullable String nLicenciaSerie,
        @Nullable String nLicenciaNumero,
        @Nullable String estado,
        @Nullable String tipoLic,
        @Nullable String ciiu,
        @Nullable String direccion,
        @Nullable String nombreDelContribuyente,
        @Nullable String fecLicDesde,
        @Nullable String fecLicHasta,
        @Nullable String aLaFecha,
        @Nullable Integer pagina,
        @Nullable Integer tamano) {}
