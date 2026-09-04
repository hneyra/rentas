package kamayuk.rentas.parametros;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametroQueFalta;
import kamayuk.rentas.web.ProblemaDeNegocio;

/**
 * El <b>unico</b> sitio donde una cifra normativa sin publicar se convierte en una respuesta de
 * error (#691, #723).
 *
 * <p>Son dos codigos y no uno, y cual toca lo decide <b>que se pidio</b>: {@link #problema} da el
 * {@code 422} de un calculo que no se pudo hacer, y {@link #noEncontrado} el {@code 404} de un
 * cuadro publicado que no esta. Los dos llevan el mismo miembro, que es lo unico que un programa
 * lee.
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>#604 puso el miembro {@code parametroQueFalta} en el cuerpo del 422 y lo cableo en las tres
 * capturas de {@code ConvenioController}, con un ayudante privado. Fuera de tesoreria las demas
 * rutas seguian contestando un 422 con {@code codigo} y {@code mensaje} y nada mas, o sea
 * <b>indistinguible del de un campo que falta</b>. Dentro de tesoreria la ausencia del miembro
 * significa «es un campo de la peticion, se corrige aqui»; aplicada fuera, esa regla es falsa y
 * manda a quien atiende a buscar en el formulario un dato que no esta mal.
 *
 * <p>Un ayudante privado por controlador habria sido la copia numero veintitres de las mismas seis
 * lineas, y la copia que se quedara atras seria invisible: el sintoma de «este 422 no lleva el
 * miembro» es exactamente el mismo que el de «este 422 es de un campo». Por eso hay uno solo y una
 * guarda que exige usarlo ({@code DiscriminadorDeLoQueFaltaPublicarTest}).
 *
 * <h2>Por que vive en {@code kamayuk-rentas-parametros}</h2>
 *
 * <p>Porque es el unico modulo que puede nombrar a la vez las dos mitades: {@link
 * ParametroSinPublicar} —suyo— y {@link ProblemaDeNegocio} de {@code kamayuk-rentas-plataforma},
 * del que todo contexto depende. Al reves no se puede: {@code kamayuk-rentas-plataforma} es la base
 * del grafo y no depende de ningun contexto acotado, asi que {@code kamayuk.rentas.web} no puede
 * nombrar {@code ParametroSinPublicar} (lo dice el javadoc de {@link ParametroQueFalta}).
 *
 * <h2>El tipo es la guarda, no el nombre del metodo</h2>
 *
 * <p>El parametro esta acotado a los dos limites a la vez —{@code RuntimeException} para poder leer
 * su mensaje y {@link ParametroSinPublicar} para poder leer su llave—, que es exactamente lo que da
 * el tipo de un {@code catch} multiple. Con eso, meter en esa lista una excepcion que no publique
 * su ejercicio <b>no compila</b>, en vez de producir un 422 sin miembro que nadie distinguiria del
 * de un campo ausente.
 */
public final class FaltaPublicar {

    private FaltaPublicar() {}

    /**
     * El 422 de una cifra normativa que no esta publicada, con su discriminador.
     *
     * <p>El mensaje sigue siendo el de la propia excepcion: ya esta redactado en lenguaje del
     * dominio y nombra el ejercicio o la llave. Lo que se anade es el mismo dato en forma legible
     * por programa.
     */
    public static <E extends RuntimeException & ParametroSinPublicar> ProblemaDeNegocio problema(
            E falta) {
        return con(CodigoDeError.VALIDACION, falta);
    }

    /**
     * El mismo discriminador sobre un {@code 404}, para las tres lecturas que piden un <b>cuadro
     * publicado</b> y no un calculo (#723).
     *
     * <h2>Por que hay dos codigos y no uno</h2>
     *
     * <p>{@code GET /catastro/tablas/aranceles?anio=2026} nombra un documento —la tabla de
     * aranceles sellada de 2026—, y cuando no hay ninguna «no esta» es literalmente lo que pasa.
     * Los 422 de esta familia estan todos detras de una peticion que el servidor <b>intento
     * ejecutar</b>: determinar un predial, abrir un convenio, dictar una REC. Aqui no habia nada
     * que ejecutar. Es la distincion que #540 y #547 ya dejaron escrita —«alli se lee un cuadro,
     * aqui se pide un calculo»— y que este metodo hace posible sin repetir el ayudante.
     *
     * <p>Y hay un motivo medido para no unificarlos en 422: <b>en esa misma ruta el 422 ya
     * significa otra cosa</b>. {@code ?anio=1800} construye un {@link
     * kamayuk.rentas.dominio.Ejercicio} fuera de rango, sale como {@code IllegalArgumentException}
     * y {@code ManejadorDeErrores} lo traduce a {@code 422 VALIDACION} — un error que quien atiende
     * corrige tecleando un ano de verdad. Mover ahi «el ejercicio no esta sellado» pondria las dos
     * cosas bajo el mismo codigo justo en la ruta donde conviven, y el discriminador tendria que
     * hacer solo el trabajo que hoy hacen entre los dos.
     *
     * <h2>Lo que el codigo NO decide</h2>
     *
     * <p>Ni el estado ni el mensaje le dicen a un programa que hacer: eso lo dice el miembro. Por
     * eso el 404 de aqui lo lleva y el 404 de «ese contribuyente no esta en el padron» no —y esa
     * diferencia es lo unico que los separa—.
     */
    public static <E extends RuntimeException & ParametroSinPublicar>
            ProblemaDeNegocio noEncontrado(E falta) {
        return con(CodigoDeError.NO_ENCONTRADO, falta);
    }

    private static <E extends RuntimeException & ParametroSinPublicar> ProblemaDeNegocio con(
            CodigoDeError codigo, E falta) {
        Objects.requireNonNull(falta, "Traducir «falta publicar» exige la excepcion que lo dice");
        int ejercicio = falta.ejercicio().valor();
        ParametroQueFalta discriminador =
                falta.llave()
                        .map(llave -> ParametroQueFalta.llave(ejercicio, llave))
                        .orElseGet(() -> ParametroQueFalta.conjuntoDelEjercicio(ejercicio));
        return new ProblemaDeNegocio(codigo, mensajeDe(falta), discriminador);
    }

    /**
     * El mismo 422 para la <b>unica</b> de estas excepciones que no puede declarar {@link
     * ParametroSinPublicar}: la del dominio puro.
     *
     * <p>{@link PoliticasDeRedondeo.PuntoSinPolitica} vive en {@code
     * kamayuk-rentas-dominio-compartido} y no sabe de que ejercicio salieron las politicas —no
     * puede saberlo: la capa {@code dominio} no mira la base ni la configuracion (regla 7)—. Quien
     * si lo sabe es quien resolvio el conjunto sellado, y por eso el ejercicio entra por argumento.
     *
     * <p>La llave se compone con {@link PoliticasDeRedondeoSelladas#llaveDe} a partir de {@link
     * PoliticasDeRedondeo.PuntoSinPolitica#punto()}, o sea de las <b>dos</b> mitades que existen y
     * no de una clave inventada. Nunca sale el {@code TIPO} solo: eso significa «falta el bloque
     * entero» ({@code SinPuntosObservados}) y aqui se sabe exactamente cual falta, porque lo pidio
     * el calculo.
     */
    public static ProblemaDeNegocio problema(
            Ejercicio ejercicio, PoliticasDeRedondeo.PuntoSinPolitica sinPolitica) {
        Objects.requireNonNull(ejercicio, "El dominio no sabe de que ejercicio son sus politicas");
        Objects.requireNonNull(sinPolitica, "Traducir «falta publicar» exige la excepcion");
        return new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                mensajeDe(sinPolitica),
                ParametroQueFalta.llave(
                        ejercicio.valor(),
                        PoliticasDeRedondeoSelladas.llaveDe(sinPolitica.punto())));
    }

    /** Una excepcion sin mensaje no deja un {@code null} en la respuesta. */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null || mensaje.isBlank() ? "El valor recibido no es valido" : mensaje;
    }
}
