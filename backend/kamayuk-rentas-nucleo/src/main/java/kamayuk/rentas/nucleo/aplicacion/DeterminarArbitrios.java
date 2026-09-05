package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.Beneficio;
import kamayuk.rentas.nucleo.dominio.BeneficioRepository;
import kamayuk.rentas.nucleo.dominio.Clase;
import kamayuk.rentas.nucleo.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.nucleo.dominio.arbitrios.CuotaDeArbitrioRepository;
import kamayuk.rentas.nucleo.dominio.arbitrios.Servicio;
import kamayuk.rentas.nucleo.dominio.arbitrios.TitularPrincipalRepository;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Determina el arbitrio de un predio para un ejercicio: limpieza pública, parques y jardines y
 * serenazgo, mes a mes (#31, RF-022).
 *
 * <p>Sin grafo de reglas ni área: el monto de cada cuota es la tasa parametrizada por servicio,
 * sector y uso, tal cual (ADR-0007). El único cálculo estructural es «cuántas cuotas faltan» y «a
 * cuál servicio excluye qué beneficio» — nunca cuánto vale la tasa (regla 5, D-02b).
 *
 * <p><b>Reejecutar no duplica cargos</b> (AC de #31): antes de cada cuota se consulta {@link
 * CuotaDeArbitrioRepository#existe}, y el {@code UNIQUE} de {@code determinacion_arbitrio} (V23) es
 * la garantía real, no solo la de esta comprobación. Las cuotas ya generadas no se recalculan: un
 * cambio de uso o sector a mitad de ejercicio solo afecta las cuotas que todavía no existían cuando
 * se detecta.
 */
@Service
public class DeterminarArbitrios {

    private static final int PRIMER_PERIODO = 1;
    private static final int ULTIMO_PERIODO = 12;
    private static final String TABLA_AUDITADA = "determinacion_arbitrio";

    private final CuotaDeArbitrioRepository cuotas;
    private final BeneficioRepository beneficios;
    private final TitularPrincipalRepository titulares;
    private final LectorDeCaracteristicas caracteristicas;
    private final LectorDeParametros parametros;
    private final GeneradorDeCargos cargos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public DeterminarArbitrios(
            CuotaDeArbitrioRepository cuotas,
            BeneficioRepository beneficios,
            TitularPrincipalRepository titulares,
            LectorDeCaracteristicas caracteristicas,
            LectorDeParametros parametros,
            GeneradorDeCargos cargos,
            Auditoria auditoria,
            Clock reloj) {
        this.cuotas = cuotas;
        this.beneficios = beneficios;
        this.titulares = titulares;
        this.caracteristicas = caracteristicas;
        this.parametros = parametros;
        this.cargos = cargos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Determina las cuotas de arbitrio de un predio que todavía no existen para ese ejercicio, y
     * asienta su cargo. Las que ya existían no se tocan.
     *
     * @return las cuotas generadas en esta llamada; vacía si no había ninguna pendiente
     */
    @Transactional
    public List<CuotaDeArbitrio> determinarPredio(
            long predioId, Ejercicio ejercicio, Observacion observacion) {
        LocalDate fecha = LocalDate.now(reloj);

        CaracteristicasDelPredio caracteristicasDelPredio =
                caracteristicas
                        .de(predioId, fecha)
                        .filter(c -> c.uso() != null && c.sectorCodigo() != null)
                        .orElseThrow(() -> new PredioSinCaracteristicas(predioId));

        long contribuyenteId =
                titulares
                        .principalDe(predioId, fecha)
                        .orElseThrow(() -> new PredioSinTitular(predioId));

        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();

        String claveDeTasa =
                caracteristicasDelPredio.sectorCodigo() + ":" + caracteristicasDelPredio.uso();

        List<CuotaDeArbitrio> generadas = new ArrayList<>();
        for (Servicio servicio : Servicio.values()) {
            if (excluidoPorBeneficio(predioId, servicio, fecha)) {
                continue;
            }
            String tipoParametro = "TASA_" + servicio.name();
            Dinero monto = new Dinero(sellados.exigirNumero(tipoParametro, claveDeTasa).valor());
            String parametroAplicado = tipoParametro + ":" + claveDeTasa;

            for (int periodo = PRIMER_PERIODO; periodo <= ULTIMO_PERIODO; periodo++) {
                if (cuotas.existe(predioId, servicio, ejercicio, periodo)) {
                    continue;
                }
                generadas.add(
                        determinarCuota(
                                ejercicio,
                                servicio,
                                periodo,
                                contribuyenteId,
                                predioId,
                                conjuntoId,
                                monto,
                                parametroAplicado,
                                fecha,
                                observacion));
            }
        }
        return generadas;
    }

    private CuotaDeArbitrio determinarCuota(
            Ejercicio ejercicio,
            Servicio servicio,
            int periodo,
            long contribuyenteId,
            long predioId,
            long conjuntoId,
            Dinero monto,
            String parametroAplicado,
            LocalDate fecha,
            Observacion observacion) {
        CuotaDeArbitrio guardada =
                cuotas.insertar(
                        CuotaDeArbitrio.nueva(
                                ejercicio,
                                servicio,
                                periodo,
                                contribuyenteId,
                                predioId,
                                conjuntoId,
                                monto,
                                parametroAplicado,
                                fecha));

        cargos.generarCargo(
                ejercicio,
                contribuyenteId,
                TributoDelLibro.ARBITRIO.texto(),
                periodo,
                predioId,
                null,
                null,
                monto,
                vencimientoDe(ejercicio, periodo),
                documentoOrigenDe(ejercicio, servicio),
                observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                        fecha,
                        TABLA_AUDITADA,
                        String.valueOf(guardada.id()),
                        Operacion.ALTA,
                        observacion));

        return guardada;
    }

    private boolean excluidoPorBeneficio(long predioId, Servicio servicio, LocalDate fecha) {
        List<Beneficio> vigentes =
                beneficios.vigentesDelPredio(predioId, servicio.codigoTributo(), fecha);
        return vigentes.stream().anyMatch(b -> b.clase() == Clase.INAFECTACION);
    }

    private static LocalDate vencimientoDe(Ejercicio ejercicio, int periodo) {
        return LocalDate.of(ejercicio.valor(), periodo, 1).with(TemporalAdjusters.lastDayOfMonth());
    }

    private static String documentoOrigenDe(Ejercicio ejercicio, Servicio servicio) {
        return "DETERMINACION-ARBITRIO-" + ejercicio + "-" + servicio.name();
    }

    /** El predio no tiene ficha vigente, o no tiene sector asignado: no hay con qué buscar tasa. */
    public static final class PredioSinCaracteristicas extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioSinCaracteristicas(long predioId) {
            super(
                    "El predio "
                            + predioId
                            + " no tiene uso o sector vigente: no se puede determinar su"
                            + " arbitrio (#31)");
        }
    }

    /** El predio no tiene ningún titular vigente a quién cobrarle. */
    public static final class PredioSinTitular extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioSinTitular(long predioId) {
            super("El predio " + predioId + " no tiene ningún titular vigente");
        }
    }
}
