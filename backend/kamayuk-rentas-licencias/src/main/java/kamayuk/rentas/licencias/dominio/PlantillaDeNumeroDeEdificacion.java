package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero impreso de una licencia de <b>edificacion</b>.
 *
 * <p>Envuelve a {@link PlantillaDeNumeroDeLicencia} en vez de repetir su compositor. Dos motivos, y
 * los dos importan:
 *
 * <ul>
 *   <li><b>La logica es la misma y se escribe una vez.</b> Las marcas, el relleno de ceros y el
 *       tope de veinte caracteres de la columna ya estan resueltos ahi.
 *   <li><b>El tipo tiene que ser distinto para que el bean lo sea.</b> Si las dos plantillas fueran
 *       del mismo tipo, Spring tendria dos candidatos para inyectar y elegiria mal o no elegiria; y
 *       la marcha blanca de #44 ya enseno lo que cuesta un bean de plantilla que falta —la
 *       aplicacion real no arranca y las pruebas no lo notan, porque instancian el caso de uso a
 *       mano—.
 * </ul>
 *
 * @param formato la plantilla con sus marcas, {@code {ejercicio}} y {@code {correlativo[:N]}}
 */
public record PlantillaDeNumeroDeEdificacion(PlantillaDeNumeroDeLicencia formato) {

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code LE-2026-000001}.
     *
     * <p>TODO D-09: contrastar con las licencias de obra reales de la municipalidad piloto. Se
     * elige la misma forma que ya usan el valor, el convenio, el expediente y la licencia de
     * funcionamiento; un quinto formato distinto en el mismo sistema seria una decision, y aqui no
     * hay ninguna que tomar todavia. Se distingue de la de funcionamiento en el prefijo —{@code LE}
     * frente a {@code LF}— porque las dos numeraciones son independientes y compartir prefijo haria
     * que dos papeles distintos se llamaran igual.
     */
    public static final PlantillaDeNumeroDeEdificacion POR_OMISION =
            new PlantillaDeNumeroDeEdificacion(
                    new PlantillaDeNumeroDeLicencia("LE-{ejercicio}-{correlativo:6}"));

    public PlantillaDeNumeroDeEdificacion {
        Objects.requireNonNull(formato, "La plantilla del numero es obligatoria (D-09)");
    }

    /** El numero impreso de ese correlativo en ese ejercicio. */
    public String componer(Ejercicio ejercicio, long correlativo) {
        return formato.componer(ejercicio, correlativo);
    }
}
