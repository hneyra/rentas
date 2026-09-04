package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Cuanto abono en el libro cada documento de caja (#36, RF-087).
 *
 * <p>Es la <b>sexta</b> API publica de este modulo, despues de {@link ConsultaDeDeudaPublica},
 * {@link GeneradorDeCargos}, {@link MovimientoDeFase}, {@link RegistroDeAbonos} y {@link
 * AcogimientoAConvenio}. Vive en el paquete raiz por lo mismo que las otras cinco: Spring Modulith
 * trata como interno todo lo que esta en un subpaquete, asi que esto es exactamente lo que {@code
 * tesoreria} puede ver del libro. Sus tablas, no.
 *
 * <h2>Para que existe</h2>
 *
 * <p>El cierre de caja tiene que <b>cuadrar contra el libro</b>: lo que el arqueo dice que se
 * recaudo tiene que ser, centimo a centimo, lo que los asientos de esos recibos dicen que se abono.
 * Sin este puerto, tesoreria solo podria comprobarlo consultando {@code cuenta_corriente_asiento}
 * —cruzar el limite del contexto— o no comprobarlo en absoluto, que es como un cierre firmado acaba
 * diciendo una cifra y el estado de cuenta otra.
 *
 * <h2>Por que por documento y no por fecha</h2>
 *
 * <p>Porque un abono se imputa al ejercicio de la <b>obligacion</b>, no al de la fecha de pago: un
 * recibo del 15 de marzo de 2026 que cobra deuda de 2025 escribe en la particion de 2025. Sumar
 * «los abonos de hoy» daria una cifra que no es la de ningun turno. Lo unico que relaciona un
 * asiento con la caja que lo origino es {@code documento_origen} —{@code "RECIBO 001-0000123"}, y
 * {@code "ANULACION 001-0000123"} para su reversion—, y es por ahi por donde se pregunta.
 *
 * <h2>Lo que este puerto NO dice</h2>
 *
 * <p><b>Nada de los recibos que no abonan.</b> Un recibo de caja de tasas no toca el libro —un
 * derecho de tramite no es deuda tributaria— y el de una cuota inicial de convenio tampoco: lo que
 * la inicial hace es formalizar, y su efecto sobre el libro es el acogimiento a fase de convenio,
 * no un abono (#35). Los dos devuelven cero aqui, y quien cuadra tiene que saberlo: <b>esos recibos
 * cuadran contra el papel, no contra asientos</b>. Devolver cero es la respuesta correcta, y
 * confundirla con «no encontre lo que buscaba» es justo el error que rompe el arqueo.
 */
public interface ConciliacionDeCaja {

    /**
     * Lo que cada uno de esos documentos abono en el libro.
     *
     * <p>Solo los <b>abonos</b>, no los cargos, igual que {@link ReversionDeAbonos#abonado}: al
     * cobrar se cristaliza el devengo con un cargo antes de abonarlo, y sumar tambien los cargos
     * daria una cifra que no coincide con ningun recibo.
     *
     * @param documentosOrigen los documentos por los que se pregunta; sin repetidos y sin nulos
     * @param aLaFecha la fecha con la que se responde; viaja con la cifra (regla 9, RNF-075)
     * @return un importe por documento, cero incluido para los que no asentaron nada
     */
    AbonadoEnElLibro abonadoPor(Collection<String> documentosOrigen, LocalDate aLaFecha);
}
