package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.nucleo.aplicacion.ConsultaUnificada;
import kamayuk.rentas.tesoreria.ConvenioDelContribuyente;
import kamayuk.rentas.valores.ValorDelContribuyente;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;

/**
 * La ficha de {@code consulta_unificada}, tal como sale por HTTP (RF-046, #25). Campos en español
 * {@code camelCase} (ARQ-04 §3).
 *
 * <h2>Por que las secciones son tipos anidados</h2>
 *
 * <p>Porque la forma entera de esta respuesta —una cabecera, un resumen y seis rejillas— es lo que
 * hay que poder leer de una vez para saber que dibuja la pantalla. Repartida en ocho archivos, lo
 * que se pierde es exactamente lo que esta pantalla es: la consolidacion. Cada seccion sigue siendo
 * un record con su javadoc.
 *
 * <h2>Ninguna cifra sin su fecha</h2>
 *
 * <p>Todas viajan como {@link ImporteActualizado}, nunca como {@code Dinero} suelto: lo exige la
 * regla de ArchUnit {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (RNF-075, regla 9). Y las fechas
 * <b>no son todas la misma</b>, que es justo lo que hace falta ver:
 *
 * <ul>
 *   <li>el resumen y las deudas van a {@code aLaFecha}, la fecha de corte de la consulta;
 *   <li>cada pago y cada movimiento van a <b>su</b> fecha valor —lo que se asento no se actualiza—;
 *   <li>la deuda acogida de un convenio va a la fecha de corte <b>del convenio</b> y su saldo a la
 *       de la consulta: dos fechas en la misma fila;
 *   <li>los importes de un valor van a su {@code proyectadoA}, la fecha de la emision, porque un
 *       valor notificado no puede ser una cifra que cambia sola (AC de #37).
 * </ul>
 *
 * <p>Aplanarlas a una sola fecha de respuesta habria sido mas corto y habria hecho que un pago de
 * 2024 y un valor de 2025 parecieran calculados hoy.
 *
 * <h2>Lo que no lleva</h2>
 *
 * <p>Ninguna clave de la rejilla «Impuesto anual» del prototipo —{@code valuoAfecto}, {@code
 * imptoPredial}, {@code limpPublica}, {@code parqYJardines}, {@code rellSanitario}, {@code
 * serenazgo}— ni de «Movimientos del Predio». El porque esta en el javadoc de {@link
 * ConsultaUnificada}; el resumen es que un cero inventado se lee como una afirmacion, y aqui no hay
 * ninguna que hacer todavia.
 *
 * @param contribuyente de quien es la ficha
 * @param aLaFecha la fecha de corte con la que se respondio todo lo que depende de hoy
 * @param resumenDeSaldos las cinco cifras del «Resumen de saldos», sumadas por el servidor
 * @param deudasPendientes la pestaña «Deudas Pendientes»
 * @param pagosRealizados la pestaña «Pagos Realizados»
 * @param altasYBajas la pestaña «Altas y Bajas»
 * @param fraccionamientos la pestaña «Fraccionamientos»
 * @param valores la pestaña «Valores»
 * @param declaracionesJuradas las declaraciones presentadas por el contribuyente
 */
public record ConsultaUnificadaResource(
        ContribuyenteDeLaFicha contribuyente,
        String aLaFecha,
        ResumenDeSaldos resumenDeSaldos,
        RespuestaPaginada<ObligacionDeLaFicha> deudasPendientes,
        RespuestaPaginada<MovimientoDeLaFicha> pagosRealizados,
        RespuestaPaginada<MovimientoDeLaFicha> altasYBajas,
        RespuestaPaginada<ConvenioDeLaFicha> fraccionamientos,
        RespuestaPaginada<ValorDeLaFicha> valores,
        RespuestaPaginada<DeclaracionJuradaResource> declaracionesJuradas) {

    public static ConsultaUnificadaResource de(ConsultaUnificada.Ficha ficha) {
        return new ConsultaUnificadaResource(
                ContribuyenteDeLaFicha.de(ficha.contribuyente()),
                ficha.aLaFecha().toString(),
                ResumenDeSaldos.de(ficha.resumen()),
                RespuestaPaginada.de(ficha.deudas(), ObligacionDeLaFicha::de),
                RespuestaPaginada.de(ficha.pagos(), MovimientoDeLaFicha::de),
                RespuestaPaginada.de(ficha.altasYBajas(), MovimientoDeLaFicha::de),
                RespuestaPaginada.de(ficha.fraccionamientos(), ConvenioDeLaFicha::de),
                RespuestaPaginada.de(ficha.valores(), ValorDeLaFicha::de),
                RespuestaPaginada.de(ficha.declaraciones(), DeclaracionJuradaResource::de));
    }

    /**
     * La cabecera: quien es.
     *
     * <p>Sin el identificador interno: la pantalla identifica al contribuyente por su codigo, que
     * es lo que teclea quien atiende y lo que sale impreso. Exponer la clave primaria invitaria a
     * que alguien la usara como parametro de otra llamada.
     */
    public record ContribuyenteDeLaFicha(String codigo, String nombre, String documento) {

        static ContribuyenteDeLaFicha de(ResumenDeContribuyente contribuyente) {
            return new ContribuyenteDeLaFicha(
                    contribuyente.codigo(), contribuyente.nombre(), contribuyente.documento());
        }
    }

    /**
     * El «Resumen de saldos»: las cinco cifras y la frase que las explica.
     *
     * <p>Las cinco llegan sumadas y {@code estadoDeLaConsulta} redactado (RNF-083): la interfaz no
     * suma ni compone texto con cifras dentro. Si lo hiciera, el dia que el total y el desglose
     * discreparan nadie sabria cual de los dos mirar.
     */
    public record ResumenDeSaldos(
            ImporteActualizado insoluto,
            ImporteActualizado reajuste,
            ImporteActualizado interes,
            ImporteActualizado gasto,
            ImporteActualizado total,
            String estadoDeLaConsulta) {

        static ResumenDeSaldos de(ConsultaUnificada.ResumenDeSaldos resumen) {
            return new ResumenDeSaldos(
                    new ImporteActualizado(resumen.insoluto(), resumen.aLaFecha()),
                    new ImporteActualizado(resumen.reajuste(), resumen.aLaFecha()),
                    new ImporteActualizado(resumen.interes(), resumen.aLaFecha()),
                    new ImporteActualizado(resumen.gasto(), resumen.aLaFecha()),
                    new ImporteActualizado(resumen.total(), resumen.aLaFecha()),
                    resumen.estadoDeLaConsulta());
        }
    }

    /**
     * Una fila de «Deudas Pendientes»: una obligacion con su desglose a la fecha de corte.
     *
     * <p>{@code total} viene del propio {@link ObligacionPublica#total()} y no se recompone aqui:
     * es la suma de las cuatro partes en un solo sitio.
     */
    public record ObligacionDeLaFicha(
            String tributo,
            int ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            ImporteActualizado insoluto,
            ImporteActualizado reajuste,
            ImporteActualizado interes,
            ImporteActualizado gasto,
            ImporteActualizado total) {

        static ObligacionDeLaFicha de(ObligacionPublica obligacion) {
            return new ObligacionDeLaFicha(
                    obligacion.tributo(),
                    obligacion.ejercicio().valor(),
                    obligacion.predioId(),
                    obligacion.vehiculoId(),
                    new ImporteActualizado(obligacion.insoluto(), obligacion.fecha()),
                    new ImporteActualizado(obligacion.reajuste(), obligacion.fecha()),
                    new ImporteActualizado(obligacion.interes(), obligacion.fecha()),
                    new ImporteActualizado(obligacion.gasto(), obligacion.fecha()),
                    new ImporteActualizado(obligacion.total(), obligacion.fecha()));
        }
    }

    /**
     * Una fila de «Pagos Realizados» o de «Altas y Bajas»: la misma forma para las dos, porque son
     * la misma tabla filtrada distinto.
     *
     * <p>{@code monto} lleva la <b>fecha valor del asiento</b>, no la de la consulta: un pago de
     * marzo no se actualiza, y decir que esta actualizado a hoy seria mentir sobre una cifra que no
     * se ha movido.
     */
    public record MovimientoDeLaFicha(
            long id,
            int ejercicio,
            String tributo,
            String concepto,
            String tipo,
            String fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            ImporteActualizado monto,
            String documentoOrigen,
            @Nullable String motivo) {

        static MovimientoDeLaFicha de(MovimientoDelLibro movimiento) {
            return new MovimientoDeLaFicha(
                    movimiento.id(),
                    movimiento.ejercicio().valor(),
                    movimiento.tributo(),
                    movimiento.concepto(),
                    movimiento.tipo(),
                    movimiento.fase(),
                    movimiento.periodo(),
                    movimiento.predioId(),
                    movimiento.vehiculoId(),
                    new ImporteActualizado(movimiento.monto(), movimiento.fechaValor()),
                    movimiento.documentoOrigen(),
                    movimiento.motivo());
        }
    }

    /**
     * Una fila de «Fraccionamientos».
     *
     * <p>Las dos cifras llevan <b>fechas distintas</b> y por eso viajan como dos {@link
     * ImporteActualizado} separados: {@code deudaAcogida} a la fecha de corte del convenio y {@code
     * saldo} a la de la consulta. Es la fila que mejor enseña por que el importe y su fecha van
     * juntos en un tipo: aplanarlas dejaria dos cifras de dias distintos bajo la misma cabecera.
     */
    public record ConvenioDeLaFicha(
            String numero,
            String fecha,
            ImporteActualizado deudaAcogida,
            int cuotas,
            int pagadas,
            int vencidas,
            ImporteActualizado saldo,
            String estado,
            @Nullable String motivoDelCierre) {

        static ConvenioDeLaFicha de(ConvenioDelContribuyente convenio) {
            return new ConvenioDeLaFicha(
                    convenio.numero(),
                    convenio.fecha().toString(),
                    new ImporteActualizado(convenio.deudaAcogida(), convenio.fechaCorte()),
                    convenio.cuotas(),
                    convenio.pagadas(),
                    convenio.vencidas(),
                    new ImporteActualizado(convenio.saldo(), convenio.saldoA()),
                    convenio.estado(),
                    convenio.motivoDelCierre());
        }
    }

    /**
     * Una fila de «Valores».
     *
     * <p>Las cinco cifras van a {@code proyectadoA} —la fecha de la emision— y no a la de la
     * consulta: el desglose de un valor esta <b>congelado</b>, y reimprimirlo dos anios despues
     * devuelve los mismos importes. La {@code situacion}, en cambio, si depende del dia desde el
     * que se mira, y por eso lleva su propia {@code situacionA}.
     */
    public record ValorDeLaFicha(
            String tipo,
            String numero,
            int ejercicio,
            String fechaEmision,
            @Nullable String tributos,
            @Nullable String periodo,
            String situacion,
            String situacionA,
            ImporteActualizado insoluto,
            ImporteActualizado reajuste,
            ImporteActualizado interes,
            ImporteActualizado gasto,
            ImporteActualizado total) {

        static ValorDeLaFicha de(ValorDelContribuyente valor) {
            return new ValorDeLaFicha(
                    valor.tipo(),
                    valor.numero(),
                    valor.ejercicio().valor(),
                    valor.fechaEmision().toString(),
                    valor.tributos(),
                    valor.periodo(),
                    valor.situacion(),
                    valor.situacionA().toString(),
                    new ImporteActualizado(valor.insoluto(), valor.proyectadoA()),
                    new ImporteActualizado(valor.reajuste(), valor.proyectadoA()),
                    new ImporteActualizado(valor.interes(), valor.proyectadoA()),
                    new ImporteActualizado(valor.gasto(), valor.proyectadoA()),
                    new ImporteActualizado(valor.total(), valor.proyectadoA()));
        }
    }
}
