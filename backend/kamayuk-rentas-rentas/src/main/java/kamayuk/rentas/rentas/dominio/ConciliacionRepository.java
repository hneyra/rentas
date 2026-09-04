package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Cuantos predios del padron estan conciliados con rentas a un ejercicio, <b>sin recorrerlos</b>
 * (#564, ADR-0015).
 *
 * <h2>Por que hacia falta, y por que la grilla no servia</h2>
 *
 * <p>{@code GET /catastro/fichas/conciliacion?conciliadaConRentas=No} <b>no se puede usar para
 * contar</b>: el filtro se aplica sobre la pagina ya devuelta y {@code totalElementos} sigue siendo
 * el del padron sin filtrar. Medido sobre Catacaos, los tres filtros contestaban <b>14 422</b>, que
 * es el padron entero; el panel de Catastro dibujaba con esa cifra la tarea «Predios sin conciliar:
 * 14 422» encima de un indicador que decia «14 422 predios en el padron», o sea una acusacion de
 * omision a todo el distrito que ninguna de las dos cifras pretendia hacer.
 *
 * <p>Recorrer la grilla tampoco era salida: serian 722 peticiones de 20 filas, y cada una de las
 * del filtro «No» <b>escribe una fila de bitacora</b> (ADR-0015 §2.3), de modo que pintar un numero
 * del panel dejaria 722 entradas de auditoria.
 *
 * <h2>Por que esta consulta vive aqui, y lo que eso cuesta</h2>
 *
 * <p>Es la decision de {@code fiscalizacion.DeteccionRepository} (#545), tomada por el mismo
 * motivo: el derivado —«conciliada a un ejercicio»— sale del cruce, asi que solo se conoce
 * <b>despues</b> de traer la pagina, y un derivado que hay que poder <b>contar</b> se escribe una
 * sola vez y en SQL.
 *
 * <ul>
 *   <li>En {@code catastro} no puede: tendria que leer {@code declaracion_jurada}, que es de
 *       rentas, y rentas ya depende de catastro — el ciclo que {@code ConsultaDeConciliacion} deja
 *       descartado por escrito.
 *   <li>En {@code rentas} si: la arista rentas ──► catastro ya existe (ARQ-01 §2) y el derivado es
 *       de este contexto.
 * </ul>
 *
 * <p><b>Lo que eso cuesta, dicho:</b> la implementacion lee dos tablas ajenas —{@code
 * ficha_catastral} y {@code predio}, de catastro—, las dos de solo lectura y bajo la misma politica
 * RLS. Y arrastra un riesgo propio: que la poblacion que <b>cuenta</b> deje de ser la que la grilla
 * <b>lista</b>. Eso no se confia a quien lo escriba — una prueba compara el {@code total} de este
 * conteo contra el {@code totalElementos} de la grilla, y se pone roja si se separan.
 *
 * <p>El estado que hace vigente una declaracion no se transcribe: sale de {@link
 * EstadoDeDeclaracion#nombresDeLasVigentes()}, el mismo arreglo que usa {@code
 * prediosConDeclaracionVigente}. Dos copias del predicado divergen, y la que se lee en el panel
 * seria la que nadie compara.
 */
public interface ConciliacionRepository {

    /**
     * El recuento a un ejercicio y a una fecha.
     *
     * @param ejercicio a que ejercicio se contesta; no existe «sin conciliar», existe «sin
     *     conciliar a 2026» (regla 9, RNF-075)
     * @param aLaFecha que version de ficha rige, igual que en la grilla
     */
    ResumenDeConciliacion contar(Ejercicio ejercicio, LocalDate aLaFecha);

    /**
     * Cuantos hay, cuantos declararon y cuantos no.
     *
     * <p>Los tres, y no solo el que el panel pinta: «14 422 sin conciliar» no significa nada sin
     * saber sobre cuantos, y fue justamente leer una cifra sin la otra lo que produjo el defecto.
     *
     * @param noConciliados derivado de los otros dos, y aqui a proposito: componerlo en la interfaz
     *     seria una resta de cifras en pantalla (RNF-083)
     */
    record ResumenDeConciliacion(
            Ejercicio ejercicio,
            LocalDate aLaFecha,
            long total,
            long conciliados,
            long noConciliados) {

        public ResumenDeConciliacion {
            Objects.requireNonNull(ejercicio, "No hay «conciliado»: hay conciliadoA(ejercicio)");
            Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha");
        }

        /** Los tres, con el tercero calculado donde se sabe que los otros dos son coherentes. */
        public static ResumenDeConciliacion de(
                Ejercicio ejercicio, LocalDate aLaFecha, long total, long conciliados) {
            return new ResumenDeConciliacion(
                    ejercicio, aLaFecha, total, conciliados, total - conciliados);
        }
    }
}
