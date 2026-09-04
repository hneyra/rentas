package kamayuk.rentas.parametros;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;

/**
 * Arma las {@link PoliticasDeRedondeo} <b>leyendolas del conjunto sellado</b>, nunca
 * escribiendolas.
 *
 * <p>Es el tercer entregable de E-7 (#203): lo que la campana de observacion del SRTM del MEF
 * averigue —en que puntos redondea y con que escala y modo— entra como <b>dato</b>, con su
 * documento fuente y su vigencia, igual que un tramo o una alicuota. Una politica escrita en el
 * codigo ya la detecta el escaner de fuentes (regla 5, D-03); lo que faltaba era el camino por el
 * que entra la respuesta.
 *
 * <h2>Un punto, una fila</h2>
 *
 * <p>Cada punto observado es una fila de {@code parametro_tributario} con {@code tipo = 'REDONDEO'}
 * y {@code clave} igual al nombre del {@link PuntoDeRedondeo}, que lleva <b>las dos mitades a la
 * vez</b>: {@code valor_numerico} es la escala y {@code valor_texto} el modo.
 *
 * <p>Van juntas en una fila y no en dos a proposito. Con una fila por mitad, un conjunto sellado
 * podria tener la escala de un punto sin su modo: eso no falla al sellar —cada fila es valida por
 * separado— y produce un punto <b>medio configurado</b>, que es peor que uno ausente porque
 * aparenta estar resuelto. Aqui esa situacion no se puede representar, y si aun asi llega, {@link
 * #de(ParametrosSellados)} la rechaza nombrando la mitad que falta.
 *
 * <h2>Por que un conjunto sin ningun punto falla</h2>
 *
 * <p>Devolver unas politicas vacias seria legal —{@link PoliticasDeRedondeo} admite no cubrir un
 * punto— pero el calculo reventaria despues, en el primer {@code en(punto)}, lejos de la causa. Y
 * mientras D-03c siga abierta la causa es siempre la misma: <b>todavia no se ha observado ningun
 * punto</b>. Se dice ahi, una vez, en vez de trece veces disfrazado.
 *
 * <h2>Y el punto que falta cuando SI hay otros</h2>
 *
 * <p>Ese es el tercer estado, y no lo cubre lo anterior: un conjunto que observa algunos puntos y
 * no el que un calculo concreto atraviesa. Lo dice {@link PoliticasDeRedondeo#en}, que es su razon
 * de ser, y lo dice desde el dominio puro —que no sabe de que ejercicio salieron las politicas—.
 * Para que la respuesta pueda decir <b>donde</b> publicar la fila que falta, quien resuelve el
 * punto lo pide por {@link #en(ParametrosSellados, PuntoDeRedondeo)} y no sobre las politicas ya
 * leidas (#633).
 */
public final class PoliticasDeRedondeoSelladas {

    /** El {@code tipo} de la fila. La {@code clave} es el nombre del punto. */
    public static final String TIPO = "REDONDEO";

    private PoliticasDeRedondeoSelladas() {}

    /**
     * Las politicas que el conjunto sellado parametriza, una por punto observado.
     *
     * @throws MediaPolitica si un punto trae la escala sin el modo, o al reves
     * @throws EscalaNoEntera si la escala tiene decimales
     * @throws ModoDesconocido si el modo no es un {@link RoundingMode}
     * @throws SinPuntosObservados si el conjunto no parametriza ningun punto
     */
    public static PoliticasDeRedondeo de(ParametrosSellados sellados) {
        Objects.requireNonNull(
                sellados, "Leer las politicas de redondeo exige el conjunto sellado");
        PoliticasDeRedondeo.Constructor constructor = PoliticasDeRedondeo.construir();
        List<PuntoDeRedondeo> leidos = new ArrayList<>();

        for (PuntoDeRedondeo punto : PuntoDeRedondeo.values()) {
            Optional<ValorNormativo> escala = sellados.numero(TIPO, punto.name());
            Optional<String> modo = sellados.texto(TIPO, punto.name());
            if (escala.isEmpty() && modo.isEmpty()) {
                continue;
            }
            if (escala.isEmpty() || modo.isEmpty()) {
                throw new MediaPolitica(sellados.ejercicio(), punto, escala.isPresent());
            }
            constructor.en(
                    punto,
                    new PoliticaDeRedondeo(
                            escala(sellados.ejercicio(), punto, escala.get()),
                            modo(sellados.ejercicio(), punto, modo.get())));
            leidos.add(punto);
        }

        if (leidos.isEmpty()) {
            throw new SinPuntosObservados(sellados);
        }
        return constructor.construir();
    }

    /**
     * La politica del punto que el calculo necesita, leida del conjunto sellado (#633).
     *
     * <p>Es {@link #de(ParametrosSellados)} seguido de {@link PoliticasDeRedondeo#en}, y existe por
     * una sola razon: <b>el fallo de «ese punto no lo observo nadie» tiene que decir donde
     * publicarlo</b>. {@link PoliticasDeRedondeo} es dominio puro —no sabe de que ejercicio
     * salieron sus politicas, y no tiene por que saberlo—, asi que su {@code PuntoSinPolitica}
     * nombra el punto y nada mas; quien si lo sabe es quien acaba de leer el conjunto, y es aqui.
     *
     * <p>Con eso, esa ausencia entra en el mismo contrato que sus cuatro hermanas ({@link
     * ParametroSinPublicar}) y sale con el mismo miembro {@code parametroQueFalta} del cuerpo
     * (#604): sin el, un 422 que nadie puede arreglar desde la pantalla seria indistinguible de uno
     * que si —«corrige el formulario» dicho de una fila que hay que publicar—.
     *
     * @throws PuntoSinObservar si el conjunto observa puntos pero no <b>este</b>
     * @throws SinPuntosObservados si el conjunto no observa ninguno
     */
    public static PoliticaDeRedondeo en(ParametrosSellados sellados, PuntoDeRedondeo punto) {
        Objects.requireNonNull(punto, "Resolver una politica de redondeo exige su punto");
        PoliticasDeRedondeo politicas = de(sellados);
        try {
            return politicas.en(punto);
        } catch (PoliticasDeRedondeo.PuntoSinPolitica sinObservar) {
            throw new PuntoSinObservar(sellados.ejercicio(), punto, sinObservar);
        }
    }

    private static int escala(Ejercicio ejercicio, PuntoDeRedondeo punto, ValorNormativo valor) {
        BigDecimal escala = valor.valor().stripTrailingZeros();
        if (escala.scale() > 0) {
            throw new EscalaNoEntera(ejercicio, punto, valor);
        }
        return escala.intValueExact();
    }

    private static RoundingMode modo(Ejercicio ejercicio, PuntoDeRedondeo punto, String texto) {
        try {
            return RoundingMode.valueOf(texto.strip());
        } catch (IllegalArgumentException desconocido) {
            throw new ModoDesconocido(ejercicio, punto, texto);
        }
    }

    /** Un punto con la escala pero sin el modo, o al reves. Ver el javadoc de la clase. */
    public static final class MediaPolitica extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final PuntoDeRedondeo punto;

        MediaPolitica(Ejercicio ejercicio, PuntoDeRedondeo punto, boolean tieneEscala) {
            super(
                    "El punto "
                            + punto
                            + " tiene "
                            + (tieneEscala ? "escala sin modo" : "modo sin escala")
                            + " en el conjunto sellado. Media politica no es una politica: la"
                            + " fila de REDONDEO:"
                            + punto
                            + " lleva valor_numerico y valor_texto, los dos");
            this.ejercicio = ejercicio;
            this.punto = punto;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llaveDe(punto));
        }
    }

    /** La escala llego con decimales: «redondear a 2,5 decimales» no significa nada. */
    public static final class EscalaNoEntera extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final PuntoDeRedondeo punto;

        EscalaNoEntera(Ejercicio ejercicio, PuntoDeRedondeo punto, ValorNormativo valor) {
            super(
                    "La escala de REDONDEO:"
                            + punto
                            + " es "
                            + valor
                            + ", y una escala es un numero de decimales, no un decimal");
            this.ejercicio = ejercicio;
            this.punto = punto;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llaveDe(punto));
        }
    }

    /** El modo no es ninguno de {@link RoundingMode}. */
    public static final class ModoDesconocido extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final PuntoDeRedondeo punto;

        ModoDesconocido(Ejercicio ejercicio, PuntoDeRedondeo punto, String texto) {
            super(
                    "El modo de REDONDEO:"
                            + punto
                            + " es '"
                            + texto
                            + "', que no es un RoundingMode. Los admitidos son "
                            + java.util.Arrays.toString(RoundingMode.values()));
            this.ejercicio = ejercicio;
            this.punto = punto;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llaveDe(punto));
        }
    }

    /**
     * El conjunto sellado no parametriza ningun punto de redondeo.
     *
     * <p>Su llave es el {@code TIPO} solo, sin clave ({@link ParametroSinPublicar}): lo que falta
     * no es una fila sino <b>todas</b>, y quien lee las politicas no sabe cual de los trece puntos
     * queria el que llamo. Nombrar {@code REDONDEO:CUOTA} porque es el que el convenio usa seria
     * una afirmacion verosimil y equivocada dicha desde el lector generico.
     */
    public static final class SinPuntosObservados extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        SinPuntosObservados(ParametrosSellados sellados) {
            super(
                    "El conjunto sellado del ejercicio "
                            + sellados.ejercicio()
                            + " (version "
                            + sellados.version()
                            + ") no tiene ninguna fila REDONDEO:‹punto›. Mientras D-03c siga"
                            + " abierta eso significa que todavia no se ha observado ningun punto"
                            + " del SRTM del MEF (#203); calcular sin ellas no da un importe sin"
                            + " redondear, da un fallo por cada punto y lejos de aqui");
            this.ejercicio = sellados.ejercicio();
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(TIPO);
        }
    }

    /**
     * El conjunto sellado observa puntos de redondeo, pero <b>no el que este calculo atraviesa</b>.
     *
     * <p>Es el tercer estado, y el que se escapo de #540 y de #547: no es «no hay conjunto» ni «al
     * conjunto le falta la llave del interes», es un conjunto que <b>si</b> tiene filas {@code
     * REDONDEO:‹punto›} y no la del punto que se pide. {@link PoliticasDeRedondeo} lo dice —esa es
     * su razon de ser, D-03c— y lo dice como {@code PuntoSinPolitica}, que es dominio puro y no
     * sabe de que ejercicio salieron las politicas; hasta #633 nadie la traducia, asi que salia
     * como {@code 500 ERROR_INTERNO} con identificador de incidencia.
     *
     * <p>Su llave es {@code REDONDEO:‹punto›} y no el {@code TIPO} solo, al reves que {@link
     * SinPuntosObservados}: aqui <b>si</b> se sabe cual falta —lo pidio el calculo—, y publicar el
     * bloque entero no es lo que hay que hacer.
     *
     * <p>El mensaje conserva el de {@code PuntoSinPolitica} entero, con los puntos que si estan:
     * saber cuales hay es la mitad del trabajo de quien va a publicar el que falta.
     */
    public static final class PuntoSinObservar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final PuntoDeRedondeo punto;

        PuntoSinObservar(
                Ejercicio ejercicio,
                PuntoDeRedondeo punto,
                PoliticasDeRedondeo.PuntoSinPolitica causa) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene la fila "
                            + llaveDe(punto)
                            + ". "
                            + causa.getMessage(),
                    causa);
            this.ejercicio = ejercicio;
            this.punto = punto;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llaveDe(punto));
        }
    }

    /**
     * La llave de la fila de un punto, {@code REDONDEO:‹punto›}.
     *
     * <p>Deja de ser privada para que {@link FaltaPublicar} componga con ella la llave del punto
     * que el dominio puro no sabe llavear (#691): {@code PuntoSinPolitica} conoce el punto y no el
     * ejercicio, y esta clase conoce el formato.
     */
    static String llaveDe(PuntoDeRedondeo punto) {
        return TIPO + ":" + punto;
    }
}
