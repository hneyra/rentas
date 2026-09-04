package kamayuk.rentas.seguridad.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Vigencia;
import org.jspecify.annotations.Nullable;

/**
 * La identidad local de una persona dentro de una municipalidad.
 *
 * <p><b>No guarda clave, y no puede guardarla</b> (ADR-0005): la autenticacion es del proveedor
 * OIDC. Lo que vive aqui es a quien corresponde el {@code sujeto_oidc} del token, que nombre
 * mostrar y —lo que de verdad importa— que puede hacer. Una clave aqui seria una segunda fuente de
 * verdad sobre quien es quien, que es la forma habitual de que una de las dos quede desactualizada.
 *
 * @param id nulo mientras no se ha guardado
 * @param cuenta identificador con el que entra; es lo que el guardia compara con el token
 * @param sujetoOidc identificador estable del proveedor, si ya se enlazo
 */
public record Usuario(
        @Nullable Long id,
        String cuenta,
        @Nullable String sujetoOidc,
        String nombre,
        @Nullable String correo,
        boolean habilitado,
        Vigencia vigencia) {

    private static final int CUENTA_MAXIMO = 60;
    private static final int NOMBRE_MAXIMO = 160;

    public Usuario {
        Objects.requireNonNull(cuenta, "El usuario necesita su cuenta");
        Objects.requireNonNull(nombre, "El usuario necesita su nombre");
        Objects.requireNonNull(vigencia, "El usuario necesita su vigencia; use Vigencia.SIEMPRE");
        cuenta = cuenta.strip();
        nombre = nombre.strip();
        if (cuenta.isEmpty() || cuenta.length() > CUENTA_MAXIMO) {
            throw new IllegalArgumentException(
                    "La cuenta va de 1 a " + CUENTA_MAXIMO + " caracteres: '" + cuenta + "'");
        }
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de usuario va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
    }

    public static Usuario nuevo(String cuenta, String nombre, @Nullable String correo) {
        return new Usuario(null, cuenta, null, nombre, correo, true, Vigencia.SIEMPRE);
    }

    public boolean autorizaEn(LocalDate fecha) {
        return habilitado && vigencia.vigenteEn(fecha);
    }

    public Usuario inhabilitado() {
        return new Usuario(id, cuenta, sujetoOidc, nombre, correo, false, vigencia);
    }

    public Usuario habilitadoDeNuevo() {
        return new Usuario(id, cuenta, sujetoOidc, nombre, correo, true, vigencia);
    }

    public Usuario con(Vigencia otra) {
        return new Usuario(id, cuenta, sujetoOidc, nombre, correo, habilitado, otra);
    }
}
