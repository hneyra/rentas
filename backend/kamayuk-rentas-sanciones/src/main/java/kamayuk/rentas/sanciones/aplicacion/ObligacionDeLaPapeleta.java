package kamayuk.rentas.sanciones.aplicacion;

import java.util.Objects;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;

/**
 * La obligación del libro que una papeleta origina (#46, #47, #50).
 *
 * <h2>Por qué existe, y por qué en un solo sitio</h2>
 *
 * <p>{@code RegistrarPapeleta} asienta el cargo de la multa contra una obligación concreta:
 * contribuyente obligado, tributo por familia, ejercicio de la fecha de la infracción y la unidad
 * —vehículo en tránsito, predio en administrativa—. Cuando un descargo se declara fundado hay que
 * dar de baja <b>esa misma</b> obligación, y componerla otra vez a mano en el caso de uso que
 * resuelve sería tener dos escrituras de la misma correspondencia. La primera que alguien tocara
 * dejaría la baja apuntando a una obligación que no es la que el cargo creó, y el síntoma sería una
 * papeleta anulada que sigue debiendo.
 *
 * <p>Es también lo que hace que la referencia externa se componga igual en los dos sitios: {@code
 * PAPELETA-<id>}, la clave <b>estable</b> de la fila, y no el número —que {@code
 * CambiarNumeroDePapeleta} puede corregir después—.
 */
final class ObligacionDeLaPapeleta {

    /** El tributo con el que se asienta una multa de tránsito. */
    static final String TRIBUTO_TRANSITO = TributoDelLibro.MULTA_TRANSITO.texto();

    /** El tributo con el que se asienta una multa administrativa. */
    static final String TRIBUTO_ADMINISTRATIVA = TributoDelLibro.MULTA_ADMINISTRATIVA.texto();

    private ObligacionDeLaPapeleta() {}

    /** El tributo del libro que corresponde a esa familia. */
    static String tributoDe(Familia familia) {
        return switch (Objects.requireNonNull(familia, "La papeleta necesita su familia")) {
            case TRANSITO -> TRIBUTO_TRANSITO;
            case ADMINISTRATIVA -> TRIBUTO_ADMINISTRATIVA;
        };
    }

    /** La obligación que el cargo de esa papeleta creó. */
    static SeleccionDeObligacion de(Papeleta papeleta) {
        return new SeleccionDeObligacion(
                tributoDe(papeleta.familia()),
                Ejercicio.de(papeleta.fechaInfraccion()),
                papeleta.familia() == Familia.ADMINISTRATIVA ? papeleta.predioId() : null,
                papeleta.familia() == Familia.TRANSITO ? papeleta.vehiculoId() : null);
    }

    /**
     * Cómo se marca en el libro lo que esa papeleta originó: por su identificador, no su número.
     */
    static String referenciaDe(Papeleta papeleta) {
        return "PAPELETA-" + papeleta.identificador();
    }
}
