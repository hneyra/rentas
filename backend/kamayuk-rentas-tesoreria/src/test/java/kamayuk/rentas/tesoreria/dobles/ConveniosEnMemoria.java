package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import org.jspecify.annotations.Nullable;

/**
 * Los convenios, en memoria. <b>Solo agrega</b>: no hay forma de editar uno, igual que en la base
 * —V31 le revoca el {@code UPDATE} a {@code kamayuk_app}—.
 *
 * <p>El correlativo se comporta como el de la base: incrementa por ejercicio y no se reinicia. Que
 * dos registros consecutivos no repitan numero se prueba aqui; que no lo repitan <b>bajo
 * concurrencia real</b> es otra cosa, y esa la hace {@code ConvenioJdbcTest} contra PostgreSQL.
 *
 * <p>Reproduce tambien {@code convenio_idempotencia_uq} (V70): sin ella el doble dejaria pasar lo
 * que la base rechaza, y las pruebas de {@code RegistrarPreconvenio} probarian menos de lo que
 * parece.
 */
public final class ConveniosEnMemoria implements ConvenioRepository {

    private final Map<Long, Convenio> guardados = new LinkedHashMap<>();
    private final Map<Integer, Long> correlativos = new LinkedHashMap<>();
    private final Map<String, Long> claves = new LinkedHashMap<>();
    private long siguienteId = 1;

    public List<Convenio> registrados() {
        return List.copyOf(guardados.values());
    }

    @Override
    public NumeroDeConvenio siguienteNumero(Ejercicio ejercicio) {
        long ultimo = correlativos.merge(ejercicio.valor(), 1L, Long::sum);
        return new NumeroDeConvenio(ejercicio, ultimo);
    }

    @Override
    public Convenio registrar(Convenio convenio, @Nullable String claveDeIdempotencia) {
        if (!convenio.esNuevo()) {
            throw new IllegalArgumentException("Un convenio ya registrado no se vuelve a insertar");
        }
        if (claveDeIdempotencia != null && claves.containsKey(claveDeIdempotencia)) {
            throw new ClaveRepetida(
                    "Ya se registro un convenio con esa clave de idempotencia",
                    new IllegalStateException("convenio_idempotencia_uq"));
        }
        long id = siguienteId++;
        Convenio guardado =
                new Convenio(
                        id,
                        convenio.numero(),
                        convenio.contribuyenteId(),
                        convenio.tipo(),
                        convenio.fecha(),
                        convenio.fechaCorte(),
                        convenio.condiciones(),
                        convenio.acogida(),
                        convenio.cronograma(),
                        convenio.tipoGarantia(),
                        convenio.detalleGarantia(),
                        convenio.resolucion(),
                        convenio.convenioOrigenId(),
                        convenio.registradoEn(),
                        "cajero.prueba",
                        convenio.observacion());
        guardados.put(id, guardado);
        if (claveDeIdempotencia != null) {
            claves.put(claveDeIdempotencia, id);
        }
        return guardado;
    }

    @Override
    public Optional<Convenio> porClaveDeIdempotencia(String clave) {
        Long id = claves.get(clave);
        return id == null ? Optional.empty() : Optional.ofNullable(guardados.get(id));
    }

    @Override
    public Optional<Convenio> porNumero(NumeroDeConvenio numero) {
        return guardados.values().stream()
                .filter(convenio -> convenio.numero().equals(numero))
                .findFirst();
    }

    @Override
    public Optional<Convenio> porId(long id) {
        return Optional.ofNullable(guardados.get(id));
    }

    @Override
    public Pagina<ConvenioEnConsulta> buscar(CriterioDeConvenios criterio, Paginacion paginacion) {
        // El listado se prueba contra PostgreSQL: aqui el estado se derivaria en Java y el
        // de la base en SQL, y comparar dos derivaciones distintas no prueba ninguna.
        return Pagina.de(new ArrayList<>(), paginacion, 0);
    }
}
