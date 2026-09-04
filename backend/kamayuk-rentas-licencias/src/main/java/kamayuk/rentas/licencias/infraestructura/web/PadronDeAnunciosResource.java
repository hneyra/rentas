package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeAnuncios;
import kamayuk.rentas.web.ImporteActualizado;

/**
 * El padron de autorizaciones de anuncio, tal como sale de {@code anuncios_reportes} (#51, RF-114).
 *
 * <p><b>La fecha de corte no es opcional ni decorativa</b> (regla 9, RNF-075). El estado de cada
 * fila y el total devengado dependen del dia al que se pregunte, asi que el papel dice de cuando es
 * y reimprimirlo con la misma fecha da el mismo resultado. Sin ella, dos padrones del mismo filtro
 * emitidos con una semana de diferencia se contradicen y ninguno de los dos puede explicarse.
 *
 * <p>{@link #devengado} suma <b>todas</b> las autorizaciones del criterio, no solo las de esta
 * pagina: el motor lo calcula con un agregado. Sumar la pagina daria una cifra que parece un total
 * y no lo es, que es el defecto que #25 destapo en la consulta unificada.
 *
 * @param aLaFecha el dia de corte del padron
 * @param autorizaciones cuantas encuentra el criterio, en total
 * @param devengado lo que todas ellas han generado en tasas hasta esa fecha
 * @param pagina cuantas filas trae esta pagina, contada desde 0
 * @param tamano cuantas caben
 * @param filas las de esta pagina
 */
public record PadronDeAnunciosResource(
        LocalDate aLaFecha,
        long autorizaciones,
        ImporteActualizado devengado,
        int pagina,
        int tamano,
        List<AnuncioResource> filas) {

    public static PadronDeAnunciosResource de(ConsultaDeAnuncios.Padron padron) {
        return new PadronDeAnunciosResource(
                padron.aLaFecha(),
                padron.resumen().autorizaciones(),
                new ImporteActualizado(padron.resumen().devengado(), padron.aLaFecha()),
                padron.pagina().pagina(),
                padron.pagina().tamano(),
                padron.pagina().contenido().stream().map(AnuncioResource::de).toList());
    }
}
