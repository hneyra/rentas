package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeDeteccion;
import kamayuk.rentas.fiscalizacion.dominio.DeteccionRepository;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;

/**
 * El cruce padron-declaraciones, de mentira (#545).
 *
 * <p>Existe para probar <b>lo que el sorteo de la muestra anade</b> —sus tres exclusiones— sin
 * volver a probar la deteccion: la deteccion se mide contra PostgreSQL, que es donde vive su
 * consulta, y aqui interesa que el sorteo reciba las filas que reciba.
 *
 * <p>Filtra por sector y condicion <b>antes</b> de paginar, como hace la consulta de verdad: un
 * doble que filtrara despues dejaria pasar el defecto que #545 cerro.
 */
public final class DeteccionDeMentira implements DeteccionRepository {

    private final List<FilaDeOmisos> filas = new ArrayList<>();

    public DeteccionDeMentira con(FilaDeOmisos fila) {
        filas.add(fila);
        return this;
    }

    @Override
    public Pagina<FilaDeOmisos> detectar(CriterioDeDeteccion criterio, Paginacion paginacion) {
        List<FilaDeOmisos> acotadas =
                filas.stream()
                        .filter(
                                fila ->
                                        criterio.sectorCodigo() == null
                                                || criterio.sectorCodigo()
                                                        .equals(fila.sectorCodigo()))
                        .filter(
                                fila ->
                                        criterio.condicion() == null
                                                || criterio.condicion() == fila.condicion())
                        .toList();

        int desde = Math.min(paginacion.desplazamiento(), acotadas.size());
        int hasta = Math.min(desde + paginacion.tamano(), acotadas.size());
        return new Pagina<>(
                acotadas.subList(desde, hasta),
                paginacion.pagina(),
                paginacion.tamano(),
                acotadas.size());
    }
}
