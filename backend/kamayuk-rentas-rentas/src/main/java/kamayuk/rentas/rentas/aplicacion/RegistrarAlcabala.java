package kamayuk.rentas.rentas.aplicacion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.rentas.dominio.Transferencia;
import kamayuk.rentas.rentas.dominio.TransferenciaRepository;
import kamayuk.rentas.rentas.dominio.alcabala.BaseImponibleDeAlcabala;
import kamayuk.rentas.rentas.dominio.alcabala.EleccionDeBase;
import kamayuk.rentas.rentas.dominio.alcabala.ImpuestoDeAlcabala;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Determina la alcabala sobre una transferencia de predio ya registrada (#29, #32; TUO Ley de
 * Tributación Municipal, D.S. 156-2004-EF, arts. 21 a 29).
 *
 * <p><b>La elección de base queda registrada con su fundamento</b> —criterio de aceptación de #32—:
 * {@link BaseImponibleDeAlcabala#elegir} decide entre el valor de transferencia y el autovalúo
 * ajustado, y el texto de por qué viaja en la auditoría (regla 10), no solo el número elegido.
 *
 * <p><b>No calcula el ajuste del autovalúo por el IPM</b>: llega como argumento, ya resuelto —ver
 * el javadoc de {@link BaseImponibleDeAlcabala}—.
 *
 * <p>El tramo inafecto son <b>10 UIT</b> (TUO LTM art. 25): el «10» es estructura —la ley lo fija,
 * no una ordenanza—, igual que {@code Vehiculo.EJERCICIOS_AFECTOS}; la UIT en sí se lee del
 * conjunto sellado (regla 5). La alícuota (3 %) también se lee del conjunto sellado, como la
 * alícuota vehicular de {@link RegistrarDeterminacionVehicular}.
 */
@Service
public class RegistrarAlcabala {

    public static final String ALICUOTA_ALCABALA = "ALICUOTA_ALCABALA";
    public static final String TIPO_UIT = "UIT";

    /** TUO LTM art. 25: el tramo inafecto son las primeras 10 UIT. Estructura, no una cifra. */
    private static final BigDecimal UIT_DEL_TRAMO_INAFECTO = BigDecimal.TEN;

    private static final String TABLA_AUDITADA = "determinacion";

    private final TransferenciaRepository transferencias;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarAlcabala(
            TransferenciaRepository transferencias,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.transferencias = transferencias;
        this.determinaciones = determinaciones;
        this.parametros = parametros;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Determina la alcabala de una transferencia de predio ya registrada.
     *
     * @param transferenciaId la transferencia sobre la que se determina (#29)
     * @param autovaluoAjustado el autovalúo del predio ya ajustado por el IPM
     * @param observacion por qué se registra (regla 10)
     */
    @Transactional
    public Determinacion determinar(
            long transferenciaId, Dinero autovaluoAjustado, Observacion observacion) {
        Transferencia transferencia =
                transferencias
                        .findById(transferenciaId)
                        .orElseThrow(() -> new TransferenciaInexistente(transferenciaId));

        if (transferencia.objeto() != ObjetoDeTransferencia.PREDIO) {
            throw new NoGravaAlcabala(
                    transferenciaId, "un vehiculo no paga alcabala (TUO LTM art. 21)");
        }
        if (!transferencia.afectaAlcabala()) {
            throw new NoGravaAlcabala(
                    transferenciaId,
                    "el tipo de transferencia '"
                            + transferencia.tipoTransferencia()
                            + "' no grava alcabala");
        }

        Ejercicio ejercicio = Ejercicio.de(transferencia.fechaTransferencia());
        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();

        EleccionDeBase eleccion =
                BaseImponibleDeAlcabala.elegir(
                        transferencia.valorTransferencia(), autovaluoAjustado);

        Dinero uit = new Dinero(sellados.exigirNumero(TIPO_UIT, null).valor());
        Dinero tramoInafecto = uit.por(UIT_DEL_TRAMO_INAFECTO);
        Alicuota alicuota =
                Alicuota.de(sellados.exigirNumero(ALICUOTA_ALCABALA, null).valor().toPlainString());

        Dinero montoDeterminado =
                ImpuestoDeAlcabala.calcular(eleccion.base(), tramoInafecto, alicuota);

        Determinacion nueva =
                Determinacion.nuevaAlcabala(
                        ejercicio,
                        transferencia.adquirienteId(),
                        requerirPredioId(transferencia),
                        conjuntoId,
                        eleccion.base(),
                        montoDeterminado,
                        List.of(ALICUOTA_ALCABALA));

        Determinacion guardada = determinaciones.insertar(nueva);
        auditar(guardada, eleccion, observacion);
        return guardada;
    }

    private void auditar(Determinacion guardada, EleccionDeBase eleccion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada, eleccion)));
    }

    private static String descripcion(Determinacion determinacion, EleccionDeBase eleccion) {
        return "{\"tributo\":\"ALCABALA\",\"predioId\":"
                + determinacion.predioId()
                + ",\"contribuyenteId\":"
                + determinacion.contribuyenteId()
                + ",\"ejercicio\":\""
                + determinacion.ejercicio()
                + "\",\"conjuntoId\":"
                + determinacion.conjuntoId()
                + ",\"baseImponible\":\""
                + determinacion.baseImponible()
                + "\",\"montoDeterminado\":\""
                + determinacion.montoDeterminado()
                + "\",\"origenDeLaBase\":\""
                + eleccion.origen()
                + "\",\"fundamento\":\""
                + eleccion.fundamento().replace("\"", "'")
                + "\"}";
    }

    private static long requerirPredioId(Transferencia transferencia) {
        Long predioId = transferencia.predioId();
        Objects.requireNonNull(
                predioId, "Una transferencia de predio ya validada siempre tiene predioId");
        return predioId;
    }

    /** No hay ninguna transferencia con ese identificador, o es de otra municipalidad. */
    public static final class TransferenciaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransferenciaInexistente(long id) {
            super(
                    "No hay ninguna transferencia con identificador "
                            + id
                            + " en esta municipalidad");
        }
    }

    /** La transferencia no grava alcabala: no es de predio, o su tipo no la afecta. */
    public static final class NoGravaAlcabala extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NoGravaAlcabala(long transferenciaId, String motivo) {
            super("La transferencia " + transferenciaId + " no grava alcabala: " + motivo);
        }
    }
}
