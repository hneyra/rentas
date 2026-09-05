package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import org.jspecify.annotations.Nullable;

/**
 * La ultima corrida de emision, tal como sale por {@code GET /rentas/predial/corridas/ultima}
 * (#523).
 *
 * <p>Es la misma lectura que {@link CorridaPredialResource} publica en la respuesta del {@code
 * POST} que ejecuta la corrida, con dos diferencias que importan:
 *
 * <ul>
 *   <li><b>Lleva {@code id}</b>, que es con lo que la pantalla pide los observados. En la respuesta
 *       del {@code POST} no hacia falta —los traia dentro— y aqui si: son cientos y viajan aparte.
 *   <li><b>No trae los observados</b>, por lo mismo.
 * </ul>
 *
 * <p>Las etapas se componen igual que alli, y a proposito: dos formas distintas del mismo hecho
 * dirian dos cosas de la misma corrida, y la que se leyera al abrir la pantalla seria la que nadie
 * compara.
 *
 * @param id el de la corrida, con el que se piden sus observados
 * @param ejercicio el ejercicio recalculado
 * @param alcance TODOS o SECTOR
 * @param sector cual, cuando el alcance es SECTOR
 * @param simulacion si la corrida no asento ninguna determinacion
 * @param conjunto el conjunto sellado con que se emitio (ARQ-09 §3)
 * @param fechaCalculo el dia al que corresponden sus cifras (regla 9)
 * @param observados cuantos quedaron fuera; la lista se pide aparte
 * @param etapas el resumen por etapa, en el orden en que ocurrieron
 */
public record CorridaGuardadaResource(
        long id,
        String ejercicio,
        String alcance,
        @Nullable String sector,
        boolean simulacion,
        String conjunto,
        String fechaCalculo,
        int observados,
        List<CorridaPredialResource.Etapa> etapas) {

    private static final String ESTADO_OK = "OK";
    private static final String ESTADO_CON_OBSERVACIONES = "CON OBSERVACIONES";
    private static final String SIN_MONTO = "";

    public CorridaGuardadaResource {
        Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
        etapas = List.copyOf(etapas);
    }

    public static CorridaGuardadaResource de(CorridaDeEmision corrida) {
        int fuera = corrida.leidos() - corrida.determinados();
        List<CorridaPredialResource.Etapa> etapas =
                List.of(
                        new CorridaPredialResource.Etapa(
                                "Padrón leído", corrida.leidos(), SIN_MONTO, 0, ESTADO_OK),
                        new CorridaPredialResource.Etapa(
                                corrida.simulacion() ? "Simulados" : "Determinados",
                                corrida.determinados(),
                                corrida.montoEmitido().toString(),
                                fuera,
                                fuera == 0 ? ESTADO_OK : ESTADO_CON_OBSERVACIONES));

        return new CorridaGuardadaResource(
                Objects.requireNonNull(corrida.id(), "Una corrida leida de la base tiene id"),
                String.valueOf(corrida.ejercicio().valor()),
                corrida.alcance(),
                corrida.sector(),
                corrida.simulacion(),
                corrida.conjunto(),
                corrida.fechaCalculo().toString(),
                fuera,
                etapas);
    }
}
