package kamayuk.rentas.sanciones.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.ObligacionCompartida;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;

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

    /** Cómo empieza la referencia con que una papeleta marca su cargo en el libro. */
    private static final String PREFIJO = "PAPELETA-";

    private ObligacionDeLaPapeleta() {}

    /** El tributo del libro que corresponde a esa familia. */
    static String tributoDe(Familia familia) {
        return switch (Objects.requireNonNull(familia, "La papeleta necesita su familia")) {
            case TRANSITO -> TRIBUTO_TRANSITO;
            case ADMINISTRATIVA -> TRIBUTO_ADMINISTRATIVA;
        };
    }

    /**
     * La obligación del libro contra la que se asentó el cargo de esa papeleta.
     *
     * <p><b>No es sólo suya</b> (#371). La clave del libro no tiene sitio para la papeleta, así que
     * todas las multas del mismo obligado, tributo, ejercicio y unidad —o sin unidad— se suman en
     * la misma obligación. Quien la da de baja o la formaliza tiene que pasar además {@link
     * #referenciaDe} para que el libro compruebe que nadie más la originó; que la papeleta sea la
     * unidad de su obligación es #465.
     */
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
        return PREFIJO + papeleta.identificador();
    }

    /**
     * El rechazo del libro, dicho con el número impreso de cada otra papeleta (#371).
     *
     * <p>El libro nombra los otros orígenes por la referencia que {@link #referenciaDe} compuso;
     * aquí se vuelve de {@code PAPELETA-<id>} a la papeleta. Un origen que no es una papeleta de
     * esta municipalidad —un cargo sin referencia, un padrón migrado— se deja como el libro lo
     * dice: inventarle un número sería peor que no tenerlo.
     */
    static ObligacionCompartidaConOtraPapeleta compartida(
            Papeleta papeleta,
            ObligacionCompartida rechazo,
            PapeletaRepository papeletas,
            String acto) {
        List<String> otras = new ArrayList<>();
        for (String origen : rechazo.otrosOrigenes()) {
            otras.add(numeroDe(origen, papeletas));
        }
        return new ObligacionCompartidaConOtraPapeleta(papeleta.numero(), otras, acto);
    }

    private static String numeroDe(String origen, PapeletaRepository papeletas) {
        if (!origen.startsWith(PREFIJO)) {
            return origen;
        }
        try {
            return papeletas
                    .porId(Long.parseLong(origen.substring(PREFIJO.length())))
                    .map(otra -> "la papeleta " + otra.numero())
                    .orElse(origen);
        } catch (NumberFormatException noEsUnId) {
            return origen;
        }
    }
}
