package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCiiu;

/**
 * Un {@link CiiuRepository} en memoria.
 *
 * <p><b>Impone la unicidad del codigo</b>, que es lo que hace que la traduccion del 409 tenga algo
 * que traducir: un doble que aceptara dos veces el mismo codigo dejaria esa prueba sin objeto.
 */
public final class CatalogoEnMemoria implements CiiuRepository {

    private final List<Ciiu> giros = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public Ciiu registrar(Ciiu giro) {
        if (porCodigo(giro.codigo()).isPresent()) {
            throw new CodigoDuplicado(
                    "El giro " + giro.codigo() + " ya esta en el catalogo",
                    new IllegalStateException("ciiu_codigo_uq"));
        }
        Ciiu conId =
                new Ciiu(
                        siguienteId++,
                        giro.codigo(),
                        giro.descripcion(),
                        giro.seccion(),
                        giro.riesgoItse(),
                        giro.zonificacionCompatible(),
                        giro.requiereSectorial(),
                        giro.extendido(),
                        giro.activo(),
                        giro.registradoEn(),
                        "prueba",
                        giro.observacion());
        giros.add(conId);
        return conId;
    }

    /** Siembra un giro sin pasar por el caso de uso. */
    public CatalogoEnMemoria con(Ciiu giro) {
        registrar(giro);
        return this;
    }

    @Override
    public Optional<Ciiu> porCodigo(String codigo) {
        String buscado = codigo.strip().toUpperCase(Locale.ROOT);
        return giros.stream().filter(giro -> giro.codigo().equals(buscado)).findFirst();
    }

    @Override
    public List<Ciiu> porIds(Set<Long> ids) {
        return giros.stream().filter(giro -> ids.contains(giro.identificador())).toList();
    }

    @Override
    public Pagina<Ciiu> buscar(CriterioDeCiiu criterio, Paginacion paginacion) {
        List<Ciiu> filtrados =
                giros.stream()
                        .filter(
                                giro ->
                                        criterio.codigo() == null
                                                || giro.codigo()
                                                        .startsWith(
                                                                criterio.codigo()
                                                                        .toUpperCase(Locale.ROOT)))
                        .filter(
                                giro ->
                                        criterio.descripcion() == null
                                                || giro.descripcion()
                                                        .startsWith(criterio.descripcion()))
                        .filter(
                                giro ->
                                        criterio.seccion() == null
                                                || criterio.seccion().equals(giro.seccion()))
                        .toList();
        return Pagina.de(filtrados, paginacion, filtrados.size());
    }
}
