package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository.ResumenDeConciliacion;

/**
 * El recuento de la conciliacion, tal como sale por HTTP (#564). Campos en español {@code
 * camelCase} (ARQ-04 §3).
 *
 * <p><b>Los tres numeros y sus dos referencias</b>. El ejercicio va porque no existe «sin
 * conciliar», existe «sin conciliar a 2026» (regla 9, RNF-075): el padron afecto se rehace cada año
 * y la declaracion de 2024 no concilia 2026. Y {@code aLaFecha} porque la poblacion es la de las
 * fichas vigentes ese dia, igual que en la grilla.
 *
 * <p>{@code noConciliados} viaja calculado y no se deja para el cliente. Es una resta de dos cifras
 * que el servidor acaba de leer en la misma consulta; hacerla en la pantalla seria componer alli
 * una cifra (RNF-083), y ademas dejaria la puerta abierta a restarla contra el total de otra
 * lectura — que es exactamente el defecto que este endpoint viene a cerrar.
 */
public record ResumenDeConciliacionResource(
        int ejercicio, LocalDate aLaFecha, long total, long conciliados, long noConciliados) {

    public static ResumenDeConciliacionResource de(ResumenDeConciliacion resumen) {
        return new ResumenDeConciliacionResource(
                resumen.ejercicio().valor(),
                resumen.aLaFecha(),
                resumen.total(),
                resumen.conciliados(),
                resumen.noConciliados());
    }
}
