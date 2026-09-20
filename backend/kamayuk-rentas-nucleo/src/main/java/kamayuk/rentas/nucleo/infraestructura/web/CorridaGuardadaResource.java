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
 * <h2>Los dos agregados que se publican como campo, y por que hacia falta (#271)</h2>
 *
 * <p>{@link #determinados} y {@link #montoEmitido} son <b>columnas de {@code corrida_predial}</b>
 * —{@code determinados} y {@code monto_emitido}—: lo que la corrida escribio al terminar, no una
 * suma que nadie recalcula aqui. Viajaban solo dentro de {@link #etapas}, donde son un rotulo y una
 * celda de una tabla, y el panel del modulo los dibuja ademas <b>arriba</b>, como cifra suelta.
 *
 * <p>Sacarlos de la tabla en el navegador seria leer «Determinados» de la segunda fila y confiar en
 * que la fila siga siendo la segunda y siga llamandose asi. Son el mismo hecho, y por eso se
 * publica <b>una vez</b> y en los dos sitios se lee el mismo campo.
 *
 * <h2>Y el derecho de emision NO se publica, medido (#271, D-02b)</h2>
 *
 * <p>El panel dibuja un tercer campo —«Derecho de emision»— y este recurso <b>no lo trae</b>. No es
 * un olvido:
 *
 * <ul>
 *   <li>{@code corrida_predial} tiene dieciocho columnas y <b>ninguna</b> es el derecho de emision:
 *       la corrida lo <i>aplica</i> —entra en {@code monto_emitido} dentro del total de cada
 *       contribuyente— y no lo <b>sella</b>.
 *   <li>Lo que si sella es {@code conjunto}, que es el <b>nombre</b> del conjunto —{@code
 *       varchar(60)}, «2026 v1»— y no su identificador. {@code
 *       CuadroPredialParametrizado.delConjunto} pide un {@code long conjuntoId}, y no hay lectura
 *       por nombre: ni por ahi se llega al conjunto que la corrida uso. Y en una corrida que no
 *       determino a nadie ese nombre es la cadena vacia.
 *   <li>El unico camino que queda es {@code vigenteEn(ejercicio)}, o sea <b>el conjunto sellado de
 *       hoy</b>, que no tiene por que ser aquel. Publicar eso seria una cifra equivocada de la peor
 *       clase: parece correcta.
 *   <li>Y encima romperia la lectura. {@code DERECHO_EMISION_PREDIAL} es de ordenanza local (D-02b)
 *       y hoy no esta publicado en ningun sitio; {@code ParametrosSellados.exigirNumero} lanza
 *       {@code ParametroAusente}, asi que leer una corrida que termino bien contestaria 422.
 * </ul>
 *
 * <p>Se queda con su hueco hasta que la corrida lo selle —una columna mas en {@code
 * corrida_predial}, escrita el dia de la corrida—, que es lo que hay que hacer y no esta hecho.
 *
 * @param id el de la corrida, con el que se piden sus observados
 * @param ejercicio el ejercicio recalculado
 * @param alcance TODOS o SECTOR
 * @param sector cual, cuando el alcance es SECTOR
 * @param simulacion si la corrida no asento ninguna determinacion
 * @param conjunto el conjunto sellado con que se emitio (ARQ-09 §3)
 * @param fechaCalculo el dia al que corresponden sus cifras (regla 9)
 * @param determinados cuantas cuentas quedaron determinadas; en una simulacion, cuantas se
 *     simularon
 * @param montoEmitido lo que la corrida determino en total —impuesto mas derecho de emision—, en
 *     texto y no en coma flotante (regla 1)
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
        int determinados,
        String montoEmitido,
        int observados,
        List<CorridaPredialResource.Etapa> etapas) {

    private static final String ESTADO_OK = "OK";
    private static final String ESTADO_CON_OBSERVACIONES = "CON OBSERVACIONES";
    private static final String SIN_MONTO = "";

    public CorridaGuardadaResource {
        Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
        Objects.requireNonNull(montoEmitido, "La corrida necesita lo que emitio");
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
                corrida.determinados(),
                corrida.montoEmitido().toString(),
                fuera,
                etapas);
    }
}
