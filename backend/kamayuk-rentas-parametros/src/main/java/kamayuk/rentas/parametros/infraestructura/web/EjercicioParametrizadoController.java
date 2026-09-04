package kamayuk.rentas.parametros.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Si un ejercicio esta parametrizado, para poder decirlo <b>antes</b> de calcular (#605).
 *
 * <h2>Que arregla, y por que sobrevive a la extraccion</h2>
 *
 * <p>Ninguna otra ruta del contrato dice si un ejercicio tiene conjunto sellado, asi que toda
 * pantalla que calcula tendria que fallar para enterarse: se rellenaria el formulario entero —y en
 * el preconvenio tambien la observacion que exige la regla 10— para recibir al final el 422 de
 * {@link LectorDeParametros.EjercicioSinSellar}, que con D-02a abierta es lo que contestan hoy
 * todas las municipalidades. Las doce pantallas que calculan siguen estando en {@code rentas}, asi
 * que la pregunta se sigue contestando aqui.
 *
 * <h2>Lo que SI se fue con `normativa` en P5B</h2>
 *
 * <p>El listado paginado de conjuntos —{@code GET /seguridad/parametros}, la pantalla que
 * administra los juegos de valores— <b>no</b> se sirve ya desde aqui, y no por descuido: este
 * sistema solo tiene los conjuntos que ha <b>descargado</b>, asi que un listado local diria «estos
 * son los conjuntos de la municipalidad» cuando lo cierto seria «estos son los que hemos bajado».
 * Es la clase de cifra plausible y equivocada que no se distingue de la correcta. La pantalla es de
 * {@code normativa}, que es donde estan de verdad; queda como hueco declarado en
 * `docs/00-gobierno/P5B-extraccion.md` §7, porque su interfaz todavia no existe.
 *
 * <h2>Por que el centinela {@link RequiereAcceso#SESION_PROPIA}</h2>
 *
 * <p>El mismo motivo de siempre: exigir {@code parametros} —una opcion del modulo Seguridad—
 * dejaria esta lectura fuera del alcance de quien la necesita, que es quien fracciona, quien
 * determina y quien liquida. Y no revela nada que quien pregunta no pueda enumerar probando cada
 * endpoint: el 422 de cualquier operacion que calcule ya dice «El ejercicio 2026 no tiene un
 * conjunto de parametros sellado».
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad/parametros")
public class EjercicioParametrizadoController {

    private final LectorDeParametros lector;

    public EjercicioParametrizadoController(LectorDeParametros lector) {
        this.lector = lector;
    }

    /**
     * Si el ejercicio tiene conjunto sellado, y cual.
     *
     * <p><b>No lleva ninguna cifra.</b> La pregunta que contesta es si <b>hay conjunto sellado</b>,
     * no con que valores — y no exactamente «si se puede calcular»: sin conjunto no se puede, pero
     * con el el calculo puede fallar igual si falta dentro alguna llave que la regla pida (#547,
     * #562).
     *
     * <p>El ejercicio va en la ruta y no en la consulta, y no es indiferente: un parametro de
     * consulta que el controlador no supiera enumerar seria un 422 «parametro desconocido» (#539).
     * Fuera del rango 1990 a 2100 lo rechaza el constructor de {@link Ejercicio} y el borde lo
     * traduce a 422 nombrando el rango; no es lo mismo que «ese ejercicio no esta sellado», que es
     * un 200 diciendo que no.
     *
     * <p><b>Y `normativa` caido NO se contesta con un 200 que diga «no esta sellado»</b>: eso es
     * una frase falsa que quien atiende no puede distinguir de la verdadera, y le haria buscar una
     * ordenanza cuando lo que falta es un despliegue. {@link
     * kamayuk.rentas.parametros.dominio.PublicadorDeNormativa.NormativaInalcanzable} sale del
     * controlador sin capturar y el borde la traduce; el repliegue a la cache lo decide el lector,
     * que es quien sabe si hay algo cacheado.
     */
    @GetMapping("/ejercicios/{ejercicio}")
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    public EjercicioParametrizadoResource ejercicio(@PathVariable int ejercicio) {
        Ejercicio elPedido = new Ejercicio(ejercicio);
        try {
            IdentificadorDeConjunto conjunto = lector.conjuntoVigenteEn(elPedido);
            return new EjercicioParametrizadoResource(
                    ejercicio, true, conjunto.valor(), lector.porConjunto(conjunto).version());
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return new EjercicioParametrizadoResource(ejercicio, false, null, null);
        }
    }

    /**
     * Si el ejercicio tiene conjunto sellado, y cual.
     *
     * @param ejercicio el que se pregunto, devuelto tal cual: el aviso de la pantalla nombra este
     *     numero y no «el ejercicio»
     * @param sellado si hay conjunto sellado vigente para ese ejercicio
     * @param conjuntoId identidad del conjunto sellado; nulo cuando no lo hay
     * @param version version sellada dentro del ejercicio; nula cuando no lo hay
     */
    public record EjercicioParametrizadoResource(
            int ejercicio, boolean sellado, @Nullable Long conjuntoId, @Nullable Integer version) {}
}
