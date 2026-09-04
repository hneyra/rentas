package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeCostas;
import kamayuk.rentas.coactiva.dominio.CostaLiquidada;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import org.jspecify.annotations.Nullable;

/**
 * La liquidacion de costas como la ve la interfaz (#42, RF-104).
 *
 * <p><b>Cada cifra con su fecha</b> (regla 9, RNF-075). {@code fecha} dice de cuando es {@code
 * totalS} —congelado el dia de la liquidacion— y {@code aLaFecha} dice a que dia esta {@code
 * pendienteS}, que depende de lo que el libro haya recibido entretanto. Son dos fechas distintas y
 * las dos viajan: bajo una sola, una liquidacion de marzo pareceria calculada hoy.
 *
 * <p>Los importes salen como texto en su representacion decimal, como en el resto de la API: un
 * numero JSON pasa por coma flotante en cualquier cliente, y un centimo perdido en el transporte es
 * una conciliacion que no cuadra (regla 1, RNF-055).
 *
 * <p><b>El estado no sale de ninguna columna</b>: se deriva del pendiente, y por eso viaja junto a
 * el y a su fecha.
 *
 * @param nroLiquidacion el numero impreso
 * @param expedCoact el numero impreso del expediente
 * @param ejercicio el ejercicio de la liquidacion
 * @param fecha el dia de la liquidacion
 * @param tributo la obligacion del libro a la que se imputo
 * @param totalS lo liquidado, congelado a {@code fecha}
 * @param pendienteS lo que queda por cobrar de esa obligacion a {@code aLaFecha}
 * @param aLaFecha la fecha a la que se respondio {@code pendienteS}
 * @param estado derivado del pendiente: ACTIVA o CANCELADA
 * @param conjuntoDeParametros de que conjunto sellado salieron los aranceles (ARQ-09 §3)
 * @param observacion por que se liquido
 * @param usuarioRegistro quien la registro
 * @param costas el detalle, una linea por acto liquidado
 */
public record LiquidacionResource(
        String nroLiquidacion,
        String expedCoact,
        int ejercicio,
        LocalDate fecha,
        String tributo,
        String totalS,
        @Nullable String pendienteS,
        @Nullable LocalDate aLaFecha,
        @Nullable String estado,
        long conjuntoDeParametros,
        String observacion,
        @Nullable String usuarioRegistro,
        List<CostaResource> costas) {

    /**
     * La liquidacion recien registrada: todavia sin la parte que depende de la fecha de consulta.
     */
    public static LiquidacionResource de(
            LiquidacionDeCostas liquidacion, String numeroDeExpediente) {
        return construir(liquidacion, numeroDeExpediente, null, null, null);
    }

    /** La fila de la grilla, con su pendiente y su estado a la fecha consultada. */
    public static LiquidacionResource de(ConsultaDeCostas.LiquidacionEnConsulta fila) {
        return construir(
                fila.liquidacion(),
                fila.numeroDeExpediente(),
                fila.pendiente().valor().toPlainString(),
                fila.aLaFecha(),
                fila.estado().name());
    }

    private static LiquidacionResource construir(
            LiquidacionDeCostas liquidacion,
            String numeroDeExpediente,
            @Nullable String pendiente,
            @Nullable LocalDate aLaFecha,
            @Nullable String estado) {

        List<CostaResource> lineas = new ArrayList<>(liquidacion.costas().size());
        for (CostaLiquidada costa : liquidacion.costas()) {
            lineas.add(CostaResource.de(costa));
        }
        return new LiquidacionResource(
                liquidacion.numero(),
                numeroDeExpediente,
                liquidacion.ejercicio().valor(),
                liquidacion.fecha(),
                liquidacion.tributo(),
                liquidacion.total().valor().toPlainString(),
                pendiente,
                aLaFecha,
                estado,
                liquidacion.conjuntoId(),
                liquidacion.observacion().texto(),
                liquidacion.usuarioRegistro(),
                lineas);
    }

    /**
     * Una linea del detalle.
     *
     * <p>{@code arancelFuente} viaja porque es lo que explica la cifra: sin el, la pantalla
     * mostraria un importe que nadie puede justificar. Es la misma razon por la que {@code
     * conjuntoDeParametros} viaja en la cabecera.
     *
     * @param actoId el acto tarifado
     * @param acto el tipo del acto
     * @param descripcion la glosa impresa
     * @param montoS lo que el arancel dice para ese acto
     * @param arancelFuente la llave del parametro sellado y su documento fuente
     */
    public record CostaResource(
            long actoId, String acto, String descripcion, String montoS, String arancelFuente) {

        static CostaResource de(CostaLiquidada costa) {
            return new CostaResource(
                    costa.actoId(),
                    costa.actoTipo().name(),
                    costa.concepto(),
                    costa.monto().valor().toPlainString(),
                    costa.arancelFuente());
        }
    }
}
