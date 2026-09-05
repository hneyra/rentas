package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDelCiudadano;
import kamayuk.rentas.web.Api;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code portal_mi_situacion}: {@code GET /api/v1/portal/situacion} (RF-131, #57, ADR-0020).
 *
 * <h2>Sin un solo parametro, y eso es la decision</h2>
 *
 * <p>Lo que este endpoint sustituye es {@code GET /portal/deuda?doc=44218937}, que contestaba
 * «quien es esta persona y cuanto debe» a quien tecleara ocho digitos: el endpoint de enumeracion
 * del padron que mantuvo D-07 abierta. Aqui no hay nada que teclear. El sujeto sale del claim
 * {@code numero_documento} del token del realm del ciudadano, validado criptograficamente por la
 * cadena del portal, exactamente igual que {@code municipalidad_id} para un funcionario (ADR-0005).
 *
 * <p>Y una sola ida y vuelta: el servidor recorre, compone y suma (RNF-083). Ni paginacion ni
 * filtros —para una persona el listado nunca es largo, y una pagina dos exigiria mantener el
 * recorrido entre peticiones—.
 *
 * <h2>La cadena por la que llega, y por la que no puede llegar otra cosa</h2>
 *
 * <p>{@code /api/v1/portal/**} lo sirve una {@link kamayuk.rentas.plataforma.SeguridadWeb} propia,
 * ordenada antes que la general y con su decodificador apuntando <b>solo</b> al emisor del realm
 * del ciudadano: un token de funcionario recibe 401 aqui, y uno de ciudadano recibe 401 en
 * cualquier otra ruta de la API. Bajo este camino no corre el filtro de tenant sino el del
 * documento, asi que un endpoint de funcionario servido aqui por descuido correria sin contexto de
 * municipalidad y fallaria ruidosamente en la base.
 *
 * <h2>El centinela, y por que no es una opcion del catalogo</h2>
 *
 * <p>{@code RequiereAcceso.CIUDADANO}: el ciudadano no tiene fila en {@code usuario} y no hay
 * privilegio que comprobar (ADR-0013 es el precedente, con {@code SESION_PROPIA}). El guardia lo
 * admite <b>solo</b> si la peticion llego por la cadena del ciudadano, y una regla de ArchUnit
 * impide que un endpoint del catalogo se anote con el.
 *
 * <p>Y la opcion {@code portal} de las 134 <b>no</b> se sirve por aqui: sigue siendo la vista del
 * funcionario (ADR-0016 §3). Servirle esto a un funcionario seria devolver el endpoint que ADR-0020
 * retira.
 */
@RestController
@RequestMapping(Api.RAIZ + "/portal/situacion")
public class PortalController {

    private final ConsultaDelCiudadano consulta;

    public PortalController(ConsultaDelCiudadano consulta) {
        this.consulta = consulta;
    }

    /**
     * No lleva {@code @Transactional}, y no es un olvido: lo llevan las <b>ramas</b>, una por
     * municipalidad. Una transaccion aqui haria que la municipalidad que falla se llevara por
     * delante a todas las demas (la leccion de #54 y #72).
     */
    @GetMapping
    @RequiereAcceso(acceso = RequiereAcceso.CIUDADANO, privilegio = Privilegio.LECTURA)
    public SituacionDelCiudadanoResource situacion() {
        // Hoy, del reloj inyectado y no de LocalDate.now() (regla 6). Se resuelve UNA vez y
        // se pasa igual a todas las ramas: sin eso, el total sumaria instantes distintos.
        return SituacionDelCiudadanoResource.de(consulta.situacion(consulta.hoy()));
    }
}
