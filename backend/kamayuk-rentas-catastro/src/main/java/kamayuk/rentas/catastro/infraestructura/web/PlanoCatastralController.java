package kamayuk.rentas.catastro.infraestructura.web;

import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDelPlanoCatastral;
import kamayuk.rentas.catastro.dominio.AcotacionDelPlano;
import kamayuk.rentas.catastro.dominio.FiltroDelPlano;
import kamayuk.rentas.compartido.MarcoGeografico;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El plano catastral: {@code GET /api/v1/catastro/predios/plano} (ADR-0022, #536).
 *
 * <h2>Por que es un controlador propio y no un metodo mas de {@link PredioController}</h2>
 *
 * <p>Por el permiso. {@code PredioController} declara {@code actualizacion_catastro} en la clase
 * —es la pantalla de saneamiento— y el plano exige {@code consulta_fichas} con {@code LECTURA},
 * porque <b>el mapa es la busqueda de un predio por otro camino</b> (ADR-0022, «Consecuencias»):
 * pedir el permiso de actualizar el catastro dejaria sin mapa a quien solo mira. Meterlo alli
 * obligaria a que el metodo contradijera a su clase, que es justo lo que #431 dejo dicho que no se
 * puede dar por heredado.
 *
 * <h2>Los cuatro parametros viajan por la CONSULTA</h2>
 *
 * <p>Los cuatro, y son los del contrato: {@code bbox}, {@code codigoDeSector}, {@code
 * codigoDeManzana} y {@code limite}. Es una lectura, y una lectura cuyo marco viajara en un cuerpo
 * no se podria compartir por la URL —que es como se comparte una vista de un plano—.
 *
 * <h2>El tope, y por que se niega</h2>
 *
 * <p>{@code limite} es cuantos lotes admite quien pregunta; el servidor tiene el suyo, {@link
 * #TOPE_DEL_SERVIDOR}, y pedir por encima es {@code 422} nombrando la cifra en vez de recortar en
 * silencio lo pedido. Y si en el marco caben mas de los que se sirven, tambien {@code 422},
 * diciendo cuantos hay: una respuesta que se puede obedecer acercandose. <b>Nunca una pagina con
 * los primeros</b> (ADR-0022 §2).
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/predios/plano")
@RequiereAcceso(acceso = "consulta_fichas", privilegio = Privilegio.LECTURA)
public class PlanoCatastralController {

    /**
     * Cuantos lotes sirve el servidor como maximo, pida lo que pida el cliente.
     *
     * <p>No es una cifra tributaria (regla 5): no entra en ningun calculo ni sale en ningun papel.
     * Es el tamano de respuesta que este servicio acepta producir, y lo unico que decide es cuando
     * contesta «acercate».
     *
     * <p>Dos mil es el ejemplo que el propio contrato publica para {@code limite}, y a razon de
     * unos veinte vertices por lote deja la respuesta en el orden de unos pocos megabytes: mas que
     * eso no es un plano que se pueda mirar, es una descarga.
     */
    static final int TOPE_DEL_SERVIDOR = 2000;

    private final ConsultaDelPlanoCatastral plano;

    public PlanoCatastralController(ConsultaDelPlanoCatastral plano) {
        this.plano = plano;
    }

    @GetMapping
    public PlanoCatastralResource lotes(
            @RequestParam(required = false) @Nullable String bbox,
            @RequestParam(required = false) @Nullable String codigoDeSector,
            @RequestParam(required = false) @Nullable String codigoDeManzana,
            @RequestParam(required = false) @Nullable String limite) {

        FiltroDelPlano filtro = new FiltroDelPlano(marcoDe(bbox), codigoDeSector, codigoDeManzana);
        try {
            return PlanoCatastralResource.de(plano.lotesDe(filtro, topeDe(limite)));
        } catch (ConsultaDelPlanoCatastral.MarcoConDemasiadosLotes noCabe) {
            // Su propio codigo, y las dos cifras como dato (#611). Los otros tres 422 de este
            // controlador dicen «corrige la peticion»; este dice «la peticion esta bien, acercate»,
            // y son lo unico que el plano puede ofrecer resolver solo. Con `VALIDACION` en los
            // cuatro, separarlos exigia leer el mensaje — y un mensaje se reescribe.
            throw new ProblemaDeNegocio(
                    CodigoDeError.MARCO_CON_DEMASIADOS_LOTES,
                    DeclaracionDeFicha.mensajeDe(noCabe),
                    List.of("lotes=" + noCabe.cuantos(), "tope=" + noCabe.tope()));
        }
    }

    /**
     * Donde esta lo levantado: el marco que envuelve la geometria ya cargada (#612).
     *
     * <p>Es la lectura que le faltaba a {@link #lotes}: aquella exige {@code bbox} y hace bien —sin
     * el la consulta seria el padron entero—, pero <b>ninguna operacion del contrato decia donde
     * esta la municipalidad</b>, asi que la pantalla no tenia de donde sacar el primer marco y
     * abria sobre un rectangulo declarado del pais. El dia que se cargue el primer plano, ese marco
     * contiene mas lotes que el tope y la respuesta pasa a ser «acercate»: correcta, y imposible de
     * obedecer desde ahi.
     *
     * <p><b>Los dos parametros son los mismos que los del plano, y a proposito</b>: el marco tiene
     * que salir del mismo conjunto de predios que despues se dibuja. Falta {@code bbox}, que es
     * justo lo que esta operacion calcula, y falta {@code limite}, que aqui no significa nada: la
     * respuesta son cuatro cifras y una cuenta, pese lo que pese el padron.
     *
     * <p><b>Cuelga de {@code /plano} y hereda su {@code @RequiereAcceso}</b> —{@code
     * consulta_fichas} con {@code LECTURA}—, que es lo que este endpoint tiene que exigir: es el
     * encuadre del mismo mapa. Y no publica ni un identificador de predio, de modo que no es una
     * via de fuga del padron ni deja fila en la bitacora.
     */
    @GetMapping("/marco")
    public MarcoDelPlanoResource marco(
            @RequestParam(required = false) @Nullable String codigoDeSector,
            @RequestParam(required = false) @Nullable String codigoDeManzana) {

        return MarcoDelPlanoResource.de(
                plano.marcoDe(new AcotacionDelPlano(codigoDeSector, codigoDeManzana)));
    }

    /**
     * El marco, obligatorio.
     *
     * <p>Sin el la consulta seria el padron entero, que es lo que esta operacion existe para no
     * hacer. Por eso su ausencia es un {@code 422} que lo nombra y no un valor por omision ni un
     * {@code 200} con cero filas: las dos cosas se leerian como «aqui no hay predios».
     */
    private static MarcoGeografico marcoDe(@Nullable String bbox) {
        if (bbox == null || bbox.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Falta 'bbox': el plano se pide siempre acotado por un marco"
                            + " 'oeste,sur,este,norte' en grados. Sin el la consulta seria el"
                            + " padron entero");
        }
        try {
            return MarcoGeografico.de(bbox);
        } catch (IllegalArgumentException ilegible) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, DeclaracionDeFicha.mensajeDe(ilegible));
        }
    }

    private static int topeDe(@Nullable String limite) {
        if (limite == null || limite.isBlank()) {
            return TOPE_DEL_SERVIDOR;
        }
        int pedido;
        try {
            pedido = Integer.parseInt(limite.strip());
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro 'limite' es un numero de lotes: llego '" + limite + "'");
        }
        if (pedido <= 0) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro 'limite' tiene que ser positivo: llego " + pedido);
        }
        if (pedido > TOPE_DEL_SERVIDOR) {
            // Recortarlo en silencio dejaria al cliente creyendo que pidio lo que no pidio, y
            // el 422 por el marco lleno diria un tope que nadie declaro.
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro 'limite' pide "
                            + pedido
                            + " lotes y el maximo que sirve este servidor son "
                            + TOPE_DEL_SERVIDOR);
        }
        return pedido;
    }
}
