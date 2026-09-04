package kamayuk.rentas.coactiva.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteEnConsulta;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Un {@link ExpedienteRepository} en memoria, para probar el transporte HTTP sin base de datos.
 *
 * <p>Imita el indice unico {@code expediente_valor_unico_uq}: un valor que ya esta en un expediente
 * no entra en otro. Que la <b>garantia</b> sea de la base y no de este {@code if} lo demuestra
 * {@code ExpedienteCoactivoJdbcTest} con diez importaciones concurrentes de verdad; aqui solo se
 * imita para que el controlador se pueda probar sin PostgreSQL.
 */
public final class ExpedientesEnMemoria implements ExpedienteRepository {

    private final Map<Long, ExpedienteCoactivo> guardados = new LinkedHashMap<>();
    private final Map<Long, List<ValorDelExpediente>> valores = new LinkedHashMap<>();
    private final Map<Integer, Long> correlativos = new LinkedHashMap<>();
    private final MovimientosDelExpedienteEnMemoria movimientos;
    private long siguienteId = 1;

    public ExpedientesEnMemoria(MovimientosDelExpedienteEnMemoria movimientos) {
        this.movimientos = movimientos;
    }

    @Override
    public ExpedienteCoactivo abrir(ExpedienteCoactivo expediente) {
        long id = siguienteId++;
        ExpedienteCoactivo conId =
                new ExpedienteCoactivo(
                        id,
                        expediente.numero(),
                        expediente.ejercicio(),
                        expediente.correlativo(),
                        expediente.contribuyenteId(),
                        expediente.ejecutor(),
                        expediente.auxiliar(),
                        expediente.fechaApertura(),
                        expediente.asunto(),
                        expediente.direccionReferencial(),
                        expediente.registradoEn(),
                        "prueba",
                        expediente.observacion());
        guardados.put(id, conId);
        valores.put(id, new ArrayList<>());
        return conId;
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        return correlativos.merge(ejercicio.valor(), 1L, Long::sum);
    }

    @Override
    public Optional<ExpedienteCoactivo> porNumero(String numero) {
        return guardados.values().stream()
                .filter(e -> e.numero().equalsIgnoreCase(numero.strip()))
                .findFirst();
    }

    @Override
    public Optional<ExpedienteCoactivo> porId(long id) {
        return Optional.ofNullable(guardados.get(id));
    }

    @Override
    public ValorDelExpediente importar(
            long expedienteId, long valorId, LocalDate fechaImportacion) {
        if (yaEnUnExpediente(List.of(valorId)).contains(valorId)) {
            throw new ValorYaEnUnExpediente(
                    "El valor " + valorId + " ya esta en un expediente coactivo",
                    new IllegalStateException("indice unico imitado"));
        }
        ValorDelExpediente fila = new ValorDelExpediente(valorId, fechaImportacion);
        valores.computeIfAbsent(expedienteId, id -> new ArrayList<>()).add(fila);
        return fila;
    }

    @Override
    public List<ValorDelExpediente> valoresDe(long expedienteId) {
        return List.copyOf(valores.getOrDefault(expedienteId, List.of()));
    }

    @Override
    public Set<Long> yaEnUnExpediente(Collection<Long> valorIds) {
        Set<Long> encontrados = new LinkedHashSet<>();
        for (List<ValorDelExpediente> deUno : valores.values()) {
            for (ValorDelExpediente fila : deUno) {
                if (valorIds.contains(fila.valorId())) {
                    encontrados.add(fila.valorId());
                }
            }
        }
        return encontrados;
    }

    /** Cuenta lo mismo que {@link #consultar} filtra, reusandolo: aqui no hay dos criterios. */
    @Override
    public long contar(CriterioDeExpedientes criterio) {
        return consultar(
                        criterio,
                        new Paginacion(
                                0, Integer.MAX_VALUE, "numero", Paginacion.Direccion.ASCENDENTE))
                .totalElementos();
    }

    @Override
    public Pagina<ExpedienteEnConsulta> consultar(
            CriterioDeExpedientes criterio, Paginacion paginacion) {
        List<ExpedienteEnConsulta> filas = new ArrayList<>();
        for (ExpedienteCoactivo expediente : guardados.values()) {
            if (criterio.numero() != null
                    && !expediente.numero().equalsIgnoreCase(criterio.numero())) {
                continue;
            }
            if (criterio.contribuyenteId() != null
                    && expediente.contribuyenteId() != criterio.contribuyenteId()) {
                continue;
            }
            List<MovimientoDelExpediente> historial =
                    movimientos.deExpediente(expediente.identificador());
            EstadoDelExpediente estado = EstadoDelExpediente.delHistorial(historial);
            if (criterio.estado() != null && estado != criterio.estado()) {
                continue;
            }
            String vigente =
                    historial.stream()
                            .filter(m -> m.direccionReferencial() != null)
                            .reduce((primero, segundo) -> segundo)
                            .map(MovimientoDelExpediente::direccionNueva)
                            .orElseGet(expediente::direccionReferencial);
            filas.add(
                    new ExpedienteEnConsulta(
                            expediente,
                            estado,
                            vigente,
                            valoresDe(expediente.identificador()).size()));
        }
        return Pagina.de(filas, paginacion, filas.size());
    }
}
