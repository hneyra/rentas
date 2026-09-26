package kamayuk.rentas.valores.dominio;

import java.util.Objects;

/**
 * El pase a coactiva que quedo en la base, y si lo inserto esta llamada (#444).
 *
 * <p>El pase es idempotente por indice: la segunda peticion recibe el movimiento de la primera.
 * Hasta #444 quien pasaba no sabia cual de las dos cosas habia pasado, y auditaba un ALTA cada vez:
 * la bitacora tenia dos altas del mismo movimiento, con dos autores y dos fechas.
 *
 * @param pase el movimiento, nuevo o el que ya estaba
 * @param nuevo si esta llamada lo inserto
 */
public record PaseRegistrado(MovimientoDeValor pase, boolean nuevo) {

    public PaseRegistrado {
        Objects.requireNonNull(pase, "El pase registrado es un movimiento concreto");
    }
}
