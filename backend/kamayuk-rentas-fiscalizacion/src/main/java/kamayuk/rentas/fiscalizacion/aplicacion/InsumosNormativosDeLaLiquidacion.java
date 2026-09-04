package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve los valores normativos que una liquidación necesita para tener cifras, <b>del conjunto
 * sellado que ella misma fijó</b> (#49 AC 1, #198).
 *
 * <h2>Qué hace hoy, y por qué se escribe hoy</h2>
 *
 * <p>Hoy <b>falla</b>, y falla nombrando la llave que falta. Es lo correcto: D-02a no ha entregado
 * la UIT del ejercicio, el cuadro de valores unitarios, la tabla de depreciación ni la multa del
 * art. 176, así que ninguna liquidación puede tener importes. Lo que esta clase aporta es que la
 * ausencia se note <b>por su nombre</b> —{@code UIT}, {@code VALOR_UNITARIO}…— en vez de producirse
 * un cero silencioso, y que el día que #198 cargue esos datos no haya que escribir el camino: ya
 * está, y ya está probado.
 *
 * <p><b>No calcula nada.</b> No hay una fórmula aquí, ni siquiera la del art. 176: cómo se compone
 * la multa —sobre qué base, con qué gradualidad— es parte de lo que D-02a y D-02c tienen que
 * responder, y escribirla ahora sería inventarla. Esta clase lee, y nada más.
 *
 * <h2>Por conjunto, nunca por ejercicio</h2>
 *
 * <p>{@link LectorDeParametros#porConjunto} y no {@link LectorDeParametros#vigenteEn}. Es el AC 1
 * de #49 —«cambiar los parámetros de hoy no altera una liquidación emitida»— y es el defecto que
 * ARQ-09 §3 nombra: si entre la emisión y el recálculo se sella una versión nueva, resolver por
 * ejercicio devuelve otros parámetros y la liquidación ya notificada cambia de cifra sin que nada
 * falle.
 *
 * <p>Cada línea guarda su {@code conjuntoId} desde V39, precisamente para que esta lectura sea
 * posible diez años después. Va en la línea y no en la cabecera porque una fiscalización abarca un
 * periodo, y los parámetros de 2022 no son los de 2026.
 */
@Service
public class InsumosNormativosDeLaLiquidacion {

    /**
     * Las llaves que una liquidación de fiscalización predial necesita para tener cifras.
     *
     * <p>Son <b>nombres</b>, no valores: ninguna cifra vive aquí (regla 5). Cada una identifica una
     * fila del conjunto sellado que D-02a tiene que firmar, y su ausencia es lo que hoy detiene la
     * valorización.
     *
     * <ul>
     *   <li>{@code UIT} — base de la multa tributaria. D.S. anual del MEF (NEG-02 §2, dato 1).
     *   <li>{@code VALOR_UNITARIO} — cuadro de valores unitarios de edificación. Resolución anual
     *       del sector Vivienda (dato 7).
     *   <li>{@code DEPRECIACION} — tabla anexa a esa resolución (dato 9).
     *   <li>{@code MULTA_TRIBUTARIA:ART_176_NUM_1} — la multa por declarar fuera de plazo. TUO del
     *       Código Tributario art. 176 y sus tablas (dato 27).
     * </ul>
     *
     * <p>Es una lista y no un mapa para que el orden sea el declarado: la llave que se nombra al
     * fallar tiene que ser siempre la misma, o el mensaje del error cambiaria de una corrida a otra
     * sin que nada hubiera cambiado.
     */
    public static final List<LlaveNormativa> LLAVES_QUE_ESPERAN_A_D02A =
            List.of(
                    new LlaveNormativa("UIT", null),
                    new LlaveNormativa("VALOR_UNITARIO", null),
                    new LlaveNormativa("DEPRECIACION", null),
                    new LlaveNormativa("MULTA_TRIBUTARIA", "ART_176_NUM_1"));

    /**
     * El nombre de un valor normativo que la liquidacion necesita. Solo el nombre: la cifra vive en
     * el conjunto sellado (regla 5).
     *
     * @param tipo el tipo del parametro
     * @param clave el discriminante dentro del tipo; {@code null} si el tipo no lo tiene
     */
    public record LlaveNormativa(String tipo, @Nullable String clave) {

        public LlaveNormativa {
            Objects.requireNonNull(tipo, "Todo parametro tiene tipo");
        }

        /** Como se escribe en el conjunto sellado y en el mensaje del error: {@code tipo:clave}. */
        @Override
        public String toString() {
            return clave == null ? tipo : tipo + ":" + clave;
        }
    }

    private final LectorDeParametros parametros;

    public InsumosNormativosDeLaLiquidacion(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /**
     * Los valores normativos de la liquidación, leídos del conjunto que ella fijó.
     *
     * @throws ParametrosSellados.ParametroAusente si falta alguno, nombrando la llave. No hay valor
     *     por omisión: valorizar con cero produciría un padrón entero de liquidaciones en blanco
     *     sin ningún error de por medio, y eso se descubre cuando llega la primera reclamación
     * @throws LectorDeParametros.ConjuntoNoSellado si el conjunto que la liquidación referencia no
     *     existe o no está sellado. Que una liquidación apunte a un conjunto abierto significa que
     *     se emitió sin sellar: no se calcula sobre eso, se investiga
     */
    @Transactional(readOnly = true)
    public Map<String, ValorNormativo> de(LineaDeLiquidacion linea) {
        Objects.requireNonNull(linea, "Los insumos son de una linea de liquidacion");

        ParametrosSellados sellados = parametros.porConjunto(conjuntoQueUsa(linea));

        Map<String, ValorNormativo> insumos = new LinkedHashMap<>();
        for (LlaveNormativa llave : LLAVES_QUE_ESPERAN_A_D02A) {
            insumos.put(llave.toString(), sellados.exigirNumero(llave.tipo(), llave.clave()));
        }
        return Map.copyOf(insumos);
    }

    /**
     * Qué conjunto usaría la valorización de esta línea.
     *
     * <p>Se publica para que la comprobación del AC 1 pueda mirar el identificador sin necesitar
     * que el conjunto tenga valores dentro: lo que el criterio exige es que sea <b>el mismo</b>
     * después de sellar otra versión, no cuánto vale la UIT.
     */
    public IdentificadorDeConjunto conjuntoQueUsa(LineaDeLiquidacion linea) {
        return IdentificadorDeConjunto.de(linea.conjuntoId());
    }
}
