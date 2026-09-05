package kamayuk.rentas.nucleo.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Un valor referencial del MEF: cuanto vale un modelo de un año, en un conjunto sellado.
 *
 * <p><b>Aqui no hay ninguna cifra escrita</b>, y no puede haberla (regla 5, D-02): esto es la forma
 * del dato, no el dato. Los valores los carga el rol {@code rol_carga_parametros} desde la tabla
 * publicada, y se sellan; el codigo solo sabe leerlos.
 *
 * <p>El {@code ejercicio} viaja junto al conjunto y no en su lugar: el conjunto es quien manda —un
 * ejercicio puede tener varias versiones selladas— y el ejercicio esta para poder leer la tabla sin
 * volver a consultar el conjunto.
 */
public record ValorReferencial(
        Ejercicio ejercicio,
        String marca,
        String modelo,
        Ejercicio anioFabricacion,
        Dinero valor,
        String documentoFuente) {

    public ValorReferencial {
        Objects.requireNonNull(ejercicio, "El valor referencial es de un ejercicio");
        Objects.requireNonNull(marca, "El valor referencial es de una marca");
        Objects.requireNonNull(modelo, "El valor referencial es de un modelo");
        Objects.requireNonNull(anioFabricacion, "El valor referencial es de un anio");
        Objects.requireNonNull(valor, "El valor referencial tiene un importe");
        Objects.requireNonNull(
                documentoFuente,
                "Todo valor normativo dice de que documento salio: sin fuente no se carga"
                        + " (ADR-0007)");
        if (documentoFuente.isBlank()) {
            throw new IllegalArgumentException("El documento fuente no puede estar en blanco");
        }
    }
}
