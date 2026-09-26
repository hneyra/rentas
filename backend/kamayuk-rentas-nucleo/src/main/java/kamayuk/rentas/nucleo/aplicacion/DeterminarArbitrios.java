package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import kamayuk.rentas.nucleo.dominio.arbitrios.PeriodoDeArbitrio;
import kamayuk.rentas.nucleo.dominio.arbitrios.Servicio;
import kamayuk.rentas.nucleo.dominio.arbitrios.TitularPrincipalRepository;
import kamayuk.rentas.parametros.ConjuntoVigente;
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
 * <p><b>Cada mes se decide a su fecha</b> (#443): el titular, el uso y el sector del predio y los
 * beneficios que lo exoneran se leen a {@link PeriodoDeArbitrio#fechaDeAtribucion}, y no a la fecha
 * de la corrida. Solo se generan los meses en que el predio tiene rasgos y titular: el predio
 * independizado en julio no debe de enero a junio, y la inafectacion que acaba el 31 de diciembre
 * no exonera el ejercicio siguiente aunque la emision se corra el 20 de diciembre.
 *
 * <p><b>Reejecutar no duplica cargos</b> (AC de #31): antes de cada cuota se consulta {@link
 * CuotaDeArbitrioRepository#existe}, y la restriccion {@code det_arbitrio_uq} ({@code
 * V1__baseline.sql}) es la garantía real, no solo la de esta comprobación. Las cuotas ya generadas
 * no se recalculan.
 */
@Service
public class DeterminarArbitrios {

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
        LocalDate hoy = LocalDate.now(reloj);

        // Una resolucion, no dos (#361): los parametros y el id del mismo conjunto.
        ConjuntoVigente conjunto = parametros.vigenteConSuConjunto(ejercicio);
        ParametrosSellados sellados = conjunto.parametros();
        long conjuntoId = conjunto.id();

        List<CuotaDeArbitrio> generadas = new ArrayList<>();
        boolean algunMesConRasgos = false;
        boolean algunMesConTitular = false;
        for (int periodo = PeriodoDeArbitrio.PRIMERO;
                periodo <= PeriodoDeArbitrio.ULTIMO;
                periodo++) {
            LocalDate atribucion = PeriodoDeArbitrio.fechaDeAtribucion(ejercicio, periodo);
            Optional<CaracteristicasDelPredio> rasgos =
                    caracteristicas
                            .de(predioId, atribucion)
                            .filter(c -> c.uso() != null && c.sectorCodigo() != null);
            if (rasgos.isEmpty()) {
                continue;
            }
            algunMesConRasgos = true;
            Optional<Long> titular = titulares.principalDe(predioId, atribucion);
            if (titular.isEmpty()) {
                continue;
            }
            algunMesConTitular = true;

            String claveDeTasa = rasgos.get().sectorCodigo() + ":" + rasgos.get().uso();
            for (Servicio servicio : Servicio.values()) {
                if (excluidoPorBeneficio(predioId, servicio, atribucion)
                        || cuotas.existe(predioId, servicio, ejercicio, periodo)) {
                    continue;
                }
                Dinero monto =
                        new Dinero(
                                sellados.exigirNumero(
                                                LlavesDelConjunto.tasaDeArbitrio(servicio),
                                                claveDeTasa)
                                        .valor());
                generadas.add(
                        determinarCuota(
                                ejercicio,
                                servicio,
                                periodo,
                                titular.get(),
                                predioId,
                                conjuntoId,
                                monto,
                                LlavesDelConjunto.tasaDeArbitrio(servicio) + ":" + claveDeTasa,
                                hoy,
                                observacion));
            }
        }
        // Nada se escribio si ningun mes tenia rasgos y titular: fallar aqui no deja cuotas a
        // medias.
        if (!algunMesConRasgos) {
            throw new PredioSinCaracteristicas(predioId);
        }
        if (!algunMesConTitular) {
            throw new PredioSinTitular(predioId);
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

    /** El predio no tiene uso o sector en ningun mes del ejercicio: no hay con qué buscar tasa. */
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

    /** El predio no tiene titular en ningun mes en que tenga rasgos: no hay a quién cobrarle. */
    public static final class PredioSinTitular extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioSinTitular(long predioId) {
            super("El predio " + predioId + " no tiene ningún titular vigente");
        }
    }
}
