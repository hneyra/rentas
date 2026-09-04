package kamayuk.rentas.licencias.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.springframework.stereotype.Service;

/**
 * De donde sale <b>que concepto del TUPA</b> es el derecho de tramite de una licencia: del conjunto
 * sellado que rige a la fecha del tramite (#44, RF-110).
 *
 * <h2>Lo que se parametriza aqui es el concepto, no su importe</h2>
 *
 * <p>Y la distincion importa, porque la tentacion es la contraria. El <b>importe</b> del derecho ya
 * es dato versionado desde V3: vive en la tabla {@code tasa}, con su {@code documento_fuente} —la
 * ordenanza que lo fijo— y su {@code vigencia_desde}, y es de ahi de donde {@code CobrarTasa} lo
 * saca al cobrarlo (regla 5, ADR-0007). Copiarlo tambien al conjunto sellado dejaria <b>dos</b>
 * sitios donde vive la misma tarifa, y el dia que difieran la caja cobraria una cifra y la licencia
 * comprobaria otra.
 *
 * <p>Lo que si falta —y no puede ir compilado— es <b>cual</b> de los conceptos del TUPA cuenta como
 * el derecho de tramite de una licencia de funcionamiento. Cada municipalidad numera su TUPA como
 * quiere: un {@code "LF-001"} escrito en el codigo obligaria a recompilar para cada instalacion, y
 * peor, la primera que no lo tuviera veria pasar como valido cualquier recibo de tasas.
 *
 * <p>Mismo mecanismo que {@code PlazosParametrizados} (#39) y {@code CondicionesParametrizadas}
 * (#35): el conjunto sellado vigente a la fecha, sin valor por omision, y un error que dice <b>que
 * llave falta</b>.
 *
 * <h2>Tampoco hay valor por omision, y aqui la consecuencia es concreta</h2>
 *
 * <p>Si el concepto faltara y se sustituyera por «cualquier concepto», bastaria un recibo de
 * fotocopias para emitir una licencia. Si se sustituyera por «ninguno», no se podria emitir
 * ninguna. Falta el parametro, falla la operacion, y el mensaje dice cual falta.
 *
 * <h2>«Vigente a la fecha del tramite», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha de emision. Sin eso, revisar dentro de
 * dos anios por que se admitio el recibo que se admitio resolveria «el vigente» y podria dar otro
 * concepto, sin avisar (ARQ-09 §3).
 *
 * <h2>Con #54 entra un numero, y por eso entra AQUI</h2>
 *
 * <p>Un certificado de numeracion o de zonificacion tiene <b>vigencia</b>: vale tantos meses desde
 * que se emite, y cuantos lo fija el TUPA de cada municipalidad (D-02b). Es una cifra normativa,
 * asi que no puede estar compilada (regla 5) y vive en el conjunto sellado bajo {@code
 * VIGENCIA_CERTIFICADO:<TIPO>}.
 *
 * <p>Se lee desde <b>esta</b> clase y no desde un servicio aparte por un motivo concreto, el mismo
 * que {@link Vigentes} explica: el concepto del TUPA y la vigencia son las dos mitades de la misma
 * linea del TUPA, y la emision de un certificado necesita las dos <b>en el mismo acto</b>.
 * Resolverlas con dos lecturas separadas abriria la puerta a que un sellado ocurrido entre ellas
 * dejara un certificado cobrado con una version y fechado con otra.
 */
@Service
public class DerechosDeTramiteParametrizados {

    /**
     * El {@code tipo} de {@code parametro_tributario} bajo el que viven los conceptos del TUPA.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ninguna cifra en esta constante.
     */
    private static final String TIPO_TUPA = "TUPA";

    /** El concepto que cobra el derecho de tramite de una licencia de funcionamiento nueva. */
    private static final String CLAVE_LICENCIA = "DERECHO_LICENCIA_FUNCIONAMIENTO";

    /** El que cobra el derecho de un duplicado de licencia. */
    private static final String CLAVE_DUPLICADO = "DERECHO_DUPLICADO_LICENCIA";

    /**
     * El que cobra el derecho de tramite de una licencia de <b>edificacion</b> (#48 AC 5).
     *
     * <p>Es otro concepto y no el mismo: el TUPA cobra la licencia de obra por su propia linea, que
     * en muchas municipalidades se liquida ademas como un porcentaje del valor de obra. <b>Ese
     * porcentaje no esta aqui</b> ni en ninguna parte de este modulo: el importe del derecho vive
     * en la tabla {@code tasa} desde V3, con su ordenanza y su vigencia, y la mitad que falta —si
     * la ordenanza lo fija como porcentaje y cual— la espera #197 (D-02b).
     */
    private static final String CLAVE_EDIFICACION = "DERECHO_LICENCIA_EDIFICACION";

    /** El que cobra el derecho de la revalidacion de una licencia de edificacion (#48 AC 4). */
    private static final String CLAVE_REVALIDACION = "DERECHO_REVALIDACION_EDIFICACION";

    /**
     * El {@code tipo} bajo el que vive cuantos meses vale cada clase de certificado (#54).
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ningun numero de meses en esta
     * clase. La clave la compone {@link TipoDeCertificado#claveDeLaVigencia()}.
     */
    private static final String TIPO_VIGENCIA = "VIGENCIA_CERTIFICADO";

    /**
     * El maximo de meses que se admite como vigencia de un certificado.
     *
     * <p><b>No es una cifra normativa</b> y por eso puede estar aqui: no dice cuanto vale ningun
     * certificado, dice hasta donde se considera que el parametro cargado es un numero de meses y
     * no un error de transcripcion. Cien años de vigencia no es una politica municipal audaz: es un
     * cero de mas, y sin este tope se convertiria en un certificado que no caduca nunca.
     */
    private static final int MESES_MAXIMOS = 1200;

    private final LectorDeParametros parametros;

    public DerechosDeTramiteParametrizados(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** Los derechos que rigen a esa fecha, resueltos del <b>mismo</b> conjunto. */
    public Vigentes aLaFechaDe(LocalDate fechaDelTramite) {
        Ejercicio ejercicio = Ejercicio.de(fechaDelTramite);
        return new Vigentes(ejercicio, parametros.vigenteEn(ejercicio));
    }

    /**
     * Los conceptos de un ejercicio, ya resueltos.
     *
     * <p>Se entrega como objeto y no como llamadas sueltas para que las dos lecturas salgan del
     * mismo conjunto: resolverlas por separado abriria la puerta a que un sellado ocurrido entre
     * las dos dejara una operacion comprobada con dos versiones distintas.
     */
    public static final class Vigentes {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;

        private Vigentes(Ejercicio ejercicio, ParametrosSellados sellados) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
        }

        /** El codigo del concepto del TUPA que cobra el derecho de una licencia nueva. */
        public String paraLaLicencia() {
            return codigo(CLAVE_LICENCIA);
        }

        /** El codigo del concepto que cobra el derecho de un duplicado. */
        public String paraElDuplicado() {
            return codigo(CLAVE_DUPLICADO);
        }

        /** El codigo del concepto que cobra el derecho de una licencia de edificacion (#48). */
        public String paraLaEdificacion() {
            return codigo(CLAVE_EDIFICACION);
        }

        /** El que cobra el derecho de la revalidacion de una licencia de edificacion (#48). */
        public String paraLaRevalidacion() {
            return codigo(CLAVE_REVALIDACION);
        }

        /** El ejercicio con el que se resolvio el conjunto. */
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** El codigo del concepto del TUPA que cobra el derecho de ese certificado (#54). */
        public String paraElCertificado(TipoDeCertificado tipo) {
            return codigo(tipo.claveDelDerecho());
        }

        /**
         * Cuantos meses vale un certificado de ese tipo, segun el TUPA sellado (#54).
         *
         * <p><b>No hay valor por omision, y la consecuencia es concreta.</b> Si faltara y se
         * sustituyera por «indefinido», la municipalidad emitiria certificados de zonificacion que
         * no caducan nunca y alguien construiria en 2035 con los parametros de 2026. Si se
         * sustituyera por «un mes», se rechazarian tramites legitimos. Falta el parametro, falla la
         * emision, y el mensaje dice cual falta.
         *
         * @throws DerechoSinParametrizar si el conjunto sellado no dice cuantos meses vale
         */
        public int mesesDeVigenciaDelCertificado(TipoDeCertificado tipo) {
            ValorNormativo valor =
                    sellados.numero(TIPO_VIGENCIA, tipo.claveDeLaVigencia())
                            .orElseThrow(
                                    () ->
                                            new DerechoSinParametrizar(
                                                    ejercicio,
                                                    TIPO_VIGENCIA,
                                                    tipo.claveDeLaVigencia(),
                                                    "Sin el no se sabe hasta cuando vale un "
                                                            + tipo.etiqueta()
                                                            + ", y un certificado sin caducidad"
                                                            + " deja construir en 2035 con los"
                                                            + " parametros de hoy"));

            BigDecimal meses = valor.valor();
            if (meses.stripTrailingZeros().scale() > 0
                    || meses.compareTo(BigDecimal.ONE) < 0
                    || meses.compareTo(BigDecimal.valueOf(MESES_MAXIMOS)) > 0) {
                throw new IllegalStateException(
                        "El parametro "
                                + TIPO_VIGENCIA
                                + ":"
                                + tipo.claveDeLaVigencia()
                                + " del ejercicio "
                                + ejercicio
                                + " vale "
                                + meses.toPlainString()
                                + ", y una vigencia se expresa en meses enteros entre 1 y "
                                + MESES_MAXIMOS
                                + ". Un valor fuera de ese rango es un error de transcripcion, y"
                                + " aplicarlo produciria un certificado que caduca el dia que se"
                                + " emite o que no caduca nunca");
            }
            return meses.intValueExact();
        }

        private String codigo(String clave) {
            String texto =
                    sellados.texto(TIPO_TUPA, clave)
                            .orElseThrow(() -> new DerechoSinParametrizar(ejercicio, clave))
                            .strip()
                            .toUpperCase(Locale.ROOT);
            if (texto.isEmpty()) {
                throw new DerechoSinParametrizar(ejercicio, clave);
            }
            return texto;
        }
    }

    /**
     * Falta el concepto del TUPA que la operacion necesita.
     *
     * <p>Que falle aqui, nombrando la llave, es preferible a que la operacion siga con un concepto
     * inventado: con «cualquier concepto» bastaria un recibo de fotocopias para emitir una
     * licencia.
     */
    public static final class DerechoSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        DerechoSinParametrizar(Ejercicio ejercicio, String clave) {
            this(
                    ejercicio,
                    TIPO_TUPA,
                    clave,
                    "Sin el no se sabe que concepto del TUPA cobra el derecho de tramite, y admitir"
                            + " cualquiera dejaria emitir una licencia con el recibo de otra cosa");
        }

        DerechoSinParametrizar(
                Ejercicio ejercicio, String tipo, String clave, String consecuencia) {
            super(
                    String.format(
                            Locale.ROOT,
                            "El conjunto sellado del ejercicio %s no tiene el parametro %s:%s. %s"
                                    + " (regla 5, RF-110)",
                            ejercicio,
                            tipo,
                            clave,
                            consecuencia));
            this.ejercicio = ejercicio;
            this.llave = tipo + ":" + clave;
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
