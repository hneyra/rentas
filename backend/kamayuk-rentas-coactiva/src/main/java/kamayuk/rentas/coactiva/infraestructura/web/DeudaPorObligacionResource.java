package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.dominio.ObligacionDelExpediente;
import org.jspecify.annotations.Nullable;

/**
 * La deuda de un expediente coactivo <b>obligación por obligación</b>, tal como sale por HTTP
 * (#426, RF-105).
 *
 * <h2>Una sola fecha, y visible</h2>
 *
 * <p>{@code aLaFecha} va en la cabecera y no en cada línea: las cifras de todas las filas están
 * calculadas al <b>mismo</b> día, y repetirlo por fila invitaría a que un día no lo estuvieran
 * (regla 9, RNF-075).
 *
 * <h2>Los tres totales viajan calculados</h2>
 *
 * <p>{@code deudaMateriaDeCobranzaS}, {@code costasS} y {@code totalS} salen del servidor, y no de
 * sumar las filas en la pantalla. Es lo que RNF-083 prohíbe: dos sitios que suman acaban sumando
 * distinto, y aquí el que manda es el mismo que compone la REC-2.
 *
 * @param expediente el número impreso
 * @param codContribuyente el código del obligado
 * @param contribuyente su nombre
 * @param estado en qué punto está el procedimiento
 * @param aLaFecha el día al que están todas las cifras
 * @param obligaciones una fila por obligación, sin sumar
 * @param deudaMateriaDeCobranzaS la suma de las obligaciones que no son costas
 * @param costasS lo que suman las costas del procedimiento
 * @param totalS la suma de las dos
 */
public record DeudaPorObligacionResource(
        String expediente,
        String codContribuyente,
        String contribuyente,
        String estado,
        LocalDate aLaFecha,
        List<LineaDeDeudaResource> obligaciones,
        String deudaMateriaDeCobranzaS,
        String costasS,
        String totalS) {

    public static DeudaPorObligacionResource de(
            ConsultaDeExpedientes.DeudaPorObligacion deuda, String codigo, String nombre) {

        List<LineaDeDeudaResource> lineas = new ArrayList<>(deuda.obligaciones().size());
        for (ObligacionDelExpediente obligacion : deuda.obligaciones()) {
            lineas.add(LineaDeDeudaResource.de(obligacion));
        }

        return new DeudaPorObligacionResource(
                deuda.expediente().numero(),
                codigo,
                nombre,
                deuda.estado().etiqueta(),
                deuda.aLaFecha(),
                lineas,
                deuda.total().materiaDeCobranza().valor().toPlainString(),
                deuda.total().costas().valor().toPlainString(),
                deuda.total().total().valor().toPlainString());
    }

    /**
     * Una obligación del expediente, con lo que hace falta para <b>elegirla</b>.
     *
     * <p>{@code tributo}, {@code ejercicio} y {@code predioId}/{@code vehiculoId} son exactamente
     * los cuatro campos de {@code PeticionDeConvenioCoactivo.PeticionDeObligacionAcogida}: la fila
     * que se marca en la grilla es la que viaja en el cuerpo, sin que nadie la recomponga.
     *
     * <p>{@code esCosta} viaja marcada y no se esconde: una costa se cobra igual, pero no se acoge
     * a un fraccionamiento como una cuota más del predial, y quien lee la grilla tiene que poder
     * distinguirla.
     */
    public record LineaDeDeudaResource(
            String tributo,
            int ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            boolean esCosta,
            String insolutoS,
            String reajusteS,
            String interesS,
            String gastosS,
            String totalS) {

        static LineaDeDeudaResource de(ObligacionDelExpediente obligacion) {
            return new LineaDeDeudaResource(
                    obligacion.tributo(),
                    obligacion.ejercicio().valor(),
                    obligacion.predioId(),
                    obligacion.vehiculoId(),
                    obligacion.esCosta(),
                    obligacion.insoluto().valor().toPlainString(),
                    obligacion.reajuste().valor().toPlainString(),
                    obligacion.interes().valor().toPlainString(),
                    obligacion.gasto().valor().toPlainString(),
                    obligacion.total().valor().toPlainString());
        }
    }
}
