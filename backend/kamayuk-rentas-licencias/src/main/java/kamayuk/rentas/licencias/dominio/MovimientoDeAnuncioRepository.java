package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Los movimientos de una autorizacion de anuncio (V45, #51).
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>: V45 le concede a {@code kamayuk_app} solo
 * {@code SELECT} e {@code INSERT}. Lo que le pasa a un anuncio se agrega.
 */
public interface MovimientoDeAnuncioRepository {

    /**
     * Registra el movimiento.
     *
     * <p><b>Se llama antes de pedirle el cargo a {@code cuentacorriente}</b>, y el orden es la
     * mitad del primer criterio de aceptacion de #51: si el cargo ya se pidio para ese anuncio y
     * ese ejercicio, este {@code INSERT} revienta contra {@code anuncio_movimiento_cargo_uq} y el
     * segundo asiento no llega a escribirse. Con el orden al reves, el libro tendria el cargo y la
     * transaccion se desharia entera —correcto, pero por accidente—.
     *
     * @throws CargoYaAsentado si ese anuncio ya devengo la tasa de ese ejercicio. Lo decide la
     *     base, no un {@code SELECT} previo: diez peticiones simultaneas pasan las diez por
     *     cualquier comprobacion escrita en Java
     * @throws ActoRepetido si el anuncio ya tenia su autorizacion, su cese o su retiro
     */
    MovimientoDeAnuncio registrar(MovimientoDeAnuncio movimiento);

    /** Todos los movimientos del anuncio, del primero al ultimo. */
    List<MovimientoDeAnuncio> deAnuncio(long anuncioId);

    /**
     * Los movimientos de varios anuncios de golpe, indexados por anuncio.
     *
     * <p>Existe para que una pagina de veinte anuncios derive sus veinte estados con <b>una</b>
     * consulta. Con {@link #deAnuncio} en un bucle serian veintiuna, y eso no se nota en la prueba
     * y si en el padron de una provincia.
     */
    Map<Long, List<MovimientoDeAnuncio>> deAnuncios(Set<Long> anuncioIds);

    /** Ese anuncio ya devengo la tasa de ese ejercicio: no se cobra dos veces. */
    final class CargoYaAsentado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CargoYaAsentado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** Ese acto ya estaba registrado: una autorizacion, un cese y un retiro por anuncio. */
    final class ActoRepetido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ActoRepetido(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
