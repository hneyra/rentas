package kamayuk.rentas.nucleo.dominio;

import java.util.Objects;

/**
 * Una entrada del catalogo de marcas y modelos.
 *
 * <p>No hay tabla de catalogo: <b>la lista es la tabla de valores referenciales</b>. Es lo que la
 * hace mantenible sin escribir una pantalla de mantenimiento —quien carga los valores del ejercicio
 * carga con ellos las marcas y los modelos— y lo que impide que existan modelos sin valor, que en
 * una tabla aparte aparecen el primer año.
 */
public record MarcaYModelo(String marca, String modelo) {

    public MarcaYModelo {
        Objects.requireNonNull(marca, "La marca es obligatoria");
        Objects.requireNonNull(modelo, "El modelo es obligatorio");
    }
}
