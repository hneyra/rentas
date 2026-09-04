package kamayuk.rentas.parametros;

import java.util.Set;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una regla {@code RT-xxx} del calculo: una funcion pura que consume conceptos y produce uno.
 *
 * <p>Requisitos que ARQ-09 §1.2 le exige a toda regla, y que esta forma hace cumplir:
 *
 * <ol>
 *   <li><b>Funcion pura.</b> Todo lo que puede leer llega en {@link InsumosDeLaRegla}: ni base de
 *       datos, ni reloj, ni configuracion global. El ejercicio entra como argumento.
 *   <li><b>Los parametros entran como argumento</b>, nunca se leen dentro.
 *   <li><b>Identificador {@code RT-xxx}</b> y norma citada en {@link #descripcion()}.
 *   <li>Prueba unitaria con los ejemplos numericos de su documento, sin levantar Spring.
 * </ol>
 *
 * <p><b>Las dependencias se declaran, no se deducen del orden.</b> {@link #requiere()} y {@link
 * #produce()} son las aristas del grafo de NEG-05 §1, donde {@code RT-001}, {@code RT-002} y {@code
 * RT-005} son ramas independientes que convergen en {@code RT-010}. El motor las ordena por lo
 * declarado, y {@link InsumosDeLaRegla} impide leer otra cosa.
 *
 * <p><b>Una implementacion que ya se uso en una emision no se modifica nunca</b> (ARQ-09 §1.3). Si
 * tiene un defecto se crea otra con su {@link #vigencia()}, y el recalculo del pasado sigue usando
 * la que corresponde a ese ejercicio. Con los anos se acumulan implementaciones: no es descuido, es
 * el registro de como se calculaba entonces.
 */
public interface ReglaTributaria {

    /** {@code RT-} y tres digitos, el mismo de NEG-05. */
    IdentificadorDeRegla identificador();

    /** Desde que ejercicio rige, y hasta cual. */
    RangoDeEjercicios vigencia();

    /** Enunciado y norma citada (RNF-090). */
    String descripcion();

    /** Los conceptos que la regla necesita. Vacio si arranca de los datos declarados. */
    Set<Concepto> requiere();

    /** El concepto que la regla calcula. Dos reglas vigentes no pueden producir el mismo. */
    Concepto produce();

    /** El calculo. Solo puede leer lo que hay en {@code insumos}. */
    Dinero calcular(InsumosDeLaRegla insumos);
}
