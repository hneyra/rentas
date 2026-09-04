package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El seguimiento de los convenios: el listado paginado y la ficha de uno (RF-084).
 *
 * <h2>Por que existe como caso de uso y no como una llamada suelta al repositorio</h2>
 *
 * <p>Por lo que la marcha blanca de #26 destapo: {@code GET /catastro/vias} corria <b>sin
 * transaccion</b> —y sin transaccion no hay {@code SET LOCAL}, asi que RLS falla— porque nadie con
 * permiso habia llegado nunca a el. Un {@code @Transactional(readOnly = true)} en un caso de uso es
 * lo que garantiza que la lectura tenga contexto de tenant.
 *
 * <h2>Cada cifra con su fecha</h2>
 *
 * <p>Regla 9. La deuda acogida viene con la <b>fecha de corte</b> del convenio —es la que se
 * congelo— y el saldo del cronograma con la <b>fecha de la consulta</b>, porque «vencidas» depende
 * de que dia es hoy. Son dos fechas distintas y la fila las lleva las dos: mostrarlas bajo una sola
 * haria que un convenio de marzo pareciera calculado hoy.
 */
@Service
public class ConsultaDeConvenios {

    private final ConvenioRepository convenios;
    private final MovimientoDeConvenioRepository movimientos;
    private final Clock reloj;

    public ConsultaDeConvenios(
            ConvenioRepository convenios, MovimientoDeConvenioRepository movimientos, Clock reloj) {
        this.convenios = convenios;
        this.movimientos = movimientos;
        this.reloj = reloj;
    }

    /** El listado que pide el criterio, paginado. */
    @Transactional(readOnly = true)
    public Pagina<ConvenioEnConsulta> listar(CriterioDeConvenios criterio, Paginacion paginacion) {
        Objects.requireNonNull(criterio, "La consulta necesita su criterio");
        Objects.requireNonNull(paginacion, "Sin paginacion no hay orden garantizado");
        return convenios.buscar(criterio, paginacion);
    }

    /**
     * La ficha completa de un convenio: su resumen, sus cuotas, la deuda original que acogio, sus
     * movimientos y su cierre.
     *
     * <p>Se pide por numero y devuelve todo de una vez porque es lo que la pantalla dibuja de una
     * vez. Una pagina del listado no puede costar veinte lecturas como esta, y por eso el listado
     * devuelve {@link ConvenioEnConsulta} y no esto.
     */
    @Transactional(readOnly = true)
    public Optional<Ficha> ficha(NumeroDeConvenio numero) {
        Objects.requireNonNull(numero, "Se consulta un convenio concreto, por su numero");
        return convenios
                .porNumero(numero)
                .map(
                        convenio -> {
                            List<MovimientoDeConvenio> historia =
                                    movimientos.deConvenio(convenio.idGuardado());
                            return new Ficha(
                                    convenio,
                                    EstadoDeConvenio.deLosMovimientos(historia),
                                    historia,
                                    LocalDate.now(reloj));
                        });
    }

    /**
     * Un convenio con todo lo que le ha pasado.
     *
     * @param convenio el convenio, con su deuda acogida y su cronograma congelados
     * @param estado en que situacion esta, derivado de los movimientos
     * @param movimientos su formalizacion y su cierre, del primero al ultimo
     * @param aLaFecha la fecha con la que se respondio lo que depende de hoy (regla 9, RNF-075)
     */
    public record Ficha(
            Convenio convenio,
            EstadoDeConvenio estado,
            List<MovimientoDeConvenio> movimientos,
            LocalDate aLaFecha) {

        public Ficha {
            Objects.requireNonNull(convenio, "La ficha es de un convenio");
            Objects.requireNonNull(estado, "La ficha dice en que situacion esta");
            movimientos = List.copyOf(movimientos);
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }

        /**
         * Las cuotas cobradas.
         *
         * <p>Hoy solo puede ser la inicial, y no por descuido: cobrar una {@code CUOTA_CONVENIO} en
         * caja exige una <b>regla de imputacion</b> —que parte de la deuda acogida extingue un pago
         * parcial— que es normativa (TUO del Codigo Tributario art. 31) y que #33 ya declino
         * definir. Se deriva de los movimientos, no de una columna, asi que el dia que esa regla
         * exista esta cuenta funciona sin cambiar.
         */
        public int cuotasPagadas() {
            return (int)
                    movimientos.stream()
                            .filter(m -> m.tipo() == TipoDeMovimientoDeConvenio.FORMALIZACION)
                            .count();
        }

        /** Las cuotas vencidas y no cobradas a {@link #aLaFecha}. */
        public int cuotasVencidas() {
            int pagadas = cuotasPagadas();
            int vencidas = 0;
            for (CuotaDeConvenio cuota : convenio.cronograma()) {
                if (cuota.esInicial()) {
                    continue;
                }
                // Sin cobrar es «numero >= pagadas», el mismo predicado que usa el saldo:
                // con la inicial cobrada (pagadas = 1), la cuota 1 sigue pendiente.
                if (!cuota.vencimiento().isAfter(aLaFecha) && cuota.numero() >= pagadas) {
                    vencidas++;
                }
            }
            return vencidas;
        }

        /** Lo que queda por cobrar del cronograma, a {@link #aLaFecha}. */
        public Dinero saldoDelCronograma() {
            int pagadas = cuotasPagadas();
            Dinero saldo = Dinero.CERO;
            for (CuotaDeConvenio cuota : convenio.cronograma()) {
                if (cuota.numero() >= pagadas) {
                    saldo = saldo.mas(cuota.monto());
                }
            }
            return saldo;
        }

        /** El acta del cierre, si el convenio esta cerrado. */
        public @Nullable MovimientoDeConvenio cierre() {
            for (MovimientoDeConvenio movimiento : movimientos) {
                if (movimiento.tipo().cierra()) {
                    return movimiento;
                }
            }
            return null;
        }
    }
}
