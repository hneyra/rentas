package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.nucleo.aplicacion.DeterminarPredialMasivo;

/**
 * Lo que hizo una corrida de emision anual, tal como sale por {@code predial_masivo} ({@code POST
 * /api/v1/rentas/predial/calculo-masivo}, #395).
 *
 * <p>La pantalla la dibuja como una tabla de etapas y un boton «Ver observados». Las dos cosas
 * viajan aqui: {@link #etapas} ya redactadas —cuantos registros, cuanto monto, cuantos observados y
 * en que estado quedo cada una— y {@link #observados} con el <b>motivo</b> de cada uno, que es lo
 * unico que convierte «emitio menos de lo esperado» en una lista de cosas que arreglar.
 *
 * <p>{@link #conjunto} dice con que parametros sellados se emitio. Una corrida sin el no se puede
 * repetir dentro de diez anios y dar lo mismo (ARQ-09 §3).
 *
 * @param ejercicio el ejercicio recalculado
 * @param alcance TODOS o SECTOR
 * @param simulacion si la corrida no guardo ninguna determinacion
 * @param conjunto el conjunto sellado con que se calculo; vacio si no se determino ninguno
 * @param fechaCalculo el dia al que corresponde la corrida (regla 9)
 * @param etapas el resumen por etapa, en el orden en que ocurrieron
 * @param observados los contribuyentes que quedan fuera de la emision, con su motivo
 */
public record CorridaPredialResource(
        String ejercicio,
        String alcance,
        boolean simulacion,
        String conjunto,
        String fechaCalculo,
        List<Etapa> etapas,
        List<ObservadoResource> observados) {

    private static final String ESTADO_OK = "OK";
    private static final String ESTADO_CON_OBSERVACIONES = "CON OBSERVACIONES";
    private static final String SIN_MONTO = "";

    public CorridaPredialResource {
        Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
        etapas = List.copyOf(etapas);
        observados = List.copyOf(observados);
    }

    public static CorridaPredialResource de(DeterminarPredialMasivo.Corrida corrida) {
        List<ObservadoResource> observados = new ArrayList<>();
        for (DeterminarPredialMasivo.Observado observado : corrida.observados()) {
            observados.add(
                    new ObservadoResource(
                            observado.codContribuyente(), observado.nombre(), observado.motivo()));
        }
        List<Etapa> etapas =
                List.of(
                        new Etapa("Padrón leído", corrida.leidos(), SIN_MONTO, 0, ESTADO_OK),
                        new Etapa(
                                corrida.simulacion() ? "Simulados" : "Determinados",
                                corrida.determinados(),
                                corrida.montoEmitido().toString(),
                                0,
                                ESTADO_OK),
                        new Etapa(
                                "Observados",
                                corrida.observados().size(),
                                SIN_MONTO,
                                corrida.observados().size(),
                                corrida.observados().isEmpty()
                                        ? ESTADO_OK
                                        : ESTADO_CON_OBSERVACIONES));
        return new CorridaPredialResource(
                corrida.ejercicio().toString(),
                corrida.alcance(),
                corrida.simulacion(),
                corrida.nombreDelConjunto(),
                corrida.fechaCalculo().toString(),
                etapas,
                observados);
    }

    /**
     * Una etapa de la corrida.
     *
     * @param monto la suma emitida en esa etapa; vacio donde la etapa no mueve dinero. Vacio y cero
     *     no son lo mismo: «no se emitio nada» y «esta etapa no emite» se leen igual en una grilla
     *     si las dos dicen 0.00
     */
    public record Etapa(String etapa, int registros, String monto, int observados, String estado) {}

    /** Un contribuyente que queda fuera de la emision, y por que. */
    public record ObservadoResource(String codContribuyente, String nombre, String motivo) {}
}
