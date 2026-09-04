package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import org.jspecify.annotations.Nullable;

/**
 * El convenio como lo ve la interfaz (#35, RF-084).
 *
 * <p><b>Cada cifra con su fecha</b> (regla 9, RNF-075). {@code fechaCorte} dice a que fecha esta
 * {@code montoTotal}, y {@code aLaFecha} a que fecha estan el saldo y las cuotas vencidas. Son dos
 * fechas distintas —una es del dia del convenio y la otra del dia de la consulta— y las dos viajan:
 * bajo una sola, un convenio de marzo pareceria calculado hoy.
 *
 * <p>Los importes salen como texto en su representacion decimal, como en el resto de la API: un
 * numero JSON pasa por coma flotante en cualquier cliente, y un centimo perdido en el transporte es
 * una conciliacion que no cuadra (regla 1, RNF-055).
 */
public record ConvenioResource(
        String numero,
        String codContribuyente,
        String tipo,
        String estado,
        LocalDate fecha,
        LocalDate fechaCorte,
        String montoTotal,
        String cuotaInicial,
        int nroDeCuotas,
        String totalDelCronograma,
        String interesDeFraccionamientoMensual,
        long conjuntoDeParametros,
        @Nullable String tipoDeGarantia,
        @Nullable String detalleDelOfrecimiento,
        @Nullable String resolucion,
        @Nullable String convenioDeOrigen,
        List<CuotaResource> cuotas,
        List<DeudaAcogidaResource> deudaOriginal,
        List<MovimientoResource> movimientos,
        @Nullable LocalDate aLaFecha,
        @Nullable String saldo,
        @Nullable Integer cuotasPagadas,
        @Nullable Integer cuotasVencidas) {

    /** El convenio recien registrado o formalizado, sin la parte que depende de hoy. */
    public static ConvenioResource de(Convenio convenio, String codContribuyente, String estado) {
        return construir(convenio, codContribuyente, estado, List.of(), null, null, null, null);
    }

    /** La ficha completa: el convenio, su estado y lo que le ha pasado. */
    public static ConvenioResource de(ConsultaDeConvenios.Ficha ficha, String codContribuyente) {
        return construir(
                ficha.convenio(),
                codContribuyente,
                ficha.estado().name(),
                ficha.movimientos(),
                ficha.aLaFecha(),
                ficha.saldoDelCronograma().valor().toPlainString(),
                ficha.cuotasPagadas(),
                ficha.cuotasVencidas());
    }

    private static ConvenioResource construir(
            Convenio convenio,
            String codContribuyente,
            String estado,
            List<MovimientoDeConvenio> movimientos,
            @Nullable LocalDate aLaFecha,
            @Nullable String saldo,
            @Nullable Integer pagadas,
            @Nullable Integer vencidas) {

        List<CuotaResource> cuotas = new ArrayList<>();
        for (CuotaDeConvenio cuota : convenio.cronograma()) {
            cuotas.add(CuotaResource.de(cuota));
        }
        List<DeudaAcogidaResource> original = new ArrayList<>();
        for (DeudaAcogida acogida : convenio.acogida()) {
            original.add(DeudaAcogidaResource.de(acogida));
        }
        List<MovimientoResource> historia = new ArrayList<>();
        for (MovimientoDeConvenio movimiento : movimientos) {
            historia.add(MovimientoResource.de(movimiento));
        }

        return new ConvenioResource(
                convenio.numero().impreso(),
                codContribuyente,
                convenio.tipo().name(),
                estado,
                convenio.fecha(),
                convenio.fechaCorte(),
                convenio.montoTotal().valor().toPlainString(),
                convenio.cuotaInicial().valor().toPlainString(),
                convenio.numeroDeCuotas(),
                convenio.totalDelCronograma().valor().toPlainString(),
                convenio.condiciones().interesMensual().valor().toPlainString(),
                convenio.condiciones().conjuntoId(),
                convenio.tipoGarantia() == null ? null : convenio.tipoGarantia().name(),
                convenio.detalleGarantia(),
                convenio.resolucion(),
                convenio.convenioOrigenId() == null
                        ? null
                        : String.valueOf(convenio.convenioOrigenId()),
                cuotas,
                original,
                historia,
                aLaFecha,
                saldo,
                pagadas,
                vencidas);
    }

    /** Una fila del cronograma. */
    public record CuotaResource(
            int nro,
            LocalDate vencimiento,
            String cuota,
            String capital,
            String interes,
            String gasto) {

        static CuotaResource de(CuotaDeConvenio cuota) {
            return new CuotaResource(
                    cuota.numero(),
                    cuota.vencimiento(),
                    cuota.monto().valor().toPlainString(),
                    cuota.capital().valor().toPlainString(),
                    cuota.interes().valor().toPlainString(),
                    cuota.gasto().valor().toPlainString());
        }
    }

    /**
     * Una fila de la deuda original acogida, con la fase de la que salio.
     *
     * <p>La fase viaja porque es lo que explica a donde vuelve si el convenio se quiebra, y porque
     * la pantalla tiene que poder decir «esto venia de coactiva» sin consultar otra cosa.
     */
    public record DeudaAcogidaResource(
            String tributo,
            int ejercicio,
            int periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String faseOrigen,
            LocalDate aLaFecha,
            String insoluto,
            String reajuste,
            String interes,
            String gasto,
            String total) {

        static DeudaAcogidaResource de(DeudaAcogida acogida) {
            return new DeudaAcogidaResource(
                    acogida.tributo(),
                    acogida.ejercicio().valor(),
                    acogida.periodo(),
                    acogida.predioId(),
                    acogida.vehiculoId(),
                    acogida.faseOrigen(),
                    acogida.fecha(),
                    acogida.insoluto().valor().toPlainString(),
                    acogida.reajuste().valor().toPlainString(),
                    acogida.interes().valor().toPlainString(),
                    acogida.gasto().valor().toPlainString(),
                    acogida.total().valor().toPlainString());
        }
    }

    /** Un acto sobre el convenio: su formalizacion o su cierre. */
    public record MovimientoResource(
            String tipo,
            LocalDate fecha,
            @Nullable String motivo,
            @Nullable String autorizadoPor,
            @Nullable String documentoAutorizacion,
            String importe,
            int asientos,
            @Nullable String usuarioRegistro) {

        static MovimientoResource de(MovimientoDeConvenio movimiento) {
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.fecha(),
                    movimiento.motivo(),
                    movimiento.autorizadoPor(),
                    movimiento.documentoAutorizacion(),
                    movimiento.importe().valor().toPlainString(),
                    movimiento.asientos(),
                    movimiento.usuarioRegistro());
        }
    }

    /**
     * Una fila del listado de convenios.
     *
     * <p>Ligera a proposito: una pagina de veinte filas no puede costar veinte lecturas de detalle.
     * Quien abra un convenio lo pide por su numero.
     */
    public record FilaResource(
            String nroConvenio,
            String contribuyente,
            LocalDate fecha,
            LocalDate fechaCorte,
            String deudaAcogidaS,
            int cuotas,
            int pagadas,
            int vencidas,
            String saldoS,
            LocalDate saldoALaFecha,
            String estado,
            @Nullable String motivo,
            @Nullable List<CuotaResource> cronograma,
            @Nullable List<DeudaAcogidaResource> deudaOriginal,
            @Nullable List<MovimientoResource> movimientos) {

        /** La fila de la grilla: lo que la pantalla pinta y nada mas. */
        public static FilaResource de(ConvenioEnConsulta fila) {
            return construir(fila, null, null, null);
        }

        /**
         * La misma fila con su detalle: el cronograma, la deuda original y lo que le ha pasado.
         *
         * <p>Solo cuando la consulta apunta a <b>un</b> convenio. Cargarlo en cada fila haria que
         * una pagina de veinte costara veinte lecturas de detalle, y la grilla no lo pinta.
         */
        public static FilaResource de(ConvenioEnConsulta fila, ConsultaDeConvenios.Ficha ficha) {
            List<CuotaResource> cuotas = new ArrayList<>();
            for (CuotaDeConvenio cuota : ficha.convenio().cronograma()) {
                cuotas.add(CuotaResource.de(cuota));
            }
            List<DeudaAcogidaResource> original = new ArrayList<>();
            for (DeudaAcogida acogida : ficha.convenio().acogida()) {
                original.add(DeudaAcogidaResource.de(acogida));
            }
            List<MovimientoResource> historia = new ArrayList<>();
            for (MovimientoDeConvenio movimiento : ficha.movimientos()) {
                historia.add(MovimientoResource.de(movimiento));
            }
            return construir(fila, cuotas, original, historia);
        }

        private static FilaResource construir(
                ConvenioEnConsulta fila,
                @Nullable List<CuotaResource> cronograma,
                @Nullable List<DeudaAcogidaResource> deudaOriginal,
                @Nullable List<MovimientoResource> movimientos) {
            return new FilaResource(
                    fila.numero().impreso(),
                    fila.codigoContribuyente(),
                    fila.fecha(),
                    fila.fechaCorte(),
                    fila.deudaAcogida().valor().toPlainString(),
                    fila.cuotas(),
                    fila.pagadas(),
                    fila.vencidas(),
                    fila.saldo().valor().toPlainString(),
                    fila.saldoA(),
                    fila.estado().name(),
                    fila.motivoDelCierre(),
                    cronograma,
                    deudaOriginal,
                    movimientos);
        }
    }

    /**
     * La simulacion: el cronograma que saldria, sin registrar nada.
     *
     * <p>No lleva numero de convenio, y eso es la mitad del punto: una simulacion no consume un
     * correlativo. Si lo llevara, la pantalla podria imprimir un papel con un numero que no existe.
     */
    public record SimulacionResource(
            String montoTotal,
            LocalDate aLaFecha,
            String cuotaInicial,
            int nroDeCuotas,
            String totalDelCronograma,
            String interesDeFraccionamientoMensual,
            List<CuotaResource> cuotas,
            List<DeudaAcogidaResource> deudaOriginal) {

        public static SimulacionResource de(RegistrarPreconvenio.Simulacion simulacion) {
            List<CuotaResource> cuotas = new ArrayList<>();
            for (CuotaDeConvenio cuota : simulacion.cronograma()) {
                cuotas.add(CuotaResource.de(cuota));
            }
            List<DeudaAcogidaResource> original = new ArrayList<>();
            for (DeudaAcogida acogida : simulacion.acogible()) {
                original.add(DeudaAcogidaResource.de(acogida));
            }
            int cuantas =
                    (int) simulacion.cronograma().stream().filter(c -> !c.esInicial()).count();
            return new SimulacionResource(
                    simulacion.total().valor().toPlainString(),
                    simulacion.aLaFecha(),
                    kamayuk.rentas.tesoreria.dominio.Cronograma.inicialDe(simulacion.cronograma())
                            .valor()
                            .toPlainString(),
                    cuantas,
                    kamayuk.rentas.tesoreria.dominio.Cronograma.total(simulacion.cronograma())
                            .valor()
                            .toPlainString(),
                    simulacion.condiciones().interesMensual().valor().toPlainString(),
                    cuotas,
                    original);
        }
    }
}
