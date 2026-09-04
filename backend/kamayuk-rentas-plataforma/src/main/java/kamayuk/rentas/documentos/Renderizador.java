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

    FormatoDeDocumento formato();

    void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException;
}
