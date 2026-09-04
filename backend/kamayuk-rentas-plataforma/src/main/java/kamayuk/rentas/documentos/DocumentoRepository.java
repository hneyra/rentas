package kamayuk.rentas.documentos;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Los documentos emitidos.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). <b>Ninguno borra</b>, y el unico que
 * actualiza toca una sola columna: cuantas veces se reimprimio. Un disparador de la base lo
 * sostiene, para que la invariante no dependa de que este repositorio siga escrito asi.
 */
public interface DocumentoRepository {

    Optional<DocumentoEmitido> porNumero(String tipo, Ejercicio ejercicio, String numero);

    /** Todo lo emitido sobre algo: los recibos de un contribuyente, los valores de un predio. */
    List<DocumentoEmitido> de(String tipo, String referencia);

    DocumentoEmitido insertar(DocumentoEmitido documento);

    /** Suma una reimpresion. No toca nada mas, y la base lo comprueba. */
    DocumentoEmitido registrarReimpresion(DocumentoEmitido documento);

    /**
     * El siguiente correlativo para ese tipo y ejercicio.
     *
     * <p>D-09 decide el formato del numero —con que ceros, si se reinicia—; lo que aqui se
     * garantiza es que no se repita, y lo garantiza la restriccion unica, no este metodo.
     */
    long siguienteCorrelativo(String tipo, Ejercicio ejercicio);
}
