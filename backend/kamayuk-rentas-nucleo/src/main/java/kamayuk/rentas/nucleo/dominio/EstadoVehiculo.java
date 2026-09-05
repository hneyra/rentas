package kamayuk.rentas.nucleo.dominio;

/**
 * Situacion del vehiculo en el padron. Los cuatro valores son los del {@code CHECK} de la tabla.
 *
 * <p>Ninguno es «borrado»: un vehiculo no se quita del padron (RNF-051, regla 4). Aunque se
 * transfiera, se dé de baja o lo roben, sus papeletas y su deuda siguen existiendo y tienen que
 * seguir consultables.
 */
public enum EstadoVehiculo {
    ACTIVO,
    TRANSFERIDO,
    BAJA,
    ROBADO
}
