package kamayuk.rentas.tesoreria.dominio;

import java.util.Optional;

/**
 * Las areas de la municipalidad a las que se imputa lo recaudado (V3, RF-133).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): la filtra la politica RLS con el valor que
 * {@code SET LOCAL} fijo al abrir la transaccion.
 *
 * <p>No hay {@code UPDATE} ni {@code DELETE}: un area que ya no cobra se da de baja con su columna
 * {@code activa}, y darla de baja es trabajo de la pantalla que todavia no existe. Lo que hace
 * falta hoy es poder <b>crearla</b>: sin area no hay caja con area, y sin caja la ventanilla de una
 * instalacion recien implantada no se puede abrir (#430).
 */
public interface AreaRepository {

    /** El area con ese codigo, si existe en esta municipalidad. */
    Optional<Area> porCodigo(String codigo);

    /** Da de alta el area y devuelve la fila guardada, con su identificador. */
    Area insertar(Area area);
}
