package kamayuk.rentas.seguridad.dominio;

/**
 * A quien se le dice que un evento de {@code identidad} se aparto sin aplicar (ADR-0026 §4).
 *
 * <p>Mientras haya un evento apartado, alguien tiene en {@code identidad} un permiso, una cuenta o
 * una afiliacion que en esta copia no rige, y ninguna cifra lo delata: el guardia sigue contestando
 * con lo que la copia dice. Por eso se avisa a una persona con nombre, y no solo al registro.
 */
public interface AlertaDeEventosSinAplicar {

    void hayUnEventoSinAplicar(EventoDeIdentidadRecibido evento, String motivo, long apartados);
}
