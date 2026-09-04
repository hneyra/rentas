package kamayuk.rentas.seguridad.infraestructura.web;

import java.util.Arrays;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quien tiene un privilegio sobre un acceso: {@code GET
 * /api/v1/seguridad/accesos/{codigo}/usuarios?privilegio=ESPECIAL} (#583).
 *
 * <h2>La pregunta inversa, y por que hacia falta publicarla</h2>
 *
 * <p>{@link PermisosDeUsuarioController} contesta que puede <b>una</b> cuenta. Preguntarlo del
 * padron costaba una peticion por usuario —medido en el issue: 200 peticiones y ~4,2 MB de JSON
 * para pintar una insignia—, y no habia forma de acotarlo: el contrato de esa operacion declara un
 * solo parametro, el {@code id} de la ruta.
 *
 * <p><b>Y no se puede componer recorriendo los grupos</b>, que es la salida que parece obvia. La
 * excepcion propia de una cuenta <b>sustituye</b> a lo que su grupo le da: alguien cuyo grupo no
 * tiene {@code ESPECIAL} puede tenerlo por excepcion, y al reves. Esa mitad no la encuentra ningun
 * recorrido por grupos, y hasta hoy ninguna lectura listaba las excepciones.
 *
 * <h2>La forma, y las dos cosas que decide</h2>
 *
 * <p><b>El acceso va en la ruta y el privilegio en la consulta.</b> La ruta es simetrica con las
 * dos inversas que ya existen —{@code /usuarios/{id}/grupos} (#543) y {@code
 * /grupos/{grupo}/miembros} (#582)—: el sujeto de la pregunta es el acceso, y lo que se filtra
 * sobre el es cual de los siete privilegios. Un {@code codigo} que no existe en esta municipalidad
 * es <b>404 nombrandolo</b>, no una pagina vacia: no tener titulares y no existir son dos
 * respuestas distintas.
 *
 * <p><b>{@code privilegio} es obligatorio y su vocabulario es cerrado.</b> Omitirlo no significa
 * «todos»: significa que no se pregunto nada, y contestar el padron entero de quien tiene algo
 * sobre esa opcion seria otra pregunta. Y una palabra que no sea uno de los siete se rechaza con
 * 422 enumerandolos, en vez de devolver la lista vacia — que se leeria como «nadie tiene Especial»,
 * que es la lectura plausible y equivocada por la que #427 se nego a traducir «ACTIVA» a «VIGENTE».
 *
 * <p>El acceso que exige es {@code permisos} con {@code LECTURA}, el mismo que la matriz de un
 * usuario y el que gobierna la pantalla que administra permisos: enumerar quien tiene la llave de
 * la caja es administrar permisos. Va <b>en el metodo</b> aunque la clase tenga un solo endpoint,
 * por lo mismo que en el resto del modulo — y cual sea no lo puede ver ArchUnit, asi que lo fija
 * una prueba (#431, #543, #555).
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad/accesos/{codigo}/usuarios")
public class TitularesDelPrivilegioController {

    /** El orden por omision: la cuenta, que es como la pantalla las lista. */
    private static final String ORDEN_POR_OMISION = "cuenta";

    private final AdministrarPermisos administrar;

    public TitularesDelPrivilegioController(AdministrarPermisos administrar) {
        this.administrar = administrar;
    }

    @GetMapping
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.TitularDelPrivilegioResource> quienesTienen(
            @PathVariable("codigo") String codigo,
            @RequestParam(required = false) @Nullable String privilegio,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                administrar.quienesTienen(
                        codigo,
                        exigirPrivilegio(privilegio),
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                Recursos.TitularDelPrivilegioResource::de);
    }

    /**
     * El privilegio pedido, o 422 diciendo cual es el problema.
     *
     * <p>Se admite en cualquier caja —{@code especial} y {@code ESPECIAL} son la misma palabra, y
     * asi lo lee ya el {@code PUT} que fija los niveles— pero <b>no se traduce ninguna otra</b>: un
     * sinonimo aceptado aqui devolveria la lista de otro privilegio sin que nada lo dijera.
     */
    private static Privilegio exigirPrivilegio(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Falta el parametro 'privilegio': se pregunta por uno de los siete, no por"
                            + " todos. Los siete son "
                            + Arrays.toString(Privilegio.values()));
        }
        try {
            return Privilegio.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Privilegio desconocido: '"
                            + texto
                            + "'. Los siete son "
                            + Arrays.toString(Privilegio.values()));
        }
    }
}
