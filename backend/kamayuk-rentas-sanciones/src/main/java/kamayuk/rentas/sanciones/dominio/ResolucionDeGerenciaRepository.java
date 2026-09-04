package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las resoluciones de gerencia contra PostgreSQL. Ningún método recibe la municipalidad (regla 2).
 *
 * <p><b>Solo inserta.</b> V41 no le concede a {@code sgtm_app} ni {@code UPDATE} ni {@code DELETE}
 * sobre {@code resolucion_gerencia}, por lo mismo que V34 se los negó a {@code acto_coactivo}: la
 * resolución se notifica al administrado, que se lleva el papel. Una equivocada se deja sin efecto
 * con otra, y las dos quedan.
 */
public interface ResolucionDeGerenciaRepository {

    /**
     * Inserta la resolución.
     *
     * @throws ResolucionDuplicada si la papeleta ya tiene una resolución de ese tipo, o si el
     *     descargo ya está resuelto. La garantía son los índices únicos parciales de V41, no un
     *     {@code if}: dos peticiones simultáneas pasan las dos por cualquier comprobación en Java
     */
    ResolucionDeGerencia registrar(ResolucionDeGerencia resolucion);

    Optional<ResolucionDeGerencia> porNumero(String numero);

    Optional<ResolucionDeGerencia> porId(long id);

    /** La resolución de ese tipo dictada sobre la papeleta, si la hay. */
    Optional<ResolucionDeGerencia> dePapeleta(long papeletaId, TipoDeResolucionDeGerencia tipo);

    /** Todas las resoluciones de una papeleta, de la más antigua a la más reciente. */
    List<ResolucionDeGerencia> dePapeleta(long papeletaId);

    /** La resolución que resolvió ese descargo, si ya se dictó. */
    Optional<ResolucionDeGerencia> queResuelve(long descargoId);

    /** La papeleta ya tiene una resolución de ese tipo, o el descargo ya está resuelto. */
    final class ResolucionDuplicada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ResolucionDuplicada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
