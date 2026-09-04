package kamayuk.rentas.licencias.dominio;

import java.util.List;

/**
 * Los duplicados autorizados de una licencia (V37, #44).
 *
 * <p><b>Solo se agregan</b>: V37 le retira a {@code sgtm_app} el {@code UPDATE} sobre {@code
 * licencia_duplicado}, y {@code DELETE} nunca lo tuvo (V7).
 */
public interface DuplicadoDeLicenciaRepository {

    /**
     * Registra el duplicado.
     *
     * @throws DuplicadoDuplicado si ya hay uno con ese ordinal en esa licencia. Lo decide {@code
     *     licencia_duplicado_uq}, no la comprobacion previa: diez peticiones simultaneas pasan las
     *     diez por cualquier {@code if}, y el titular acabaria con dos papeles que dicen «DUPLICADO
     *     N.o 1»
     */
    DuplicadoDeLicencia registrar(DuplicadoDeLicencia duplicado);

    /** Cuantos duplicados lleva la licencia. Es lo que numera el siguiente. */
    int cuantosDe(long licenciaId);

    /** Los duplicados de la licencia, en orden. */
    List<DuplicadoDeLicencia> deLicencia(long licenciaId);

    /** Ese ordinal de duplicado ya existe en esa licencia. */
    final class DuplicadoDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public DuplicadoDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
