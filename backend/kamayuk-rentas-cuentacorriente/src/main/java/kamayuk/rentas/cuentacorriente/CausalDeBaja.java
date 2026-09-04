package kamayuk.rentas.cuentacorriente;

import java.util.Locale;
import java.util.Objects;

/**
 * Por que se da de baja una deuda (#684, RF-044): el sustento juridico del acto.
 *
 * <h2>Que problema cierra</h2>
 *
 * <p>Hasta #684 la causal <b>no tenia campo</b>. {@code
 * MovimientosDeDeudaController.PeticionDeMovimiento} declaraba diecinueve y ninguno era este, asi
 * que la pantalla de RF-044 anteponia la causal al texto de la observacion —«PRESCRIPCIÓN
 * DECLARADA. Deshace el alta…»— y ahi se quedaba. Consecuencias, las tres medidas en el issue:
 *
 * <ol>
 *   <li><b>El libro no sabia por que.</b> {@code cuenta_corriente_asiento.acto} (V68, #601) dice
 *       <i>que</i> se hizo —un alta o una baja— y nada decia <i>por que</i>.
 *   <li><b>RF-045 no podia filtrar por causal</b>, que es justo la pregunta de quien audita como se
 *       extingue deuda del municipio: «ensename las bajas por prescripcion». La unica forma era
 *       leer la observacion de cada fila a ojo.
 *   <li><b>El texto libre no es comparable.</b> «PRESCRIPCION DECLARADA», «prescripción declarada»
 *       y «prescrita s/ Res. 123-2026» son la misma causal y tres cadenas distintas — el mismo
 *       defecto de vocabulario que #553 midio para {@code tributo} y #542 para el tipo de
 *       transferencia, cerrado en los dos casos con un vocabulario cerrado.
 * </ol>
 *
 * <h2>De donde salen las seis, una por una</h2>
 *
 * <p>Del <b>desplegable «Causal» de la pantalla de baja de deuda</b>, que es la especificacion
 * funcional, y no hay ninguna que no este dibujada ahi. Son estas seis, en el orden en que el
 * manual las ofrece:
 *
 * <pre>
 *   «PRESCRIPCIÓN DECLARADA»         -&gt; PRESCRIPCION_DECLARADA
 *   «RESOLUCIÓN QUE DEJA SIN EFECTO» -&gt; RESOLUCION_QUE_DEJA_SIN_EFECTO
 *   «ERROR MATERIAL»                 -&gt; ERROR_MATERIAL
 *   «COMPENSACIÓN»                   -&gt; COMPENSACION
 *   «DEUDA DE COBRANZA DUDOSA»       -&gt; DEUDA_DE_COBRANZA_DUDOSA
 *   «CONDONACIÓN POR ORDENANZA»      -&gt; CONDONACION_POR_ORDENANZA
 * </pre>
 *
 * <p><b>Ninguna se traduce ni se aproxima</b>, y ninguna sobra ni falta: la lista de aqui es la del
 * desplegable, entera. Es lo que #427 hizo al negarse a leer «ACTIVA» como {@code VIGENTE} y #546
 * al negarse a mapear seis rotulos de hallazgo sobre cuatro valores. Si algun dia el manual ofrece
 * una causal mas, se anade aqui y en el {@code CHECK} de la tabla; lo que no se hace es meterla en
 * la mas parecida.
 *
 * <p>La <b>unica</b> diferencia entre el rotulo y el nombre es de escritura, y es la misma de
 * {@code TipoTransferencia} (#542): cuatro de los seis rotulos llevan tilde y ningun identificador
 * de este sistema puede llevarla (Checkstyle lo prohibe, ADR-0004), y el espacio del rotulo va aqui
 * como guion bajo. Por eso la pantalla que registre el acto tiene que traducir <b>con una tabla</b>
 * —una entrada por rotulo—, nunca quitando tildes con una funcion: una funcion normalizadora
 * convierte cualquier texto parecido en un valor «traducido», y lo que se clasifica aqui es el
 * sustento juridico de un acto que extingue deuda del municipio.
 *
 * <h2>Por que vive en el paquete raiz</h2>
 *
 * <p>Porque {@link ExtincionDeDeuda} la recibe, y esa interfaz es lo que {@code sanciones} ve de
 * este modulo: Spring Modulith trata como interno todo lo que esta en un subpaquete, asi que un
 * tipo de {@code cuentacorriente.dominio} en su firma seria «depends on non-exposed type» —lo que
 * #51 midio con {@code Concepto}—.
 *
 * <h2>Lo que NO es</h2>
 *
 * <p>No es la observacion (regla 10, RNF-052). Son dos cosas: la causal es el <b>sustento</b> del
 * acto —una lista cerrada que se puede filtrar y contar— y la observacion es el <b>relato</b> de
 * quien firma, texto libre suyo. Componer una dentro de la otra fue el defecto que este enumerado
 * cierra, y es la misma salida que #653 rechazo para la declaracion de titular anterior y #488 para
 * «inventar la observacion que falta».
 *
 * <p>Y no es el {@code documentoOrigen}: ese es el <b>papel</b> que aprueba el acto —el numero de
 * la resolucion—, y sigue siendo obligatorio. La causal dice de que clase de acto es ese papel.
 */
public enum CausalDeBaja {

    /**
     * «PRESCRIPCIÓN DECLARADA». La primera del desplegable.
     *
     * <p>Se apoya en la resolucion que declara la prescripcion (RF-094, art. 43 del TUO). Ojo a lo
     * que #674 dejo medido: <b>declarar la prescripcion no toca el libro</b> —lo que prescribe es
     * la accion de cobro y este libro es el de la obligacion—, asi que la unica huella de una
     * prescripcion en la cuenta corriente es precisamente la baja que se registra por ella. Antes
     * de #684 esa huella era indistinguible de cualquier otra baja.
     */
    PRESCRIPCION_DECLARADA,

    /**
     * «RESOLUCIÓN QUE DEJA SIN EFECTO».
     *
     * <p>Es la causal de las bajas que asienta {@link ExtincionDeDeuda} desde {@code sanciones}
     * (#50, #662, RF-064): la resolucion de gerencia que deja una multa sin efecto, y la que
     * declara fundado un descargo. Ahi no se elige de un desplegable —la declara quien dicta la
     * resolucion, que es quien acaba de comprobar {@code dejaLaMultaSinEfecto()}—.
     */
    RESOLUCION_QUE_DEJA_SIN_EFECTO,

    /** «ERROR MATERIAL»: la baja que deshace un alta que no debio existir. */
    ERROR_MATERIAL,

    /** «COMPENSACIÓN». */
    COMPENSACION,

    /** «DEUDA DE COBRANZA DUDOSA». */
    DEUDA_DE_COBRANZA_DUDOSA,

    /** «CONDONACIÓN POR ORDENANZA». */
    CONDONACION_POR_ORDENANZA;

    /**
     * La causal que nombra ese texto, sin ninguna tolerancia mas que los blancos y la caja.
     *
     * <p><b>No normaliza tildes ni separadores a proposito.</b> Con esa tolerancia «COMPENSACIÓN» y
     * «COMPENSACION» entrarian las dos y quedarian guardadas como la misma, que es justo lo que
     * #542 midio y rechazo: una lectura tolerante convierte cualquier texto parecido en un valor
     * valido, y aqui lo que se clasifica se imprime y se audita. Lo que llega mal se rechaza
     * nombrando lo recibido y lo admitido.
     *
     * @throws IllegalArgumentException si el texto no es exactamente uno de los seis nombres
     */
    public static CausalDeBaja de(String texto) {
        Objects.requireNonNull(texto, "Una baja de deuda declara su causal");
        String nombre = texto.strip().toUpperCase(Locale.ROOT);
        for (CausalDeBaja causal : values()) {
            if (causal.name().equals(nombre)) {
                return causal;
            }
        }
        throw new IllegalArgumentException(
                "Causal de baja desconocida: '" + texto + "'. Las que hay son " + admitidas());
    }

    /** Las seis, para poder nombrarlas en un rechazo. */
    public static String admitidas() {
        StringBuilder nombres = new StringBuilder();
        for (CausalDeBaja causal : values()) {
            if (!nombres.isEmpty()) {
                nombres.append(", ");
            }
            nombres.append(causal.name());
        }
        return nombres.toString();
    }
}
