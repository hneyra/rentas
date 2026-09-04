package kamayuk.rentas.parametros.infraestructura.web;

import java.time.Instant;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parametros del sistema: {@code GET /api/v1/seguridad/parametros}.
 *
 * <p>La ruta cuelga de {@code /seguridad} porque asi la declara el contrato —es una opcion del
 * modulo Seguridad del menu— pero el controlador vive en {@code parametros}, que es el contexto
 * dueno de estos datos. Ponerlo en {@code seguridad} habria significado que ese modulo consulte
 * tablas de otro, y esa es exactamente la clase de atajo que convierte un monolito modular en un
 * monolito.
 *
 * <p>Lo que muestra son los <b>conjuntos por ejercicio y su estado</b>, no las cifras una a una: la
 * pregunta que responde esta pantalla es «con que juego de valores se emitio este ejercicio», y esa
 * solo tiene respuesta a nivel de conjunto.
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad/parametros")
public class ParametrosController {

    private final AdministrarParametros administrar;

    public ParametrosController(AdministrarParametros administrar) {
        this.administrar = administrar;
    }

    @GetMapping
    @RequiereAcceso(acceso = "parametros", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ConjuntoResource> conjuntos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.conjuntos(paginacion.aPaginacion("ejercicio")), ConjuntoResource::de);
    }

    /**
     * Si un ejercicio esta parametrizado, para poder decirlo <b>antes</b> de calcular (#605).
     *
     * <p><b>Que arregla.</b> Ninguna ruta del contrato decia si un ejercicio tiene conjunto
     * sellado, asi que toda pantalla que calcula tenia que fallar para enterarse: se rellenaba el
     * formulario entero —y en el preconvenio tambien la observacion que exige la regla 10— para
     * recibir al final el 422 de {@code LectorDeParametros.EjercicioSinSellar}, que con D-02a
     * abierta es lo que contestan hoy todas las municipalidades.
     *
     * <p><b>El ejercicio va en la ruta y no en la consulta</b>, y no es indiferente: un parametro
     * de consulta que el controlador no supiera enumerar seria un 422 «parametro desconocido»
     * (#539), y con {@code {ejercicio}} en el camino el problema no existe. Fuera del rango 1990 a
     * 2100 lo rechaza el constructor de {@link Ejercicio} y el borde lo traduce a 422 nombrando el
     * rango; no es lo mismo que «ese ejercicio no esta sellado», que es un 200 diciendo que no.
     *
     * <h2>Por que el centinela {@link RequiereAcceso#SESION_PROPIA} y no el acceso {@code
     * parametros}</h2>
     *
     * <p>Porque exigir {@code parametros} —una opcion del <b>modulo Seguridad</b>, la que muestra
     * los valores— dejaria esta lectura fuera del alcance de quien la necesita: quien fracciona
     * tiene {@code fraccionamiento}, quien determina tiene {@code predial_individual}, y ninguno de
     * los dos tiene por que administrar los parametros del sistema. Otorgarselo en cada
     * implantacion seria invertir la separacion de funciones de REQ-03 —quien opera el sistema no
     * publica ni consulta las cifras con las que se calcula— para poder leer un booleano.
     *
     * <p>Y {@code oTambien} (#548) tampoco sirve: esta pensado para <b>dos</b> opciones que cubren
     * la misma lectura, con su motivo escrito una a una, y aqui las opciones que calculan pasan de
     * la docena — la decimotercera que se escribiera recibiria 403 sin que nada lo dijera.
     *
     * <p>El criterio que <b>si</b> aplica es el que el propio centinela enuncia (ADR-0013, REQ-03
     * §5): no revela nada que quien pregunta no pueda enumerar probando cada endpoint. Y aqui no es
     * una analogia, es literal: el 422 de cualquier operacion que calcule ya dice «El ejercicio
     * 2026 no tiene un conjunto de parametros sellado». Lo unico que cambia esta ruta es que se
     * pueda preguntar antes de rellenar el formulario, no quien puede saberlo. El precedente exacto
     * es {@code municipalidad_de_la_sesion} (#555): el estado de la instalacion no es de un modulo,
     * es del sistema entero, y sin el las doce pantallas que calculan no pueden decir por que no
     * pueden calcular.
     *
     * <p>Lo que si deja, y las otras dos lecturas de sesion no, es su <b>fila de {@code
     * ACCESO}</b>: esta admite un parametro, asi que quien recorra 1990 a 2100 deja su nombre en
     * cada intento. La escribe el caso de uso, en la misma transaccion que la lectura.
     */
    @GetMapping("/ejercicios/{ejercicio}")
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    public EjercicioParametrizadoResource ejercicio(@PathVariable int ejercicio) {
        return EjercicioParametrizadoResource.de(
                administrar.estadoDelEjercicio(new Ejercicio(ejercicio)));
    }

    /**
     * Si el ejercicio tiene conjunto sellado, y cual.
     *
     * <p><b>No lleva ninguna cifra</b>, y esa ausencia es la mitad de la decision: la pregunta que
     * contesta es si <b>hay conjunto sellado</b>, no con que valores — y no exactamente «si se
     * puede calcular»: sin conjunto no se puede, pero con el el calculo puede fallar igual si falta
     * dentro alguna llave que la regla pida (#547, #562). Lo que adelanta es la primera mitad, que
     * es la que hoy falla en todas las municipalidades. Tampoco lleva {@code usuarioSellado} ni
     * {@code fechaSellado} —eso es de la pantalla de parametros, detras de su permiso—; aqui basta
     * la identidad del conjunto, que es lo que una determinacion guarda para poder repetirse.
     *
     * @param ejercicio el que se pregunto, devuelto tal cual: el aviso de la pantalla nombra este
     *     numero y no «el ejercicio», que es lo que hoy no puede hacer
     * @param sellado si hay conjunto sellado vigente para ese ejercicio
     * @param conjuntoId identidad del conjunto sellado; nulo cuando no lo hay
     * @param version version sellada dentro del ejercicio; nula cuando no lo hay
     */
    public record EjercicioParametrizadoResource(
            int ejercicio, boolean sellado, @Nullable Long conjuntoId, @Nullable Integer version) {

        static EjercicioParametrizadoResource de(AdministrarParametros.EstadoDelEjercicio estado) {
            ConjuntoDeParametros sellado = estado.sellado();
            return new EjercicioParametrizadoResource(
                    estado.ejercicio().valor(),
                    estado.estaSellado(),
                    sellado == null ? null : sellado.id(),
                    sellado == null ? null : sellado.version());
        }
    }

    /**
     * Un conjunto y su estado.
     *
     * <p>No lleva ningun importe, asi que no le aplica la regla de {@code actualizadoA}: lo que se
     * publica aqui es la <b>identidad</b> del juego de parametros, no sus cifras.
     */
    public record ConjuntoResource(
            long id,
            int ejercicio,
            int version,
            String estado,
            @Nullable Instant fechaSellado,
            @Nullable String usuarioSellado) {

        static ConjuntoResource de(ConjuntoDeParametros conjunto) {
            return new ConjuntoResource(
                    conjunto.id() == null ? 0L : conjunto.id(),
                    conjunto.ejercicio().valor(),
                    conjunto.version(),
                    conjunto.estado().name(),
                    conjunto.fechaSellado(),
                    conjunto.usuarioSellado());
        }
    }
}
