package kamayuk.rentas.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * Documento con el que una persona se identifica ante el Estado: el tipo y su numero, juntos.
 *
 * <p>Van juntos porque por separado no se pueden validar: {@code "12345678"} es un DNI correcto y
 * un RUC imposible, y el numero suelto no permite distinguirlo. La composicion que exige cada tipo
 * esta en {@link TipoDocumento}.
 *
 * <p>Lo que aqui <b>no</b> se valida es el digito verificador del RUC ni el del DNI. No por
 * descuido: el algoritmo es de SUNAT y de RENIEC, cambia con ellos, y meterlo en un objeto de valor
 * del dominio significa que el dia que cambie hay que recompilar el sistema para poder registrar a
 * un contribuyente. Lo que se exige aqui es la forma, que es lo que la columna tambien exige.
 *
 * <p><b>No es el {@link CodigoContribuyente}.</b> Este identifica a la persona ante el Estado;
 * aquel, ante esta municipalidad.
 */
public record DocumentoIdentidad(TipoDocumento tipo, String numero) {

    public DocumentoIdentidad {
        Objects.requireNonNull(tipo, "El tipo de documento es obligatorio");
        Objects.requireNonNull(numero, "El numero de documento es obligatorio");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.length() < tipo.longitudMinima() || numero.length() > tipo.longitudMaxima()) {
            throw new IllegalArgumentException(
                    "Un "
                            + tipo
                            + " tiene de "
                            + tipo.longitudMinima()
                            + " a "
                            + tipo.longitudMaxima()
                            + " caracteres; se recibieron "
                            + numero.length()
                            + ": '"
                            + numero
                            + "'");
        }
        if (tipo.soloDigitos() && !esSoloDigitos(numero)) {
            throw new IllegalArgumentException("Un " + tipo + " es solo digitos: '" + numero + "'");
        }
        if (numero.isBlank()) {
            throw new IllegalArgumentException("El numero de documento no puede estar en blanco");
        }
    }

    public static DocumentoIdentidad dni(String numero) {
        return new DocumentoIdentidad(TipoDocumento.DNI, numero);
    }

    public static DocumentoIdentidad ruc(String numero) {
        return new DocumentoIdentidad(TipoDocumento.RUC, numero);
    }

    private static boolean esSoloDigitos(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) < '0' || texto.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return tipo + " " + numero;
    }
}
