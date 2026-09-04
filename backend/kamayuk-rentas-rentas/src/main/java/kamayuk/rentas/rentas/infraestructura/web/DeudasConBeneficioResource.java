package kamayuk.rentas.rentas.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.rentas.aplicacion.SimularAcogimiento;
import kamayuk.rentas.rentas.dominio.beneficios.AcogimientoSimulado;
import kamayuk.rentas.rentas.dominio.beneficios.CampaniaDeBeneficio;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve {@code consulta_deudas_beneficio}, tal como sale por HTTP (#72, RF-107). Campos
 * en español {@code camelCase} (ARQ-04 §3).
 *
 * <h2>Ninguna cifra sin su fecha</h2>
 *
 * <p>Todas viajan como {@link ImporteActualizado} —nunca como {@code Dinero} suelto— y todas llevan
 * la <b>misma</b> fecha: la de corte de la consulta. Aqui si es la misma para todas, a diferencia
 * de la ficha unificada, y por un motivo que se puede decir: no hay ni un importe congelado en esta
 * respuesta. Es una simulacion de hoy sobre una deuda calculada a hoy; manana las dos cifras son
 * otras, y por eso la fecha va pegada a cada una (regla 9, RNF-075).
 *
 * <h2>El descuento puede no estar, y entonces no esta</h2>
 *
 * <p>{@code simulacion} sale en {@code null} cuando no hay campana elegida —o cuando la elegida no
 * la publica ningun conjunto sellado, que entonces es un 422 y no un cuerpo—. <b>No sale con
 * ceros</b>: «se ahorraria 0,00» es una afirmacion sobre una campana, y sin campana no hay ninguna
 * que hacer. La interfaz dibuja «—», que no es cero.
 *
 * @param contribuyente de quien es la deuda
 * @param aLaFecha la fecha de corte de todas las cifras
 * @param deudaTotal toda la deuda del contribuyente, sin acotar por los filtros
 * @param deudaAcogida la parte que los filtros seleccionaron
 * @param registrosAcogidos cuantas obligaciones entran en lo acogido
 * @param simulacion lo que la campana produce; nulo si no se eligio ninguna
 * @param campaniasAplicables las campanas que el conjunto sellado publica; vacia si no hay ninguna
 * @param estadoDeLaSimulacion la frase que explica lo anterior, redactada por el servidor
 * @param obligaciones la pagina de obligaciones acogidas
 */
public record DeudasConBeneficioResource(
        ContribuyenteDeLaSimulacion contribuyente,
        String aLaFecha,
        ImporteActualizado deudaTotal,
        ImporteActualizado deudaAcogida,
        int registrosAcogidos,
        @Nullable SimulacionDelBeneficio simulacion,
        List<CampaniaAplicable> campaniasAplicables,
        String estadoDeLaSimulacion,
        RespuestaPaginada<ObligacionAcogidaResource> obligaciones) {

    public static DeudasConBeneficioResource de(SimularAcogimiento.Simulacion simulacion) {
        LocalDate fecha = simulacion.aLaFecha();
        return new DeudasConBeneficioResource(
                new ContribuyenteDeLaSimulacion(
                        simulacion.contribuyente().codigo(),
                        simulacion.contribuyente().nombre(),
                        simulacion.contribuyente().documento(),
                        simulacion.domicilioFiscal()),
                fecha.toString(),
                new ImporteActualizado(simulacion.deudaTotal(), fecha),
                new ImporteActualizado(simulacion.deudaAcogida(), fecha),
                simulacion.registrosAcogidos(),
                SimulacionDelBeneficio.de(simulacion.campania(), simulacion.acogimiento(), fecha),
                simulacion.campaniasPublicadas().stream().map(CampaniaAplicable::de).toList(),
                simulacion.estadoDeLaSimulacion(),
                RespuestaPaginada.de(simulacion.obligaciones(), ObligacionAcogidaResource::de));
    }

    /**
     * La cabecera: quien es y donde se le notifica.
     *
     * <p>El domicilio es el <b>vigente a la fecha de corte</b>, no el ultimo (regla 9): quien mudo
     * en setiembre no cambia la direccion a la que se le notifico en marzo.
     */
    public record ContribuyenteDeLaSimulacion(
            String codigo, String nombre, String documento, @Nullable String domicilioFiscal) {}

    /**
     * Lo que produce el acogimiento, cuando hay campana elegida.
     *
     * <p>{@code alicuotaAplicada} se llama asi y no «tasa» (regla 8): una tasa es un tipo de
     * tributo. La pantalla la rotula «Tasa aplicada (%)» porque asi la rotula el manual, y ese
     * rotulo se respeta (RNF-080) sin arrastrar el nombre al contrato.
     *
     * @param campania la campana simulada, tal como la nombra el conjunto sellado
     * @param alicuotaAplicada el porcentaje que descuenta, en tanto por ciento
     * @param baseDelBeneficio sobre que parte de lo acogido corre el descuento
     * @param baseDelBeneficioImporte cuanto suma esa parte
     * @param ahorro cuanto se descontaria
     * @param deudaConBeneficio lo que quedaria por pagar
     */
    public record SimulacionDelBeneficio(
            String campania,
            String alicuotaAplicada,
            String baseDelBeneficio,
            ImporteActualizado baseDelBeneficioImporte,
            ImporteActualizado ahorro,
            ImporteActualizado deudaConBeneficio) {

        static @Nullable SimulacionDelBeneficio de(
                @Nullable CampaniaDeBeneficio campania,
                @Nullable AcogimientoSimulado acogimiento,
                LocalDate fecha) {
            if (campania == null || acogimiento == null) {
                return null;
            }
            return new SimulacionDelBeneficio(
                    campania.nombre(),
                    campania.alicuota().valor().toPlainString(),
                    campania.base().name(),
                    new ImporteActualizado(acogimiento.baseDelBeneficio(), fecha),
                    new ImporteActualizado(acogimiento.ahorro(), fecha),
                    new ImporteActualizado(acogimiento.deudaConBeneficio(), fecha));
        }
    }

    /**
     * Una campana a la que se puede simular el acogimiento.
     *
     * <p>Sale del conjunto sellado, no de un {@code enum}: el desplegable de la pantalla lista lo
     * que la ordenanza publica en <b>esta</b> municipalidad. Vacia mientras no haya ninguna.
     */
    public record CampaniaAplicable(String nombre, String alicuota, String base) {

        static CampaniaAplicable de(CampaniaDeBeneficio campania) {
            return new CampaniaAplicable(
                    campania.nombre(),
                    campania.alicuota().valor().toPlainString(),
                    campania.base().name());
        }
    }

    /**
     * Una obligacion de las acogidas, con su desglose a la fecha de corte.
     *
     * <p>Es la misma forma que publica {@code ObligacionDeLaFicha} de la consulta unificada —el
     * desglose plano, no anidado— porque sale del mismo puerto, {@code ConsultaDeDeudaPublica}.
     *
     * <p><b>Sin fase y sin periodo</b>, y no por olvido: {@code ObligacionPublica} no los publica.
     * El puerto entrega el desglose que otro contexto necesita para formalizar deuda, no la fila de
     * una rejilla de cobranza. Quien necesite la fase y la cuota tiene {@code GET
     * /consultas/deuda}, que las publica porque vive dentro de {@code cuentacorriente}.
     */
    public record ObligacionAcogidaResource(
            String tributo,
            int ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            ImporteActualizado insoluto,
            ImporteActualizado reajuste,
            ImporteActualizado interes,
            ImporteActualizado gasto,
            ImporteActualizado total) {

        static ObligacionAcogidaResource de(ObligacionPublica obligacion) {
            LocalDate fecha = obligacion.fecha();
            return new ObligacionAcogidaResource(
                    obligacion.tributo(),
                    obligacion.ejercicio().valor(),
                    obligacion.predioId(),
                    obligacion.vehiculoId(),
                    new ImporteActualizado(obligacion.insoluto(), fecha),
                    new ImporteActualizado(obligacion.reajuste(), fecha),
                    new ImporteActualizado(obligacion.interes(), fecha),
                    new ImporteActualizado(obligacion.gasto(), fecha),
                    new ImporteActualizado(obligacion.total(), fecha));
        }
    }
}
