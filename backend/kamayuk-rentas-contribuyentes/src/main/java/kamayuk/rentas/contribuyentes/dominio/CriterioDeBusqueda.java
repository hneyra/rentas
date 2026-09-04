package kamayuk.rentas.contribuyentes.dominio;

import java.util.Optional;
import kamayuk.rentas.dominio.TipoDocumento;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se busca en el padron. Todos los criterios son opcionales y se combinan con Y.
 *
 * <p>El del nombre no es igualdad ni {@code LIKE}: es <b>aproximacion</b>. En ventanilla el nombre
 * llega sin tildes, con la enie cambiada, con los apellidos invertidos o con una letra de menos, y
 * una busqueda exacta devuelve cero filas. Cuando eso pasa, el cajero da de alta al mismo
 * contribuyente por segunda vez, que es como se duplican los padrones (RF-014).
 *
 * <p>El documento se puede buscar <b>sin el tipo</b>: quien atiende teclea el numero que trae el
 * carne, no se detiene a clasificarlo.
 */
public record CriterioDeBusqueda(
        @Nullable String codigo,
        @Nullable String nombreAproximado,
        @Nullable TipoDocumento tipoDocumento,
        @Nullable String numeroDocumento,
        boolean soloActivos) {

    public CriterioDeBusqueda {
        codigo = limpiar(codigo);
        nombreAproximado = limpiar(nombreAproximado);
        numeroDocumento = limpiar(numeroDocumento);
        if (tipoDocumento != null && numeroDocumento == null) {
            throw new IllegalArgumentException(
                    "Buscar por tipo de documento sin numero devolveria el padron entero de ese"
                            + " tipo; si es lo que se quiere, se pide sin criterios");
        }
    }

    /** Sin ningun filtro: el padron completo, paginado. */
    public static CriterioDeBusqueda todos() {
        return new CriterioDeBusqueda(null, null, null, null, false);
    }

    public static CriterioDeBusqueda porNombre(String aproximado) {
        return new CriterioDeBusqueda(null, aproximado, null, null, false);
    }

    public static CriterioDeBusqueda porCodigo(String codigo) {
        return new CriterioDeBusqueda(codigo, null, null, null, false);
    }

    public static CriterioDeBusqueda porDocumento(TipoDocumento tipo, String numero) {
        return new CriterioDeBusqueda(null, null, tipo, numero, false);
    }

    /** El numero que trae el carne, sin clasificarlo. */
    public static CriterioDeBusqueda porNumeroDeDocumento(String numero) {
        return new CriterioDeBusqueda(null, null, null, numero, false);
    }

    public CriterioDeBusqueda y(CriterioDeBusqueda otro) {
        return new CriterioDeBusqueda(
                otro.codigo != null ? otro.codigo : codigo,
                otro.nombreAproximado != null ? otro.nombreAproximado : nombreAproximado,
                otro.tipoDocumento != null ? otro.tipoDocumento : tipoDocumento,
                otro.numeroDocumento != null ? otro.numeroDocumento : numeroDocumento,
                soloActivos || otro.soloActivos);
    }

    public CriterioDeBusqueda soloLosActivos() {
        return new CriterioDeBusqueda(
                codigo, nombreAproximado, tipoDocumento, numeroDocumento, true);
    }

    public Optional<String> nombre() {
        return Optional.ofNullable(nombreAproximado);
    }

    /** Si hay nombre, el orden natural es por parecido y no alfabetico. */
    public boolean ordenaPorParecido() {
        return nombreAproximado != null;
    }

    public boolean estaVacio() {
        return codigo == null
                && nombreAproximado == null
                && numeroDocumento == null
                && !soloActivos;
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    @Override
    public String toString() {
        // Sin el numero de documento ni el nombre: esto acaba en un log, y ahi no van
        // datos identificatorios de una persona.
        return "CriterioDeBusqueda[codigo="
                + (codigo != null)
                + ", nombre="
                + (nombreAproximado != null)
                + ", documento="
                + (numeroDocumento != null)
                + ", soloActivos="
                + soloActivos
                + "]";
    }
}
