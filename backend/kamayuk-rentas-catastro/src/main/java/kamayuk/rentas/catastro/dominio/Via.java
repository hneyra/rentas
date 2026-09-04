package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una via del catalogo vial de la municipalidad.
 *
 * <p>Se eligio como primer objeto persistido del sistema justamente porque no arrastra reglas de
 * negocio: no tiene importes, ni vigencias, ni calculo. Lo que si tiene —y es lo que hay que
 * demostrar— es {@code municipalidad_id} y politica RLS, de modo que sirve para probar que el
 * patron de repositorio aisla de verdad y no solo en el papel.
 *
 * <p>{@code municipalidadId} <b>no</b> aparece aqui ni en el repositorio (regla 2): sale del token
 * y lo aplica la politica de la tabla.
 *
 * @param id nulo mientras la via no se ha guardado; lo asigna la base
 */
public record Via(
        @Nullable Long id,
        String codigo,
        TipoVia tipo,
        String nombre,
        @Nullable String ubigeo,
        boolean activa) {

    private static final int CODIGO_MAXIMO = 20;
    private static final int NOMBRE_MAXIMO = 160;
    private static final int UBIGEO = 6;

    public Via {
        Objects.requireNonNull(codigo, "La via necesita su codigo");
        Objects.requireNonNull(tipo, "La via necesita su tipo");
        Objects.requireNonNull(nombre, "La via necesita su nombre");
        codigo = codigo.strip();
        nombre = nombre.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de via va de 1 a "
                            + CODIGO_MAXIMO
                            + " caracteres: '"
                            + codigo
                            + "'");
        }
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de via va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
        if (ubigeo != null && ubigeo.length() != UBIGEO) {
            throw new IllegalArgumentException(
                    "El ubigeo son " + UBIGEO + " posiciones: '" + ubigeo + "'");
        }
    }

    /** Una via que todavia no esta en la base. */
    public static Via nueva(String codigo, TipoVia tipo, String nombre, @Nullable String ubigeo) {
        return new Via(null, codigo, tipo, nombre, ubigeo, true);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Dar de baja, nunca borrar (RNF-051): la via aparece en direcciones ya emitidas. */
    public Via dadaDeBaja() {
        return new Via(id, codigo, tipo, nombre, ubigeo, false);
    }
}
