package kamayuk.rentas.verificaciones.muestras.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.List;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.compartido.CifraTecleada;
import kamayuk.rentas.dominio.Dinero;

/**
 * Muestras para {@code ElImporteTecleadoSeLeeEnUnSitioTest} (#395, anotado en #629): un importador
 * de archivo —lo que lee filas con {@link LectorDeFilasCsv}— que lee un importe a mano, y el que lo
 * lee con la regla.
 *
 * <p>Viven en {@code src/test} y en un paquete {@code aplicacion}, como los importadores de la
 * siembra: la regla no las mira por su paquete sino porque leen un archivo.
 */
@SuppressWarnings("unused")
public final class MuestrasDeImporteImportadoAMano {

    private MuestrasDeImporteImportadoAMano() {}

    /** Lo que las filas traen: el importador lee el archivo, que es lo que lo hace importador. */
    static List<LectorDeFilasCsv.FilaCsv> filas(Reader archivo) throws IOException {
        return LectorDeFilasCsv.leer(archivo);
    }

    /** El parser de los importadores antes de #629: acepta cualquier escala. */
    static Dinero conBigDecimal(String texto) {
        return new Dinero(new BigDecimal(texto.strip()));
    }

    /** La que vale: la regla unica, que rechaza el tercer decimal y rechaza la fila. */
    static Dinero comoSeLee(String texto) {
        return new Dinero(CifraTecleada.leer(texto, "monto", "no es un importe"));
    }
}
