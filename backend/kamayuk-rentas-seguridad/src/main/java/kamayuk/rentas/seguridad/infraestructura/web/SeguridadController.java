package kamayuk.rentas.seguridad.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las dos lecturas del catalogo que la interfaz necesita para componer su arbol: {@code GET
 * /seguridad/modulos} y {@code GET /seguridad/accesos} (I-3 de `rentas-web`).
 *
 * <h2>Lo que este controlador ya no tiene, y por que (ADR-0039, etapa 4)</h2>
 *
 * <p>Hasta la etapa 4 aqui vivian ademas las once escrituras de administracion —altas, bajas,
 * reactivaciones y vigencias de grupos y usuarios, la afiliacion y las dos matrices de permisos—
 * con sus lecturas. Eso es <b>administrar la autorizacion</b>, y la autorizacion es un sistema
 * propio: se administra en {@code identidad} y llega aqui por su buzon. Entre la etapa 2 y esta
 * hubo dos sitios que administraban, y esta es la mitad que lo cierra: las cuatro opciones del
 * catalogo que las servian —{@code usuarios}, {@code grupos}, {@code miembros} y {@code permisos}—
 * se retiran con ellas.
 *
 * <p>Lo que queda lee la <b>copia local</b>, que sigue siendo la que autoriza (ADR-0039 §«No esta
 * en el camino caliente»): el guardia no cambia y la interfaz tampoco. <b>Cada operacion declara su
 * acceso por separado</b> y no la clase entera: son dos opciones distintas del catalogo.
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad")
public class SeguridadController {

    private final LecturaDeLaCopiaLocal copiaLocal;

    public SeguridadController(LecturaDeLaCopiaLocal copiaLocal) {
        this.copiaLocal = copiaLocal;
    }

    @GetMapping("/modulos")
    @RequiereAcceso(acceso = "modulos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.ModuloResource> modulos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                copiaLocal.modulos(paginacion.aPaginacion("orden")), Recursos.ModuloResource::de);
    }

    @GetMapping("/accesos")
    @RequiereAcceso(acceso = "accesos", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<Recursos.AccesoResource> accesos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                copiaLocal.accesos(paginacion.aPaginacion("codigo")), Recursos.AccesoResource::de);
    }
}
