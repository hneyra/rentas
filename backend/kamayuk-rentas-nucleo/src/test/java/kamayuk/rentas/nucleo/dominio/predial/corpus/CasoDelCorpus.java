package kamayuk.rentas.nucleo.dominio.predial.corpus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Una fila del corpus de casos: lo que se espera del calculo de un caso concreto, con la cifra en
 * blanco mientras D-02a siga abierta.
 *
 * <p>Lo que hace util a un corpus sin cifras: <b>se deja en blanco el importe, no las aristas del
 * grafo</b>. Con parametros ficticios se puede comprobar hoy que un caso aplica exactamente las
 * reglas que declara y produce exactamente los conceptos que declara, y que los parametros que
 * declara son los que la regla pide de verdad.
 *
 * @param caso identificador estable, {@code RT-xxx-cNN}
 * @param casoBorde el caso borde de NEG-05 §2 que cubre, si cubre alguno
 * @param descripcion que situacion es
 * @param ejercicio del hecho imponible
 * @param entradas conceptos declarados de la partida —areas, aportes—, nunca cifras normativas
 * @param caracteristicas lo que la partida es y no cuanto vale: via, categoria, material
 * @param parametrosRequeridos las llaves que la regla pide, sin sus valores
 * @param reglasEsperadas los {@code RT-xxx} que deben haberse aplicado
 * @param conceptosEsperados los conceptos que el calculo debe haber producido
 * @param estado como se puede comprobar este caso hoy
 * @param esperado el importe. Vacio hasta D-02a
 * @param fuenteDelEsperado de donde sale ese importe. Obligatorio en cuanto haya importe
 */
public record CasoDelCorpus(
        String caso,
        Optional<String> casoBorde,
        String descripcion,
        int ejercicio,
        Map<String, String> entradas,
        Map<String, String> caracteristicas,
        List<String> parametrosRequeridos,
        List<String> reglasEsperadas,
        List<String> conceptosEsperados,
        EstadoDelCaso estado,
        Optional<String> esperado,
        Optional<String> fuenteDelEsperado) {

    /** La regla a la que pertenece, deducida del identificador del caso. */
    public String regla() {
        return caso.substring(0, "RT-000".length());
    }

    /** Sin cifra esperada: es el numero que baja cuando D-02a avanza. */
    public boolean sinCifra() {
        return esperado.isEmpty();
    }
}
