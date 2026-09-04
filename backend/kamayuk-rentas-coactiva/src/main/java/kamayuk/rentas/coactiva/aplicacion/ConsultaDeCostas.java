package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.coactiva.dominio.EstadoDeLaLiquidacion;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla «Liquidaciones encontradas» de {@code costas_procesales} (#42, RF-104).
 *
 * <h2>El estado y el pendiente salen del libro, no de una columna</h2>
 *
 * <p>{@code liquidacion_costas} no tiene columna de estado (V35 §4), asi que aqui se pregunta:
 * cuanto queda de la obligacion de costas <b>a la fecha</b>. Es la misma fuente que la ventanilla
 * cobra, de modo que una liquidacion no puede aparecer como pendiente en la grilla y como pagada en
 * caja.
 *
 * <p>El total <b>liquidado</b> es otra cifra y viaja aparte: esta congelado el dia de la
 * liquidacion y no cambia nunca, mientras que el pendiente depende de la fecha. Presentarlas bajo
 * una sola haria que una liquidacion de marzo pareciera calculada hoy (regla 9, RNF-075).
 *
 * <p>Por {@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL}, y sin
 * el la politica RLS falla en vez de devolver filas.
 */
@Service
public class ConsultaDeCostas {

    private final LiquidacionDeCostasRepository liquidaciones;
    private final ExpedienteRepository expedientes;
    private final ConsultaDeDeudaPublica deuda;

    public ConsultaDeCostas(
            LiquidacionDeCostasRepository liquidaciones,
            ExpedienteRepository expedientes,
            ConsultaDeDeudaPublica deuda) {
        this.liquidaciones = liquidaciones;
        this.expedientes = expedientes;
        this.deuda = deuda;
    }

    /**
     * Las liquidaciones que cumplen el criterio, con su pendiente y su estado a la fecha.
     *
     * @param estado si se da, se descartan las filas que no lo tengan. Se filtra <b>despues</b> de
     *     paginar y no en SQL, porque el estado se deriva del libro y filtrarlo antes exigiria
     *     consultar la deuda de todas las liquidaciones de la municipalidad
     */
    @Transactional(readOnly = true)
    public Pagina<LiquidacionEnConsulta> buscar(
            CriterioDeLiquidaciones criterio,
            LocalDate aLaFecha,
            @Nullable EstadoDeLaLiquidacion estado,
            Paginacion paginacion) {

        Objects.requireNonNull(criterio, "La consulta necesita su criterio");
        Objects.requireNonNull(aLaFecha, "Toda cifra se pide a una fecha (regla 9)");

        Pagina<LiquidacionDeCostas> pagina = liquidaciones.consultar(criterio, paginacion);
        Map<Long, List<ObligacionPublica>> porContribuyente = new HashMap<>();

        Pagina<LiquidacionEnConsulta> compuesta =
                pagina.mapear(fila -> componer(fila, aLaFecha, porContribuyente));
        if (estado == null) {
            return compuesta;
        }
        List<LiquidacionEnConsulta> filtradas =
                compuesta.contenido().stream().filter(fila -> fila.estado() == estado).toList();
        return new Pagina<>(
                filtradas, compuesta.pagina(), compuesta.tamano(), compuesta.totalElementos());
    }

    /** Una liquidacion por su numero, con su detalle. */
    @Transactional(readOnly = true)
    public Optional<LiquidacionEnConsulta> porNumero(String numero, LocalDate aLaFecha) {
        return liquidaciones
                .porNumero(numero)
                .map(fila -> componer(fila, aLaFecha, new HashMap<>()));
    }

    // ------------------------------------------------------------------

    private LiquidacionEnConsulta componer(
            LiquidacionDeCostas liquidacion,
            LocalDate aLaFecha,
            Map<Long, List<ObligacionPublica>> porContribuyente) {

        List<ObligacionPublica> obligaciones =
                porContribuyente.computeIfAbsent(
                        liquidacion.contribuyenteId(),
                        id -> deuda.deTodoElContribuyente(id, aLaFecha));

        Dinero pendiente = Dinero.de("0.00");
        for (ObligacionPublica obligacion : obligaciones) {
            if (obligacion.tributo().equalsIgnoreCase(liquidacion.tributo())
                    && obligacion.ejercicio().equals(liquidacion.ejercicio())
                    && obligacion.predioId() == null
                    && obligacion.vehiculoId() == null) {
                pendiente = pendiente.mas(obligacion.total());
            }
        }

        String numeroDeExpediente =
                expedientes
                        .porId(liquidacion.expedienteId())
                        .map(ExpedienteCoactivo::numero)
                        .orElse(String.valueOf(liquidacion.expedienteId()));

        return new LiquidacionEnConsulta(
                liquidacion,
                numeroDeExpediente,
                pendiente,
                aLaFecha,
                EstadoDeLaLiquidacion.segunLoPendiente(pendiente));
    }

    /**
     * Una liquidacion como la pinta la pantalla.
     *
     * <p><b>Dos cifras y una sola fecha visible, y no es un descuido</b>: {@code
     * liquidacion.total()} esta congelado el dia de la liquidacion —que la propia liquidacion
     * lleva— y {@link #pendiente} es de {@link #aLaFecha}. Cada una viaja con la suya.
     *
     * @param liquidacion la cabecera con sus lineas
     * @param numeroDeExpediente el numero impreso del expediente, para no obligar a la pantalla a
     *     resolverlo
     * @param pendiente cuanto queda de su obligacion de costas a la fecha
     * @param aLaFecha la fecha a la que se respondio el pendiente (regla 9, RNF-075)
     * @param estado derivado del pendiente; nunca leido de una columna
     */
    public record LiquidacionEnConsulta(
            LiquidacionDeCostas liquidacion,
            String numeroDeExpediente,
            Dinero pendiente,
            LocalDate aLaFecha,
            EstadoDeLaLiquidacion estado) {

        public LiquidacionEnConsulta {
            Objects.requireNonNull(liquidacion, "La fila es la de una liquidacion");
            Objects.requireNonNull(numeroDeExpediente, "La fila dice de que expediente es");
            Objects.requireNonNull(pendiente, "Toda cifra viaja con su fecha (regla 9)");
            Objects.requireNonNull(aLaFecha, "Toda cifra viaja con su fecha (regla 9)");
            Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
        }
    }
}
