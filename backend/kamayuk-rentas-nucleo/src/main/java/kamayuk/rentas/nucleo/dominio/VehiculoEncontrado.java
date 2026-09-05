package kamayuk.rentas.nucleo.dominio;

import java.util.Objects;

/**
 * Una fila de la consulta de vehiculos (RF-024, #25): el vehiculo con el nombre de su titular, ya
 * resuelto.
 *
 * <p>El nombre no vive en {@link Vehiculo} —esa es la ficha, y solo guarda el identificador del
 * contribuyente—, y esta consulta lo trae con un {@code JOIN} a {@code contribuyente} desde el SQL
 * de {@code rentas}: no es una dependencia de Java hacia ese contexto, asi que Spring Modulith no
 * la ve como tal (mismo patron que {@code BeneficioRepositoryJdbc#buscar}).
 *
 * @param vehiculo la ficha
 * @param titular el nombre o razon social del contribuyente propietario
 * @param codigoContribuyente su codigo unico del padron
 */
public record VehiculoEncontrado(Vehiculo vehiculo, String titular, String codigoContribuyente) {

    public VehiculoEncontrado {
        Objects.requireNonNull(vehiculo, "La fila necesita el vehiculo");
        Objects.requireNonNull(titular, "La fila necesita el nombre del titular");
        Objects.requireNonNull(codigoContribuyente, "La fila necesita el codigo del titular");
    }
}
