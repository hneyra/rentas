package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Optional;

/**
 * La version de la ficha <b>economica</b> de un predio vigente en una fecha, publicada para otros
 * contextos acotados (ARQ-01 §4, #19, #44).
 *
 * <p>La pide {@code licencias}: una licencia de funcionamiento recae sobre un establecimiento, y la
 * ficha economica es la que describe la actividad que en el se desarrolla. Las dos se referencian
 * <b>sin que ninguno de los dos contextos dependa del otro por dentro</b> —que es un criterio de
 * aceptacion de #44 y lo que Spring Modulith verifica—: {@code licencias} guarda en {@code
 * licencia_funcionamiento.ficha_id} el identificador que este puerto devuelve y nada mas, y {@code
 * catastro} no sabe que existen las licencias.
 *
 * <h2>Por que un puerto aparte y no un metodo mas en {@link LectorDeFichas}</h2>
 *
 * <p>Porque {@link LectorDeFichas} tiene <b>un solo metodo abstracto</b> y se consume como lambda:
 * {@code RegistrarActaFiscalizacionTest} escribe {@code (predioId, fecha) -> Optional.of(900L)}.
 * Anadirle un segundo metodo dejaria de compilar codigo de otro contexto que no tiene nada que ver
 * con las licencias, y arreglarlo alli seria pagar en {@code fiscalizacion} el precio de una
 * necesidad de {@code licencias}.
 *
 * <p>Un parametro {@code TipoFicha} en la firma tampoco sirve: obligaria a publicar la enumeracion
 * de tipos de ficha —modelo interno de {@code catastro}, con cuatro variantes y sus reglas de
 * detalle— solo para que dos llamadores eligieran entre dos de ellas.
 *
 * <p>Es el mismo criterio con el que este contexto ya publica {@link LectorDeCaracteristicas} y
 * {@link PrediosDelContribuyente} por separado: puertos pequenos, uno por pregunta.
 */
public interface LectorDeFichasEconomicas {

    /**
     * La ficha economica que regia en esa fecha, si el predio tiene alguna.
     *
     * <p>Vacio si no la tiene, y eso <b>no es un error</b>: hay establecimientos en predios que
     * todavia no se han levantado economicamente, y negarles la licencia por eso seria inventar un
     * requisito que ninguna norma pone.
     *
     * <p>«Vigente en esa fecha», no «la ultima» (regla 9): una licencia emitida en marzo quedo
     * enlazada a la ficha de marzo, y reimprimirla en diciembre tiene que seguir diciendo lo mismo.
     */
    Optional<Long> fichaEconomicaVigenteEn(long predioId, LocalDate fecha);
}
