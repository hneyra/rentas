package kamayuk.rentas.contribuyentes.aplicacion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Un padron en memoria para las pruebas de la carga desde archivo, que no necesitan base de datos:
 * lo que se verifica es que el cargador lee el archivo, respeta la guarda de demostracion y deja el
 * contexto limpio, no como persiste PostgreSQL —eso ya lo prueba {@code
 * ContribuyenteRepositoryJdbcTest} contra el motor de verdad—.
 *
 * <p><b>Imita la unicidad de la tabla</b> y lanza la misma familia de excepcion que el controlador
 * de errores traduce ({@link DataIntegrityViolationException}). Sin eso, la prueba de «una fila
 * repetida no arrastra a la siguiente» no probaria nada: nunca fallaria ninguna fila.
 */
final class PadronEnMemoria implements ContribuyenteRepository {

    private final Map<Long, Contribuyente> porId = new LinkedHashMap<>();
    private long siguienteId = 1;

    @Override
    public Optional<Contribuyente> findById(long id) {
        return Optional.ofNullable(porId.get(id));
    }

    @Override
    public List<Contribuyente> findAllById(Collection<Long> ids) {
        List<Contribuyente> hallados = new ArrayList<>();
        for (Long id : ids) {
            Contribuyente contribuyente = porId.get(id);
            if (contribuyente != null) {
                hallados.add(contribuyente);
            }
        }
        return hallados;
    }

    @Override
    public Optional<Contribuyente> findByCodigo(CodigoContribuyente codigo) {
        return porId.values().stream().filter(c -> c.codigo().equals(codigo)).findFirst();
    }

    @Override
    public Optional<Contribuyente> findByDocumento(DocumentoIdentidad documento) {
        return porId.values().stream().filter(c -> c.documento().equals(documento)).findFirst();
    }

    @Override
    public Pagina<Contribuyente> buscar(CriterioDeBusqueda criterio, Paginacion paginacion) {
        throw new UnsupportedOperationException("La carga desde archivo no busca");
    }

    @Override
    public Contribuyente save(Contribuyente contribuyente) {
        if (contribuyente.esNuevo()) {
            // Las dos restricciones de unicidad de la tabla, que son la barrera de verdad.
            if (findByCodigo(contribuyente.codigo()).isPresent()
                    || findByDocumento(contribuyente.documento()).isPresent()) {
                throw new DataIntegrityViolationException("contribuyente_codigo_uq");
            }
            Contribuyente guardado = conId(contribuyente, siguienteId++);
            porId.put(guardado.id(), guardado);
            return guardado;
        }
        porId.put(contribuyente.id(), contribuyente);
        return contribuyente;
    }

    /** Cuantos hay. Lo que las pruebas comprueban despues de cargar un archivo. */
    int cuantos() {
        return porId.size();
    }

    List<String> codigos() {
        return porId.values().stream().map(c -> c.codigo().valor()).toList();
    }

    private static Contribuyente conId(Contribuyente contribuyente, long id) {
        return new Contribuyente(
                id,
                contribuyente.codigo(),
                contribuyente.documento(),
                contribuyente.tipoPersona(),
                contribuyente.nombreRazonSocial(),
                contribuyente.condicionEspecial(),
                contribuyente.fechaNacimiento(),
                contribuyente.estadoCivil(),
                contribuyente.conyugeId(),
                contribuyente.activo());
    }
}
