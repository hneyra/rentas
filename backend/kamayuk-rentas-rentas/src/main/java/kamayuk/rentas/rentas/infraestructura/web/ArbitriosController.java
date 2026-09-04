package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Arbitrios municipales: {@code GET /api/v1/rentas/arbitrios?ejercicio=2026} (#31, RF-022).
 *
 * <p>Solo lectura: la determinación vive en {@code DeterminarArbitrios} y no se publica todavía
 * —igual que {@code beneficios} en este mismo módulo—; el contrato no declara ningún {@code POST}
 * en esta ruta. <b>Nada escribe {@code determinacion_arbitrio} en producción</b>: ese caso de uso
 * no lo llama ningún controlador ni ningún proceso, así que hoy esta consulta lee una tabla que
 * ninguna instalación llena. Publicarlo es otro issue y está bloqueado por D-02b —sus tasas son de
 * ordenanza local—, con el precedente de #51: se falla con un 422 que nombra la llave que falta,
 * nunca con un valor por omisión.
 *
 * <h2>El ejercicio tiene dos nombres, y el canónico es {@code ejercicio} (#541)</h2>
 *
 * <p>El contrato declara los dos —{@code anio} lo arrastra el {@code endpoint} del prototipo y
 * {@code ejercicio} es el filtro que la pantalla dibuja— y este controlador <b>solo leía {@code
 * anio}</b>: el desplegable «Ejercicio» se tecleaba, viajaba y no cambiaba nada. Ahora se leen los
 * dos y manda {@code ejercicio}, que es como se llama el dato en el dominio, en la columna y en el
 * otro endpoint del predial ({@code /rentas/predial/corridas/ultima}).
 *
 * <h2>«Zona» y «Uso» se rechazan con 422 en vez de ignorarse (#541)</h2>
 *
 * <p>Eran los otros dos parámetros declarados y no leídos: se tecleaban en la pantalla, viajaban en
 * la URL y la tabla volvía igual, que es la peor de las respuestas posibles —quien filtró cree
 * estar mirando una parte—.
 *
 * <p><b>Y no se sirven porque los valores que el desplegable ofrece no existen en el sistema.</b>
 * La zona vive en {@code sector.zona} (V1), es texto libre por municipalidad y lo que la carga real
 * escribe es «Urbana»/«Rustica», no «Zona 1»…«Zona 4»; el uso vive en {@code ficha_catastral.uso},
 * también libre, y las fichas traen «Casa habitacion», «Tienda de artesania». Es el cruce de
 * vocabularios de #427 —«ACTIVA» no es VIGENTE— con un agravante: allí había un enumerado del que
 * computar la lista buena, y aquí el dominio es <b>abierto</b>, así que no hay lista que ofrecer.
 * Servirlos contra esos valores devolvería la tabla vacía, y una tabla vacía se lee como «no hay
 * cuotas».
 *
 * <p>El parámetro <b>se queda en el contrato y se rechaza</b>, que es lo que ya hacen {@code
 * ConsultaController} con «Conciliada con rentas» (#322) y {@code ResumenesDeTransitoController}
 * con «Agrupado por» y «Caja» (#398). Retirarlo del contrato también cierra el defecto —se midió, y
 * {@code --comprobar} queda en verde—, pero exige que la pantalla deje de dibujarlo vivo en el
 * mismo cambio: un parámetro retirado sin eso deja el control tecleándose y sin viajar, en silencio
 * (el defecto de #431). <b>Lo que la pantalla tiene que hacer con estos dos es dibujarlos
 * bloqueados con su motivo</b>, como los cinco de #398.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/arbitrios")
@RequiereAcceso(acceso = "arbitrios", privilegio = Privilegio.LECTURA)
public class ArbitriosController {

    private static final String ORDEN_POR_OMISION = "fechaCalculo";

    private final ConsultasDeRentas consulta;
    private final Clock reloj;

    public ArbitriosController(ConsultasDeRentas consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<ArbitrioResource> buscar(
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String anio,
            @RequestParam(required = false) @Nullable String codigoPredial,
            @RequestParam(required = false) @Nullable String zona,
            @RequestParam(required = false) @Nullable String uso,
            ParametrosDePaginacion paginacion) {

        rechazarLoQueNoSeSirve(zona, uso);

        CriterioDeArbitrio criterio =
                new CriterioDeArbitrio(
                        ejercicioDe(FiltroDeLaConsulta.elCanonicoOSuAlias(ejercicio, anio)),
                        codigoPredial);

        return RespuestaPaginada.de(
                consulta.arbitrios(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ArbitrioResource::de);
    }

    /**
     * Los dos filtros que esta consulta no puede servir, dichos en vez de ignorados (#541).
     *
     * <p>Se leen —{@code @RequestParam}— para poder rechazarlos: un parámetro que el controlador no
     * declara lo descarta Spring en silencio, la consulta sale sin acotar y el listado vuelve
     * entero. El mensaje dice <b>por qué</b> y por dónde se sale, porque quien filtra por zona está
     * haciendo una pregunta legítima que este servicio no sabe contestar.
     */
    private static void rechazarLoQueNoSeSirve(@Nullable String zona, @Nullable String uso) {
        if (zona != null && !zona.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro «zona» no se puede servir: la zona de un predio la pone su sector y"
                            + " cada municipalidad la escribe con sus propias palabras, asi que"
                            + " ninguna de las que ofrece el desplegable acota nada. Acote por"
                            + " «codigoPredial»");
        }
        if (uso != null && !uso.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro «uso» no se puede servir: el uso lo guarda la ficha catastral como"
                            + " texto libre y no coincide con el vocabulario del desplegable."
                            + " Acote por «codigoPredial»");
        }
    }

    /**
     * El ejercicio pedido; sin ninguno de los dos nombres, el del reloj del servidor.
     *
     * <p>Lo que no es un año se rechaza <b>nombrando el parámetro</b>: hasta #541 el {@code
     * NumberFormatException} salía como un 422 con el mensaje de Java —«For input string: "dos
     * mil"»—, que no dice cuál de los parámetros de la petición estaba mal.
     */
    private Ejercicio ejercicioDe(@Nullable String pedido) {
        if (pedido == null || pedido.isBlank()) {
            return Ejercicio.de(LocalDate.now(reloj));
        }
        try {
            return new Ejercicio(Integer.parseInt(pedido.strip()));
        } catch (IllegalArgumentException mal) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El parametro «ejercicio» tiene que ser un ano: '" + pedido + "'");
        }
    }
}
