package kamayuk.rentas.cuentacorriente.dominio;

/**
 * Con que granularidad se pide el listado de deuda de un contribuyente (#551).
 *
 * <p>No es una preferencia de presentacion: cambia <b>que es una fila</b>, y con ella lo que la
 * pantalla puede hacer con lo que lee. Una fila que agrega varios periodos dice cuanto se debe por
 * el conjunto y no cuanto por cada cuota, asi que ninguna baja se puede componer desde ella —{@code
 * POST /rentas/deuda/bajas} extingue <b>una</b> {@link ClaveDeSaldo}, que lleva su periodo—.
 *
 * <p>Las dos formas siguen siendo la misma cuenta: la deuda de cada periodo la da {@link
 * CalculoDeDeuda#deudaPorPeriodoA} y la del conjunto {@link CalculoDeDeuda#deudaActualizadaA} sobre
 * los asientos de todos ellos. Lo que cambia es donde se corta, no como se suma.
 */
public enum Agregacion {

    /**
     * Una fila por obligacion —tributo, ejercicio y unidad—, con los periodos agregados.
     *
     * <p>Es lo que la ventanilla lee para cobrar: el cajero marca «predial 2026 del predio 7», no
     * cuota por cuota, y {@code POST /tesoreria/caja/cobranza} pide exactamente esa clave. {@code
     * periodoDesde} y {@code periodoHasta} acotan lo que la fila agrego.
     */
    POR_OBLIGACION,

    /**
     * Una fila por <b>cuota</b>, con su propio desglose en cuatro partes y su fecha.
     *
     * <p>{@code periodoDesde == periodoHasta} en cada fila, que es lo que la distingue de la otra
     * forma sin cambiar la forma del recurso. Es lo que hace expresable la baja de «predial 2016,
     * cuotas 1 a 4»: cuota a cuota, con el importe que cada una debe y sin que la interfaz reparta
     * ninguna cifra (RNF-083).
     */
    POR_PERIODO
}
