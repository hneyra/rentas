package kamayuk.rentas.verificaciones;

import java.util.Set;
import kamayuk.comun.verificaciones.contrato.ContratoConElConsumidorTestBase;
import org.junit.jupiter.api.DisplayName;

/**
 * {@code rentas} sigue aceptando lo que {@code caja} le manda (ADR-0026 §3, ADR-0030 §4).
 *
 * <p>Es la unica de las cuatro fronteras en que este repositorio es el <b>proveedor</b>, y la unica
 * de <b>escritura</b>: {@code caja} publica en {@code POST /pagos} el evento del cobro, tal como lo
 * congelo en la transaccion, y no lee la respuesta.
 *
 * <p>Lo que se comprueba es lo contrario que en una lectura: que {@code PeticionDePago} declare
 * cada campo que la caja manda. Un campo que no declare lo <b>descarta Jackson en silencio</b> y el
 * emisor recibe 201: el evento se marca ENTREGADO, el buzon se vacia y el dato no llego. No hay
 * reintento, porque para la caja la entrega salio bien.
 */
@DisplayName("Contrato con caja (rentas es el proveedor)")
class ContratoConCajaTest extends ContratoConElConsumidorTestBase {

    @Override
    protected String consumidor() {
        return "caja";
    }

    @Override
    protected String proveedor() {
        return "rentas";
    }

    /**
     * Lo que hoy no cuadra, medido y no supuesto.
     *
     * <p>Los dos son del evento de <b>anulacion</b>, y valen lo mismo: {@code
     * ComponedorDeEventosJson.pagoAnulado} escribe {@code motivo} y {@code fecha} y {@code
     * PeticionDePago} no los declara, asi que Jackson los descarta y la caja recibe 201. El motivo
     * de una anulacion es el dato con el que se explica por que una deuda volvio a estar viva, y
     * hoy no llega — sobrevive solo dentro de la columna {@code cuerpo} de {@code pago_recibido},
     * que es jsonb y que ninguna lectura tipada mira.
     *
     * <p>No se arregla aqui, y hay que decir por que: anadir los dos componentes al {@code record}
     * es una linea, pero decidir <b>que hace `rentas` con ellos</b> no lo es —{@code
     * pago_recibido.motivo} existe y su {@code CHECK} lo exige solo cuando el estado es RECHAZADO,
     * asi que escribir ahi el motivo de la caja cambiaria lo que esa columna significa—. Se
     * registra con nombre y se cierra decidiendolo, no de lado.
     */
    @Override
    protected Set<String> desajustesVivos() {
        return Set.of(
                "POST /pagos: falta el campo «(el cuerpo).fecha», que el consumidor manda. Este"
                        + " endpoint declara [actualizadoA, ordenes, pagador, pagoId, pagoOriginalId,"
                        + " recibo, sistemaOrigen, tipo, total].",
                "POST /pagos: falta el campo «(el cuerpo).motivo», que el consumidor manda. Este"
                        + " endpoint declara [actualizadoA, ordenes, pagador, pagoId, pagoOriginalId,"
                        + " recibo, sistemaOrigen, tipo, total].");
    }
}
