package kamayuk.rentas.tesoreria.dobles;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CajaEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;

/**
 * Las cajas, en memoria.
 *
 * <p>El area <b>no</b> se modela: {@link Caja} guarda su identificador y este doble no tiene tabla
 * de areas que consultar, asi que {@link #listar} devuelve las dos columnas del area nulas. Es
 * deliberado y esta escrito porque es justo lo que un doble no puede demostrar: que el catalogo
 * resuelve el area legible lo mide {@code CatalogoDeCajasFronteraTest} contra PostgreSQL, que es
 * donde vive el {@code LEFT JOIN}.
 */
public final class CajasEnMemoria implements CajaRepository {

    private final Map<String, Caja> porCodigo = new LinkedHashMap<>();

    public CajasEnMemoria con(Caja caja) {
        porCodigo.put(caja.codigo().toUpperCase(Locale.ROOT), caja);
        return this;
    }

    @Override
    public Pagina<CajaEnConsulta> listar(Paginacion paginacion) {
        List<CajaEnConsulta> todas =
                porCodigo.values().stream()
                        .sorted(Comparator.comparing(Caja::codigo))
                        .map(
                                caja ->
                                        new CajaEnConsulta(
                                                caja.codigo(),
                                                caja.nombre(),
                                                null,
                                                null,
                                                caja.activa()))
                        .toList();
        int desde = Math.min(paginacion.desplazamiento(), todas.size());
        int hasta = Math.min(desde + paginacion.tamano(), todas.size());
        return Pagina.de(todas.subList(desde, hasta), paginacion, todas.size());
    }

    @Override
    public Optional<Caja> porCodigo(String codigo) {
        return Optional.ofNullable(porCodigo.get(codigo.strip().toUpperCase(Locale.ROOT)));
    }

    @Override
    public Optional<Caja> porId(long id) {
        return porCodigo.values().stream()
                .filter(caja -> caja.id() != null && caja.id() == id)
                .findFirst();
    }

    @Override
    public Caja insertar(Caja caja) {
        String clave = caja.codigo().toUpperCase(Locale.ROOT);
        if (porCodigo.containsKey(clave)) {
            throw new IllegalStateException("Ya hay una caja con el codigo '" + clave + "'");
        }
        Caja guardada =
                new Caja(
                        (long) (porCodigo.size() + 1),
                        caja.codigo(),
                        caja.nombre(),
                        caja.serie(),
                        caja.areaId(),
                        caja.activa());
        porCodigo.put(clave, guardada);
        return guardada;
    }
}
