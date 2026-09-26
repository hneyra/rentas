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
     * El siguiente correlativo para ese tipo y ejercicio, <b>reservado</b> hasta el fin de la
     * transaccion.
     *
     * <p>Lo que se garantiza desde #427: dos emisiones simultaneas del mismo tipo y ejercicio salen
     * con numeros <b>distintos y consecutivos</b>, y ninguna choca. La segunda <b>espera</b> a que
     * la primera confirme o se deshaga —el candado de la fila contador dura hasta el {@code
     * COMMIT}, renderizado incluido—; es lo que exige un numero correlativo. Si la transaccion se
     * deshace, el numero vuelve con ella y no queda hueco. La primera emision de cada tipo y
     * ejercicio arranca por encima del mayor numero ya emitido. {@code documento_numero_uq} sigue
     * ahi, pero ya no decide: si alguna vez salta, es un defecto y no una carrera.
     *
     * <p>D-09 decide el formato del numero —con que ceros, si se reinicia—; este metodo reparte el
     * entero.
     */
    long siguienteCorrelativo(String tipo, Ejercicio ejercicio);
}
