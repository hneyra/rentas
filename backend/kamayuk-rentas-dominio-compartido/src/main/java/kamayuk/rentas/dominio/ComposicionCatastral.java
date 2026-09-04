package kamayuk.rentas.dominio;

import java.util.List;
import java.util.Objects;

/**
 * Como se reparte en tramos un {@link CodigoReferenciaCatastral}.
 *
 * <p>Es un parametro y no una constante escrita dentro del codigo porque <b>D-10 sigue abierta</b>:
 * la plantilla de letras del manual da 23 posiciones y los ejemplos del prototipo de interfaz traen
 * 21, y hasta contrastarlo con fichas reales no hay forma de saber cual rige. El esquema tomo la
 * misma precaucion —{@code cod_catastral varchar(25) CHECK (VALUE ~ '^[0-9]{18,25}$')}— en lugar de
 * fijar un numero inventado que obligaria a migrar la columna despues.
 *
 * <p>Con la composicion afuera, cerrar D-10 es cambiar una lista de tramos; con la composicion
 * adentro, seria cambiar la validacion, los accesores y las pruebas de todo lo que la use.
 *
 * @param tramos los tramos en el orden en que aparecen en el codigo
 */
public record ComposicionCatastral(List<Tramo> tramos) {

    /**
     * La plantilla {@code DDPPddSSMMMLLLEEeeppUUU} del manual (cap. 2, §Registro de Predios) y de
     * RF-005: 23 posiciones.
     *
     * <p>TODO D-10: verificar contra fichas reales si son 23 o 21 posiciones. Mientras la decision
     * siga abierta, esta es la lectura literal del manual, y la unica alternativa —elegir 21 porque
     * lo dice un prototipo— tampoco esta verificada.
     */
    public static final ComposicionCatastral DEL_MANUAL =
            new ComposicionCatastral(
                    List.of(
                            new Tramo("departamento", 2),
                            new Tramo("provincia", 2),
                            new Tramo("distrito", 2),
                            new Tramo("sector", 2),
                            new Tramo("manzana", 3),
                            new Tramo("lote", 3),
                            new Tramo("edificacion", 2),
                            new Tramo("entrada", 2),
                            new Tramo("piso", 2),
                            new Tramo("unidad", 3)));

    public ComposicionCatastral {
        Objects.requireNonNull(tramos, "Una composicion catastral necesita sus tramos");
        if (tramos.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una composicion catastral sin tramos no valida nada");
        }
        tramos = List.copyOf(tramos);
    }

    /** Un tramo del codigo: que representa y cuantos digitos ocupa. */
    public record Tramo(String nombre, int longitud) {
        public Tramo {
            Objects.requireNonNull(nombre, "Un tramo necesita nombre");
            if (nombre.isBlank()) {
                throw new IllegalArgumentException("Un tramo necesita nombre");
            }
            if (longitud <= 0) {
                throw new IllegalArgumentException(
                        "El tramo '" + nombre + "' debe ocupar al menos un digito");
            }
        }
    }

    /** Posiciones que ocupa el codigo completo. */
    public int longitud() {
        return tramos.stream().mapToInt(Tramo::longitud).sum();
    }

    /** Posicion en la que empieza un tramo, o -1 si la composicion no lo tiene. */
    int inicioDe(String nombre) {
        int inicio = 0;
        for (Tramo tramo : tramos) {
            if (tramo.nombre().equals(nombre)) {
                return inicio;
            }
            inicio += tramo.longitud();
        }
        return -1;
    }
}
