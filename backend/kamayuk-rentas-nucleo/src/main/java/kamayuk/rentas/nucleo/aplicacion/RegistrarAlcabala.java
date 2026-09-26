package kamayuk.rentas.nucleo.aplicacion;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.nucleo.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.alcabala.BaseImponibleDeAlcabala;
import kamayuk.rentas.nucleo.dominio.alcabala.EleccionDeBase;
import kamayuk.rentas.nucleo.dominio.alcabala.ImpuestoDeAlcabala;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.parametros.ConjuntoVigente;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
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
 * <p><b>Las tres cifras salen del conjunto sellado</b> (regla 5), con las llaves con que {@code
 * normativa} las publica (#376): la {@link LlavesDelConjunto#UIT}, cuántas UIT forman el tramo
 * inafecto —{@link LlavesDelConjunto#ALCABALA_TRAMO_INAFECTO_UIT}, las 10 del art. 25— y la
 * alícuota —{@link LlavesDelConjunto#ALCABALA_ALICUOTA}, el 3 %—. Hasta #376 la alícuota se pedía
 * como {@code ALICUOTA_ALCABALA}, que nadie publica, y la operación contestaba siempre 422 «falta
 * publicar» con el conjunto real; y el «10» estaba escrito aquí como estructura de la ley, cuando
 * {@code normativa} lo publica como cifra.
 *
 * <p><b>Y el impuesto se redondea en su punto</b>, {@code REDONDEO:IMPUESTO_ALCABALA}, con la
 * politica del mismo conjunto (#378; ADR-0018 de {@code normativa}). Se pide por {@link
 * PoliticasDeRedondeoSelladas#en} para que, si falta, el 422 diga de que ejercicio es la fila que
 * hay que publicar (#633). Hasta #378 la respuesta y la auditoria decian el producto crudo y la
 * columna {@code dinero} guardaba otra cifra.
 */
@Service
public class RegistrarAlcabala {

    private static final String TABLA_AUDITADA = "determinacion";

    private final TransferenciaRepository transferencias;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;

    public RegistrarAlcabala(
            TransferenciaRepository transferencias,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            Auditoria auditoria) {
        this.transferencias = transferencias;
        this.determinaciones = determinaciones;
        this.parametros = parametros;
        this.auditoria = auditoria;
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
        // Una resolucion, no dos (#361): los parametros y el id del mismo conjunto.
        ConjuntoVigente conjunto = parametros.vigenteConSuConjunto(ejercicio);
        ParametrosSellados sellados = conjunto.parametros();
        long conjuntoId = conjunto.id();

        EleccionDeBase eleccion =
                BaseImponibleDeAlcabala.elegir(
                        transferencia.valorTransferencia(), autovaluoAjustado);

        Dinero uit = new Dinero(sellados.exigirNumero(LlavesDelConjunto.UIT, null).valor());
        Dinero tramoInafecto =
                uit.por(
                        sellados.exigirNumero(LlavesDelConjunto.ALCABALA_TRAMO_INAFECTO_UIT, null)
                                .valor());
        Alicuota alicuota =
                Alicuota.de(
                        sellados.exigirNumero(LlavesDelConjunto.ALCABALA_ALICUOTA, null)
                                .valor()
                                .toPlainString());
        PoliticaDeRedondeo redondeo =
                PoliticasDeRedondeoSelladas.en(sellados, ImpuestoDeAlcabala.PUNTO_DE_REDONDEO);

        Dinero montoDeterminado =
                ImpuestoDeAlcabala.calcular(eleccion.base(), tramoInafecto, alicuota, redondeo);

        Determinacion nueva =
                Determinacion.nuevaAlcabala(
                        ejercicio,
                        transferencia.adquirienteId(),
                        requerirPredioId(transferencia),
                        conjuntoId,
                        eleccion.base(),
                        montoDeterminado,
                        List.of(
                                LlavesDelConjunto.ALCABALA_TRAMO_INAFECTO_UIT,
                                LlavesDelConjunto.ALCABALA_ALICUOTA));

        Determinacion guardada = determinaciones.insertar(nueva);
        auditar(guardada, eleccion, observacion);
        return guardada;
    }

    private void auditar(Determinacion guardada, EleccionDeBase eleccion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
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
