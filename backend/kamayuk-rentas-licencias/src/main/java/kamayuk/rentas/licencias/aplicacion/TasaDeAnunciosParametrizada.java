package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.springframework.stereotype.Service;

/**
 * De donde sale cuanto cuesta autorizar un anuncio: del conjunto sellado que rige a la fecha del
 * acto (#51, RF-114).
 *
 * <h2>La tasa NO esta en este repositorio, y no puede estarlo</h2>
 *
 * <p>Es el mismo camino que {@code ArancelDeCostasParametrizado} (#42), {@code
 * CondicionesParametrizadas} (#35) y {@code PlazosCoactivosParametrizados} (#41), y por el mismo
 * motivo. La tasa por anuncios y propaganda la aprueba <b>cada municipalidad por ordenanza</b>,
 * ratificada por la provincia: es <b>D-02b</b>, y el issue <b>#199</b> —la mitad de #51 que
 * necesita cifras— esta bloqueado esperandola. Compilar aqui «un panel cuesta tanto» tendria dos
 * consecuencias inmediatas: no se podria cambiar sin desplegar, y las autorizaciones ya emitidas se
 * recalcularian con la tarifa nueva.
 *
 * <p><b>Y no hay valor por omision.</b> Una tasa que faltara y se sustituyera por cero regalaria la
 * publicidad de todo el distrito; una «razonable» produciria cobros sin sustento normativo en cada
 * autorizacion, y eso se descubre cuando el primero se impugna. Falta el parametro, falla el
 * registro, y el mensaje dice <b>cual</b> falta.
 *
 * <h2>Una llave por clase de elemento, y ninguna aritmetica</h2>
 *
 * <p>La llave es {@code TASA_ANUNCIO:<CLASE>} —{@code TASA_ANUNCIO:PANEL}, {@code
 * TASA_ANUNCIO:TOLDO}…—, con los nombres de {@link ClaseDeAnuncio}. Que la clase sea la clave y no
 * un numero de fila es lo que permite que una ordenanza tarife unas clases y otras no: la clase sin
 * tarifa declarada <b>no se autoriza</b> —no se autoriza a cero—, porque cero y «esta ordenanza no
 * lo tarifa» son cosas distintas y la segunda no es un cobro.
 *
 * <p><b>Aqui no se multiplica por el area, y es deliberado.</b> La pantalla dice que la tasa
 * «resulta del area del anuncio, el numero de lados y su clase», y esa formula es exactamente lo
 * que #199 traera con sus cifras. Escribirla hoy exigiria dos cosas que no existen: los importes
 * por m² (D-02b) y un <b>punto de redondeo</b> para el producto (D-03c). {@code PuntoDeRedondeo}
 * dice con todas sus letras que su lista «solo crece con una determinacion observada, no con una
 * conjetura», asi que inventar {@code TASA_DE_ANUNCIO} para poder multiplicar seria tomar por
 * descuento la decision que D-03c no ha tomado. Mientras tanto la ordenanza entra tarifada por
 * clase, que es una forma en que las ordenanzas se escriben de verdad, y el area y los lados quedan
 * guardados en el anuncio esperando la formula.
 *
 * <h2>«Vigente a la fecha del acto», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha de la autorizacion o de la renovacion.
 * Sin eso, revisar dentro de dos anios por que una autorizacion costo lo que costo resolveria «el
 * vigente» y podria dar otra cifra, sin avisar (ARQ-09 §3). Lo que se cobro queda ademas copiado en
 * {@code anuncio_movimiento.tasa}.
 */
@Service
public class TasaDeAnunciosParametrizada {

    /**
     * El {@code tipo} de {@code parametro_tributario} bajo el que viven las tasas de anuncios.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ninguna cifra en esta constante ni
     * en ninguna otra de esta clase.
     */
    private static final String TIPO_TASA = "TASA_ANUNCIO";

    private final LectorDeParametros parametros;

    public TasaDeAnunciosParametrizada(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** La tasa que rige a esa fecha, resuelta de un solo conjunto. */
    public Vigente aLaFechaDe(LocalDate fechaDelActo) {
        Ejercicio ejercicio = Ejercicio.de(fechaDelActo);
        return new Vigente(ejercicio, parametros.vigenteEn(ejercicio));
    }

    /**
     * La tarifa de un ejercicio, ya resuelta.
     *
     * <p>Se entrega como objeto y no como llamadas sueltas para que todas las lecturas de un mismo
     * acto salgan del <b>mismo</b> conjunto: resolverlas por separado abriria la puerta a que un
     * sellado ocurrido entre dos de ellas dejara una autorizacion cobrada con dos versiones
     * distintas.
     */
    public static final class Vigente {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;

        private Vigente(Ejercicio ejercicio, ParametrosSellados sellados) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
        }

        /** El ejercicio con el que se resolvio. */
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** La llave del parametro que tarifa esa clase, tal como se escribe en la fuente. */
        public String llaveDe(ClaseDeAnuncio clase) {
            return TIPO_TASA + ":" + clase.claveDeLaTasa();
        }

        /**
         * Cuanto cuesta autorizar un elemento de esa clase, segun la ordenanza sellada.
         *
         * @throws TasaSinParametrizar si la ordenanza cargada no tarifa esa clase
         */
        public Dinero paraLaClase(ClaseDeAnuncio clase) {
            ValorNormativo valor =
                    sellados.numero(TIPO_TASA, clase.claveDeLaTasa())
                            .orElseThrow(() -> new TasaSinParametrizar(ejercicio, clase));
            Dinero tasa = new Dinero(valor.valor());
            if (!tasa.esPositivo()) {
                throw new IllegalStateException(
                        "El parametro "
                                + llaveDe(clase)
                                + " del ejercicio "
                                + ejercicio
                                + " vale "
                                + tasa.valor().toPlainString()
                                + ", y una tasa de cero o negativa no es una tasa. Una clase que la"
                                + " ordenanza no tarifa se deja SIN parametro, que es distinto de"
                                + " tarifarla en cero");
            }
            return tasa;
        }

        /** Si la ordenanza tarifa esa clase. Una clase sin tarifa no se autoriza a cero. */
        public boolean tarifa(ClaseDeAnuncio clase) {
            return sellados.numero(TIPO_TASA, clase.claveDeLaTasa()).isPresent();
        }
    }

    /**
     * Falta la tasa que el registro necesita.
     *
     * <p>Es lo que ocurre hoy: la tasa de anuncios es de ordenanza local y vive en D-02b (#199,
     * bloqueado). Que falle aqui, nombrando la llave, es preferible a que la autorizacion se emita
     * con una cifra inventada —que es un cobro sin sustento normativo repetido en todo el padron de
     * publicidad—.
     */
    public static final class TasaSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        TasaSinParametrizar(Ejercicio ejercicio, ClaseDeAnuncio clase) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene el parametro "
                            + TIPO_TASA
                            + ":"
                            + clase.claveDeLaTasa()
                            + ". Sin el no hay tarifa que aplicar a un anuncio de clase "
                            + clase.etiqueta()
                            + ", y una tasa inventada es un cobro que ninguna ordenanza respalda"
                            + " (regla 5, D-02b, #199)");
            this.ejercicio = ejercicio;
            this.llave = TIPO_TASA + ":" + clase.claveDeLaTasa();
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** La llave que falta, {@code tipo:clave}, legible por programa. */
        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }
}
