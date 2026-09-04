package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;

/**
 * El padron de licencias de funcionamiento, tal como sale de {@code licencia_padron} (#54, RF-115).
 *
 * <p><b>La fecha de corte no es opcional ni decorativa</b> (regla 9, RNF-075). El estado de cada
 * fila depende del dia al que se pregunte, asi que el papel dice de cuando es y reimprimirlo con la
 * misma fecha da el mismo resultado. Sin ella, dos padrones del mismo filtro emitidos con una
 * semana de diferencia se contradicen y ninguno de los dos puede explicarse.
 *
 * <p>Los cuatro recuentos cubren <b>todas</b> las licencias del criterio, no solo las de esta
 * pagina: el motor los calcula con un agregado. Contar la pagina daria una cifra que parece un
 * total y no lo es, que es el defecto que #25 destapo en la consulta unificada y que #51 volvio a
 * cazar en el padron de anuncios.
 *
 * <p>Ninguna cifra de dinero: una licencia no lleva importes. Lo recaudado por sus derechos de
 * tramite es el resumen anual, que es otro reporte y otra pregunta.
 *
 * @param aLaFecha el dia de corte del padron
 * @param licencias cuantas encuentra el criterio, en total
 * @param vigentes cuantas de ellas lo estaban a esa fecha
 * @param vencidas cuantas habian pasado su plazo
 * @param canceladas cuantas tenian resolucion de cancelacion
 * @param pagina cuantas filas trae esta pagina, contada desde 0
 * @param tamano cuantas caben
 * @param filas las de esta pagina
 */
public record PadronDeLicenciasResource(
        LocalDate aLaFecha,
        long licencias,
        long vigentes,
        long vencidas,
        long canceladas,
        int pagina,
        int tamano,
        List<LicenciaResource> filas) {

    public static PadronDeLicenciasResource de(ConsultaDeLicencias.Padron padron) {
        return new PadronDeLicenciasResource(
                padron.aLaFecha(),
                padron.resumen().licencias(),
                padron.resumen().vigentes(),
                padron.resumen().vencidas(),
                padron.resumen().canceladas(),
                padron.pagina().pagina(),
                padron.pagina().tamano(),
                padron.pagina().contenido().stream().map(LicenciaResource::de).toList());
    }
}
