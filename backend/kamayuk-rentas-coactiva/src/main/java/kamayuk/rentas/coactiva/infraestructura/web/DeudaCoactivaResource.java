package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.rentas.BeneficioRegistrado;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de {@code coactiva_consulta_deudas} y, con su beneficio, de {@code
 * coactiva_deudas_beneficio} (#42, RF-107).
 *
 * <p><b>Una sola fecha para todas las cifras, y visible</b> (regla 9, RNF-075). {@code deudaS},
 * {@code costasS} y {@code totalS} estan calculadas al mismo dia, {@code aLaFecha}, y ese dia sale
 * en la respuesta. Sin el, la pantalla tendria que suponer que es hoy, y una consulta guardada o
 * reimpresa mañana diria otra cosa bajo la misma etiqueta.
 *
 * <p><b>El total no lo recompone la interfaz.</b> Viaja calculado —{@code deudaS + costasS}— porque
 * sumar en la pantalla es exactamente lo que RNF-083 prohibe: dos sitios que suman acaban sumando
 * distinto.
 *
 * @param expediente el numero impreso
 * @param ano el ejercicio del expediente
 * @param codContribuyente el codigo del obligado
 * @param contribuyente su nombre
 * @param tributos los tributos que el expediente agrupa
 * @param deudaS la deuda materia de cobranza, sin costas
 * @param costasS las costas del procedimiento liquidadas y pendientes
 * @param totalS la suma de las dos
 * @param aLaFecha el dia al que estan las tres cifras
 * @param estado en que punto esta el procedimiento
 * @param ultimaActuacion el ultimo acto dictado, si hubo alguno
 * @param beneficios los beneficios registrados y vigentes; solo en la consulta de beneficio
 */
public record DeudaCoactivaResource(
        String expediente,
        int ano,
        String codContribuyente,
        String contribuyente,
        List<String> tributos,
        String deudaS,
        String costasS,
        String totalS,
        LocalDate aLaFecha,
        String estado,
        @Nullable ActuacionResource ultimaActuacion,
        @Nullable List<BeneficioResource> beneficios) {

    /** La fila de {@code coactiva_consulta_deudas}. */
    public static DeudaCoactivaResource de(
            ConsultaDeDeudasCoactivas.DeudaEnCoactiva fila, String codigo, String nombre) {
        return construir(fila, codigo, nombre, null);
    }

    /**
     * La fila de {@code coactiva_deudas_beneficio}: la misma deuda y los beneficios registrados.
     *
     * <p><b>Sin ninguna cifra «con beneficio»</b>, y el {@code Resource} no tiene donde ponerla: el
     * efecto de un beneficio sobre el importe es D-02b (#191). Lo que viaja es lo que la norma
     * declara —el porcentaje o el monto del beneficio, con su base legal—, no un descuento
     * aplicado. Una consulta que devolviera un total rebajado se imprime y se entrega en
     * ventanilla.
     */
    public static DeudaCoactivaResource de(
            ConsultaDeDeudasCoactivas.DeudaConBeneficio fila, String codigo, String nombre) {
        List<BeneficioResource> beneficios = new ArrayList<>(fila.beneficios().size());
        for (BeneficioRegistrado beneficio : fila.beneficios()) {
            beneficios.add(BeneficioResource.de(beneficio));
        }
        return construir(fila.deuda(), codigo, nombre, beneficios);
    }

    private static DeudaCoactivaResource construir(
            ConsultaDeDeudasCoactivas.DeudaEnCoactiva fila,
            String codigo,
            String nombre,
            @Nullable List<BeneficioResource> beneficios) {

        ActoCoactivo ultimo = fila.ultimaActuacion();
        return new DeudaCoactivaResource(
                fila.expediente().numero(),
                fila.expediente().ejercicio().valor(),
                codigo,
                nombre,
                fila.tributos(),
                fila.deuda().materiaDeCobranza().valor().toPlainString(),
                fila.deuda().costas().valor().toPlainString(),
                fila.deuda().total().valor().toPlainString(),
                fila.aLaFecha(),
                fila.estado().etiqueta(),
                ultimo == null ? null : ActuacionResource.de(ultimo),
                beneficios);
    }

    /** El ultimo acto dictado en el expediente. */
    public record ActuacionResource(String acto, String numero, LocalDate fecha) {

        static ActuacionResource de(ActoCoactivo acto) {
            return new ActuacionResource(acto.tipo().titulo(), acto.numero(), acto.fecha());
        }
    }

    /**
     * Un beneficio registrado, tal como la norma lo declara.
     *
     * <p>{@code porcentajeDeclarado} y {@code montoDeclarado} <b>no son un descuento calculado</b>:
     * son lo que la ordenanza dice, transcrito al registrar el beneficio. Sobre que base se aplican
     * y en que orden es D-02b, y por eso esta fila no lleva ninguna cifra rebajada.
     */
    public record BeneficioResource(
            String tipo,
            String clase,
            String tributo,
            @Nullable String porcentajeDeclarado,
            @Nullable String montoDeclarado,
            String baseLegal,
            LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String efectoSobreElImporte) {

        /** Lo que el sistema puede decir hoy del efecto: que no lo calcula, y por que. */
        private static final String EFECTO_NO_CALCULADO =
                "No calculado: sobre que parte de la deuda se aplica el beneficio, en que orden y"
                        + " con que redondeo es D-02b (#191). El importe que se cobra es el que se"
                        + " debe.";

        static BeneficioResource de(BeneficioRegistrado beneficio) {
            return new BeneficioResource(
                    beneficio.tipo(),
                    beneficio.clase(),
                    beneficio.tributo(),
                    beneficio.porcentajeDeclarado() == null
                            ? null
                            : beneficio.porcentajeDeclarado().valor().toPlainString(),
                    beneficio.montoDeclarado() == null
                            ? null
                            : beneficio.montoDeclarado().valor().toPlainString(),
                    beneficio.baseLegal(),
                    beneficio.vigenciaDesde(),
                    beneficio.vigenciaHasta(),
                    EFECTO_NO_CALCULADO);
        }
    }
}
