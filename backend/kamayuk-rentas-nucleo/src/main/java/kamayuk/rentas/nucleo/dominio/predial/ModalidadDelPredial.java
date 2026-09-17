package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Como se paga el impuesto predial de un ejercicio: el articulo 15 del TUO de la Ley de Tributacion
 * Municipal (D.S. 156-2004-EF) da dos formas y ninguna mas (#234).
 *
 * <p><b>Es un enumerado y no un texto libre, y eso cierra un hueco medido.</b> Hasta #234 la
 * modalidad viajaba como {@code String} desde el cuerpo de la peticion hasta {@code
 * CuadroPredialParametrizado.Vigente#vencimientos}, que compara contra {@code "CONTADO"} y trata
 * <b>cualquier otra cosa</b> como fraccionada: mandar {@code "MENSUAL"} devolvia las cuatro fechas
 * trimestrales con esa etiqueta encima, sin error de ninguna clase. Con la columna {@code
 * determinacion.modalidad} de V20 ese texto pasaria ademas a quedar <b>escrito</b>, y una fila que
 * dice «MENSUAL» y trae cuatro vencimientos trimestrales no la puede interpretar nadie dentro de
 * dos anios.
 *
 * <p><b>Aqui no se compila ninguna fecha</b> (regla 5): que dia vence cada cuota sale del conjunto
 * sellado, con las llaves {@code PREDIAL_VENCIMIENTO:‹clave›}. Lo unico que este enumerado decide
 * es <b>cuantas</b> cuotas hay y con que clave se piden, que es estructura de la norma y no una
 * cifra del ejercicio.
 */
public enum ModalidadDelPredial {

    /** Articulo 15 a): una sola cuota, hasta el ultimo dia habil de febrero. */
    CONTADO,

    /** Articulo 15 b): hasta en cuatro cuotas trimestrales. */
    TRIMESTRAL;

    /**
     * La modalidad que nombra ese texto, sin blancos y en mayusculas.
     *
     * @throws ModalidadDesconocida si no es ninguna de las dos; el mensaje las nombra
     * @throws NullPointerException si no viene ninguna: quien no la dice no la elige, y suponerla
     *     es lo que #234 existe para impedir
     */
    public static ModalidadDelPredial de(String texto) {
        Objects.requireNonNull(
                texto,
                "La modalidad de pago no se supone: sin ella el cronograma de una determinacion no"
                        + " se puede reproducir (#234)");
        String pedida = texto.strip().toUpperCase(Locale.ROOT);
        for (ModalidadDelPredial modalidad : values()) {
            if (modalidad.name().equals(pedida)) {
                return modalidad;
            }
        }
        throw new ModalidadDesconocida(pedida);
    }

    /** Las dos, separadas por comas, para decirlas en un mensaje de error. */
    public static String admitidas() {
        return Stream.of(values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** Ese texto no nombra ninguna de las dos formas del articulo 15. */
    public static final class ModalidadDesconocida extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ModalidadDesconocida(String pedida) {
            super(
                    "Modalidad de pago desconocida: '"
                            + pedida
                            + "'. Se admiten "
                            + admitidas()
                            + " (articulo 15 del TUO LTM). Antes de #234 cualquier otra palabra se"
                            + " trataba como fraccionada y devolvia las cuatro fechas trimestrales"
                            + " con esa etiqueta encima");
        }
    }
}
