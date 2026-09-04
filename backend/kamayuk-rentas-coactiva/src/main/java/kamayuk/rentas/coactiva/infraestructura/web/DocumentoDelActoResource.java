package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;

/**
 * El papel que salio con un acto coactivo (#41, RF-101, RF-132).
 *
 * <p><b>No lleva los bytes.</b> El contrato declara {@code application/json} para estas cuatro
 * opciones, y meter un PDF en base64 dentro de un JSON multiplica su tamanio por cuatro en una
 * emision que puede ser de todo un padron. Lo que viaja es <b>como pedirlo</b>: el numero y el
 * formato. La descarga es otra peticion.
 *
 * <p>{@code resumen} es el SHA-256 de los bytes de la <b>primera</b> emision. Va aqui a proposito:
 * es lo que convierte «la reimpresion devuelve el original» en algo que quien recibe el documento
 * puede comprobar por su cuenta, en vez de creerselo.
 *
 * @param numero el numero impreso del documento, que es tambien el del acto
 * @param formato en que formato salio esta vez, que no tiene por que ser el de la emision original:
 *     quien recibio un PDF tiene derecho a pedir la misma emision en hoja de calculo
 * @param nombreDeArchivo con que nombre se descarga
 * @param resumen el SHA-256 de la emision original
 * @param fechaDeEmision el dia en que se emitio por primera vez
 * @param reimpresiones cuantas veces se ha vuelto a sacar; el original es 0
 * @param bytes cuantos bytes ocupa lo que se genero ahora
 */
public record DocumentoDelActoResource(
        String numero,
        String formato,
        String nombreDeArchivo,
        String resumen,
        LocalDate fechaDeEmision,
        int reimpresiones,
        int bytes) {

    static DocumentoDelActoResource de(
            EmitirDocumento.Emision emision, FormatoDeDocumento solicitado) {
        return new DocumentoDelActoResource(
                emision.registro().numero(),
                solicitado.name(),
                solicitado.nombreDeArchivo(emision.registro().numero()),
                emision.registro().resumen(),
                emision.registro().fechaEmision(),
                emision.registro().reimpresiones(),
                emision.contenido().length);
    }
}
