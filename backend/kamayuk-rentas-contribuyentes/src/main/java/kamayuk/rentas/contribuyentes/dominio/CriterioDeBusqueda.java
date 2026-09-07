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
 * carne, no se detiene a clasificarlo. Y con el tipo <b>puesto</b> se puede buscar cualquiera de
 * los seis de {@link TipoDocumento}, no solo el DNI y el RUC: hasta #35 el borde HTTP solo
 * publicaba esos dos, de modo que <b>no habia forma de saber si un extranjero ya estaba en el
 * padron</b> antes de darlo de alta por segunda vez. La restriccion nunca fue de este record.
 *
 * <p><b>El codigo es un PREFIJO y no una igualdad</b> (#35). Los codigos de un padron empiezan
 * todos por la misma retahila de ceros —{@code 00000000008}, {@code 00000000023}—, asi que buscar
 * por igualdad solo sirve a quien ya sabe el codigo entero, que es justo lo que quien busca no
 * tiene: medido contra Catacaos, {@code ?codigo=000000000} devolvia <b>0</b> de 10 603 y sin error
 * ninguno, o sea «ese contribuyente no existe». Se llama {@code codigoQueEmpiezaPor} y no {@code
 * codigo} para que el nombre diga lo que la consulta hace; quien lo lea en el repositorio no tiene
 * que ir a buscar el {@code WHERE} para saberlo.
 */
public record CriterioDeBusqueda(
        @Nullable String codigoQueEmpiezaPor,
        @Nullable String nombreAproximado,
        @Nullable TipoDocumento tipoDocumento,
        @Nullable String numeroDocumento,
        boolean soloActivos) {

    public CriterioDeBusqueda {
        codigoQueEmpiezaPor = limpiar(codigoQueEmpiezaPor);
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

    /** El codigo entero o sus primeras cifras: la comparacion es «empieza por» (#35). */
    public static CriterioDeBusqueda porCodigoQueEmpiezaPor(String codigo) {
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
                otro.codigoQueEmpiezaPor != null ? otro.codigoQueEmpiezaPor : codigoQueEmpiezaPor,
                otro.nombreAproximado != null ? otro.nombreAproximado : nombreAproximado,
                otro.tipoDocumento != null ? otro.tipoDocumento : tipoDocumento,
                otro.numeroDocumento != null ? otro.numeroDocumento : numeroDocumento,
                soloActivos || otro.soloActivos);
    }

    public CriterioDeBusqueda soloLosActivos() {
        return new CriterioDeBusqueda(
                codigoQueEmpiezaPor, nombreAproximado, tipoDocumento, numeroDocumento, true);
    }

    public Optional<String> nombre() {
        return Optional.ofNullable(nombreAproximado);
    }

    /** Si hay nombre, el orden natural es por parecido y no alfabetico. */
    public boolean ordenaPorParecido() {
        return nombreAproximado != null;
    }

    public boolean estaVacio() {
        return codigoQueEmpiezaPor == null
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
                + (codigoQueEmpiezaPor != null)
                + ", nombre="
                + (nombreAproximado != null)
                + ", documento="
                + (numeroDocumento != null)
                + ", soloActivos="
                + soloActivos
                + "]";
    }
}
