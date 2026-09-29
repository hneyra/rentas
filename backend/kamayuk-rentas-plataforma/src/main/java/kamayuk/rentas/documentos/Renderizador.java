package kamayuk.rentas.documentos;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Convierte un {@link ModeloDeDocumento} en bytes de un formato concreto.
 *
 * <p><b>Escribe en un flujo, no devuelve un arreglo.</b> Es lo que permite generar miles de
 * documentos sin agotar la memoria: cada uno se escribe y se olvida. Un {@code byte[] generar(...)}
 * obligaria a tener el documento entero en memoria, y una emision masiva a tenerlos todos.
 *
 * <p>Ninguna implementacion usa una biblioteca externa, y es deliberado: ver {@link
 * GeneradorDeDocumentos}.
 */
public interface Renderizador {

    /**
     * El formato que produce. {@link GeneradorDeDocumentos} no arranca si dos renderizadores dicen
     * el mismo, ni si algun formato se queda sin ninguno.
     */
    FormatoDeDocumento formato();

    /**
     * Escribe el documento en {@code salida}, sin cerrarla: el flujo es de quien lo abrio.
     *
     * @throws IOException si la salida falla a mitad; lo escrito hasta entonces no es un documento
     */
    void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException;
}
