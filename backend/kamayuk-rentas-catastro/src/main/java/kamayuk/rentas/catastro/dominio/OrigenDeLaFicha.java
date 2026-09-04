package kamayuk.rentas.catastro.dominio;

/**
 * De donde salio la version de la ficha.
 *
 * <p>No es estadistica: decide como se defiende. Una version con origen {@code FISCALIZACION} se
 * sustenta en un acta y admite discusion; una de {@code MIGRACION} viene del sistema anterior y su
 * respaldo es la conciliacion de la migracion, no un documento del contribuyente.
 */
public enum OrigenDeLaFicha {
    DECLARACION_JURADA,
    FISCALIZACION,
    RESOLUCION,
    MIGRACION
}
