package kamayuk.rentas.web;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Error previsto, con su codigo del catalogo.
 *
 * <p>Se lanza desde la capa de aplicacion cuando la operacion no se puede completar por una razon
 * que el usuario puede entender y corregir. Lo que <b>no</b> se hace nunca es dejar salir la
 * excepcion del motor de base de datos: eso lo traduce {@link ManejadorDeErrores} a {@link
 * CodigoDeError#ERROR_INTERNO}, sin detalle.
 *
 * <h2>El discriminador de lo que falta publicar (#604)</h2>
 *
 * <p>Un problema puede llevar ademas un {@link ParametroQueFalta}, y entonces el cuerpo sale con el
 * miembro {@code parametroQueFalta}. Es lo que separa por contrato «falta un campo de la peticion»
 * —que lo arregla quien atiende— de «falta una cifra normativa» —que no lo arregla nadie desde la
 * pantalla—, dos cosas que hasta #604 salian con el mismo {@code codigo} y el mismo {@code
 * mensaje}.
 *
 * <p>El mecanismo es <b>aditivo</b>: las dos formas de construir un problema que ya existian siguen
 * exactamente igual y siguen sin llevar el miembro. Un 422 que no lo lleve tiene que seguir siendo
 * indistinguible del de siempre, porque ponerlo en todos —o en ninguno— deshace la distincion que
 * este miembro existe para hacer.
 */
public class ProblemaDeNegocio extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final CodigoDeError codigo;
    private final List<String> detalles;
    private final @Nullable ParametroQueFalta parametroQueFalta;

    public ProblemaDeNegocio(CodigoDeError codigo, String mensaje) {
        this(codigo, mensaje, List.of());
    }

    public ProblemaDeNegocio(CodigoDeError codigo, String mensaje, List<String> detalles) {
        this(codigo, mensaje, detalles, null);
    }

    /**
     * El problema que existe porque una cifra normativa no esta publicada (#604).
     *
     * <p>Lo construye el controlador del modulo que caza la excepcion, que es quien sabe
     * traducirla: {@code kamayuk-rentas-plataforma} no depende de {@code kamayuk-rentas-parametros}
     * y no puede nombrar sus tipos.
     */
    public ProblemaDeNegocio(
            CodigoDeError codigo, String mensaje, ParametroQueFalta parametroQueFalta) {
        this(
                codigo,
                mensaje,
                List.of(),
                Objects.requireNonNull(
                        parametroQueFalta,
                        "Este constructor es el de «falta publicar»: sin el dato, usa otro"));
    }

    private ProblemaDeNegocio(
            CodigoDeError codigo,
            String mensaje,
            List<String> detalles,
            @Nullable ParametroQueFalta parametroQueFalta) {
        super(mensaje);
        this.codigo = Objects.requireNonNull(codigo, "Todo problema lleva su codigo del catalogo");
        this.detalles = List.copyOf(detalles);
        this.parametroQueFalta = parametroQueFalta;
    }

    public CodigoDeError codigo() {
        return codigo;
    }

    public List<String> detalles() {
        return detalles;
    }

    /**
     * La cifra normativa que hay que publicar, si el problema es ese. Vacio en cualquier otro caso
     * —incluido un campo ausente o un valor invalido—, que es lo que le da su significado.
     */
    public Optional<ParametroQueFalta> parametroQueFalta() {
        return Optional.ofNullable(parametroQueFalta);
    }
}
