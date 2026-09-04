package kamayuk.rentas.catastro.dominio;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * El detalle de una ficha economica (RF-002): que se hace en la unidad y con que autorizaciones.
 *
 * <p>Es una <b>lista</b> y no una actividad: en un mismo local conviven varios giros, y el manual
 * los declara por separado porque cada uno tiene su CIIU y su autorizacion.
 *
 * @param actividades puede estar vacia: un local cerrado tambien se ficha
 * @param informacionComplementaria el campo libre de la pantalla del manual
 */
public record DetalleEconomico(
        List<ActividadEconomica> actividades, @Nullable String informacionComplementaria)
        implements DetalleDeLaFicha {

    private static final int COMPLEMENTARIA_MAXIMA = 400;

    public DetalleEconomico {
        Objects.requireNonNull(actividades, "La lista de actividades es vacia, no nula");
        actividades = List.copyOf(actividades);

        if (informacionComplementaria != null) {
            informacionComplementaria = informacionComplementaria.strip();
            if (informacionComplementaria.isEmpty()) {
                informacionComplementaria = null;
            } else if (informacionComplementaria.length() > COMPLEMENTARIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La informacion complementaria excede "
                                + COMPLEMENTARIA_MAXIMA
                                + " caracteres");
            }
        }
    }

    public static DetalleEconomico de(ActividadEconomica... actividades) {
        return new DetalleEconomico(List.of(actividades), null);
    }

    @Override
    public TipoFicha tipo() {
        return TipoFicha.ECONOMICA;
    }

    /** Las actividades que no declaran licencia. Es la pregunta que hace fiscalizacion. */
    public List<ActividadEconomica> sinLicencia() {
        return actividades.stream().filter(actividad -> !actividad.declaraLicencia()).toList();
    }
}
