package kamayuk.rentas.seguridad.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos;
import kamayuk.rentas.seguridad.dominio.Permiso;
import kamayuk.rentas.seguridad.dominio.PermisoEfectivo;
import kamayuk.rentas.web.Api;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La matriz de permisos <b>efectivos</b> de un usuario: {@code GET
 * /api/v1/seguridad/usuarios/{id}/permisos} (#543).
 *
 * <h2>Por que no es el {@code GET} de {@link PermisosController} con otro sujeto</h2>
 *
 * <p>Aquel devuelve lo <b>configurado</b> de un grupo: la matriz que se edita y se guarda con el
 * {@code PUT} de la misma ruta. Este devuelve lo que un usuario <b>puede</b>, ya resuelto: la
 * excepcion de usuario sustituye al grupo entero para ese acceso —otorgue o niegue— y la union de
 * grupos manda cuando no hay excepcion. Son dos preguntas distintas y la segunda no se compone con
 * la primera sin volver a implementar la precedencia.
 *
 * <p><b>Y esa es la decision de forma.</b> Publicar las dos listas por separado —«los del grupo» y
 * «los del usuario»— obliga a quien pregunta a reconciliarlas, y la regla que tendria que aplicar
 * es justo la que no se puede equivocar: la interfaz la tenia invertida (calculaba {@code on =
 * esPropio || esHeredado}), que convierte una excepcion que <b>restringe</b> en una que amplia. Por
 * eso cada fila trae {@code origen} y, cuando lo hereda de un solo grupo, cual.
 *
 * <p>El acceso es {@code permisos} —la misma opcion del catalogo que la matriz de grupo— y el
 * privilegio va <b>en cada metodo</b>, nunca en la clase: las dos lecturas piden {@code LECTURA} y
 * la escritura de la excepcion (#585) pide {@code REGISTRO}, y una anotacion de clase habria dejado
 * que la escritura heredara en silencio el privilegio de una lectura. {@code verificarArquitectura}
 * exige que la anotacion exista «en la clase <b>o</b> en cada endpoint», no que diga lo correcto:
 * cual acceso y cual privilegio declara cada una lo fija {@code AccesoDeLasLecturasDeUsuarioTest}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad/usuarios/{id}/permisos")
public class PermisosDeUsuarioController {

    private final AdministrarPermisos administrar;

    public PermisosDeUsuarioController(AdministrarPermisos administrar) {
        this.administrar = administrar;
    }

    @GetMapping
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.LECTURA)
    public List<Recursos.PermisoEfectivoResource> deUsuario(@PathVariable("id") long usuario) {
        List<Recursos.PermisoEfectivoResource> resultado = new ArrayList<>();
        for (PermisoEfectivo permiso : administrar.efectivosDeUsuario(usuario)) {
            resultado.add(Recursos.PermisoEfectivoResource.de(permiso));
        }
        return resultado;
    }

    /**
     * Lo <b>configurado</b> de esa cuenta: {@code GET
     * /api/v1/seguridad/usuarios/{id}/permisos/configurados} (#583).
     *
     * <h2>Por que es otra ruta y no un parametro de la de arriba</h2>
     *
     * <p>Porque es otra pregunta, no otra respuesta a la misma. La de arriba <b>no cambia</b>: a
     * una cuenta deshabilitada le sigue contestando la lista vacia, con la misma regla que el
     * guardia, porque ensenar en la matriz privilegios que despues responden 403 seria peor que no
     * ensenar nada. Un parametro que apagara esa guarda convertiria una ruta en dos respuestas
     * distintas segun quien la llame, y la que se lee en pantalla acabaria dependiendo de un
     * booleano.
     *
     * <p>Lo que aqui se puede contestar y alli no: <b>que cuenta deshabilitada conserva
     * permisos</b>. Hasta este issue las dos situaciones —conservarlos y no haberlos tenido nunca—
     * eran el mismo JSON, y la diferencia importa porque deshabilitar no retira nada: rehabilitar
     * la cuenta se lo devuelve entero, y quien audita quiere saber que volveria a poder.
     *
     * <p>La respuesta lleva {@code surtenEfectoHoy} y no es adorno: las filas son campo a campo las
     * mismas que las de la matriz efectiva, y sin esa marca las dos lecturas son indistinguibles
     * para quien se equivoque de ruta.
     *
     * <p>Mismo acceso y mismo privilegio que la de arriba —{@code permisos} con {@code LECTURA}—,
     * declarado <b>en el metodo</b>: es lo que hace que una escritura anadida despues no herede en
     * silencio el privilegio de una lectura.
     */
    @GetMapping("/configurados")
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.LECTURA)
    public Recursos.PermisosConfiguradosResource configuradosDeUsuario(
            @PathVariable("id") long usuario) {
        return Recursos.PermisosConfiguradosResource.de(administrar.configuradosDeUsuario(usuario));
    }

    /**
     * La <b>excepcion</b> de esa cuenta: {@code PUT /api/v1/seguridad/usuarios/{id}/permisos}
     * (#585).
     *
     * <h2>Que faltaba</h2>
     *
     * <p>{@code AdministrarPermisos.fijarParaUsuario} existia desde el primer dia —transaccional,
     * con su {@link Observacion} y con la guarda del ultimo administrador— y <b>no la llamaba
     * nadie</b>: la ruta solo tenia {@code get}, de modo que la unica forma de negarle un
     * privilegio a una persona sin sacarla de su grupo era el SQL directo, que no deja fila de
     * auditoria con quien lo decidio (regla 10, RNF-052) ni pasa por esa guarda.
     *
     * <h2>La semantica, que es la del {@code PUT} del grupo salvo en una cosa</h2>
     *
     * <p>Mismo cuerpo y mismo <i>upsert</i> por acceso: <b>lo que no viene se queda como
     * estaba</b>, porque una lista parcial no puede traducirse en retirar en silencio todo lo
     * demas. Lo que cambia es lo que significa {@code "privilegios": []}: en el grupo es «este
     * grupo no otorga nada aqui», y en la cuenta es una <b>negacion</b> que sustituye a lo que el
     * grupo le da. Por eso no se borra la fila —ademas de que aqui no se borra nada (regla 4)—: sin
     * ella el acceso volveria a heredar del grupo, y «se le nego expresamente» y «nunca lo tuvo»
     * volverian a leerse igual, que es justo lo que {@code GET} de esta misma ruta existe para
     * distinguir.
     *
     * <p><b>Puede contestar 409</b>, con la misma guarda que la matriz del grupo y contando con la
     * precedencia: negarle por excepcion {@code permisos}/{@code REGISTRO} al unico administrador
     * deja la municipalidad sin quien administre, y de ahi no se sale por el sistema. La
     * comprobacion corre <b>despues</b> del guardado y dentro de la misma transaccion, asi que lo
     * que deshace el cambio es el rollback.
     *
     * <p>El acceso es {@code permisos} con <b>{@code REGISTRO}</b>, y va en el metodo: heredar el
     * {@code LECTURA} de las lecturas de arriba dejaria que quien solo puede mirar la matriz la
     * escribiera, y {@code verificarArquitectura} no ve <i>cual</i> acceso ni <i>cual</i>
     * privilegio declara una anotacion.
     */
    @PutMapping
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.REGISTRO)
    public List<PermisosController.PermisoResource> fijar(
            @PathVariable("id") long usuario,
            @RequestBody PermisosController.CambioDePermisos cambio) {

        Observacion observacion = PermisosController.observacionDe(cambio.observacion());
        List<PermisosController.PermisoResource> resultado = new ArrayList<>();

        for (PermisosController.NivelDeAcceso nivel :
                PermisosController.nivelesDe(cambio.niveles())) {
            String acceso = PermisosController.accesoDe(nivel);
            Permiso permiso =
                    administrar.fijarParaUsuario(
                            usuario, acceso, PermisosController.privilegios(nivel), observacion);
            resultado.add(PermisosController.PermisoResource.de(permiso, acceso));
        }
        return resultado;
    }
}
