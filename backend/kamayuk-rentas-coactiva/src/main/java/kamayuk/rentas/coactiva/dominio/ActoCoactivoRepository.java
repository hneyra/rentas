package kamayuk.rentas.coactiva.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Los actos de un expediente coactivo (V34, #41).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): la filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V34 le retira a {@code
 * kamayuk_app} el privilegio de {@code UPDATE} sobre {@code acto_coactivo}, y V7 nunca le dio
 * {@code DELETE}. Un acto se deja sin efecto con otro acto.
 */
public interface ActoCoactivoRepository {

    /**
     * Registra el acto.
     *
     * @param acto {@link ActoCoactivo#esNuevo()} tiene que ser verdadero
     * @throws Rec1Duplicada si el expediente ya tenia su REC-1. <b>Lo decide la base</b> —{@code
     *     acto_rec1_uq}, V34— y no un {@code SELECT} previo: dos peticiones simultaneas pasan las
     *     dos por cualquier comprobacion escrita en Java, y el obligado acabaria con dos
     *     resoluciones de inicio del mismo procedimiento
     */
    ActoCoactivo registrar(ActoCoactivo acto);

    /** Todos los actos del expediente, del primero al ultimo. */
    List<ActoCoactivo> deExpediente(long expedienteId);

    /** La REC-1 del expediente, si ya se dicto. Es lo que la REC-2 necesita para sustentarse. */
    Optional<ActoCoactivo> rec1De(long expedienteId);

    /**
     * El ultimo acto de ese tipo dictado en el expediente, si hay alguno.
     *
     * <p>El ultimo y no «el» acto: de la REC-1 solo puede haber una ({@code acto_rec1_uq}), pero de
     * una medida cautelar puede haber varias —un embargo en retencion y despues otro en
     * inscripcion—, y reimprimir «la REC 2» del expediente significa la ultima dictada.
     */
    Optional<ActoCoactivo> ultimoDe(long expedienteId, TipoDeActoCoactivo tipo);

    /** Un acto por el numero de su documento, tal como sale impreso. */
    Optional<ActoCoactivo> porNumero(String numero);

    /** Ese expediente ya tenia su REC-1. */
    final class Rec1Duplicada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public Rec1Duplicada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
