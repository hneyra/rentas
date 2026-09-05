package kamayuk.rentas.nucleo.infraestructura.web;

import java.text.Normalizer;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.nucleo.aplicacion.CampaniasDeBeneficioParametrizadas;
import kamayuk.rentas.nucleo.aplicacion.SimularAcogimiento;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code consulta_deudas_beneficio}: {@code GET /api/v1/consultas/deudas-con-beneficio} (RF-107,
 * #72).
 *
 * <p>Simula el acogimiento de la deuda de un contribuyente a una campana de beneficio: deuda total,
 * deuda acogida, deuda con beneficio, alicuota aplicada y ahorro. <b>Simula</b>: no mueve un solo
 * asiento del libro.
 *
 * <h2>Los cuatro filtros que el contrato declara</h2>
 *
 * <ul>
 *   <li><b>{@code contribuyente}</b> es, de hecho, <b>obligatorio</b>. El contrato lo declara
 *       opcional porque se derivo del filtro de la pantalla, pero simular el acogimiento del padron
 *       entero no es una consulta de ventanilla: sin el, 422 con el motivo. Es lo mismo que hace
 *       {@code consulta_deuda} con {@code codContribuyente}.
 *   <li><b>{@code tipoDePapeleta}</b> acota lo acogido a las multas de esa familia, traduciendo el
 *       vocabulario de la pantalla al nombre de tributo con que se asientan: «TRIBUTARIA» → {@code
 *       MULTA_TRIBUTARIA} (la de fiscalizacion, #52), «P. TRÁNSITO» → {@code MULTA_TRANSITO} y «P.
 *       ADMINISTRATIVA» → {@code MULTA_ADMINISTRATIVA} (las dos de {@code sanciones}, #46). Sin
 *       filtro entra <b>toda</b> la deuda, no solo las multas: la pantalla simula el acogimiento de
 *       lo que se debe, y las papeletas son una parte.
 *   <li><b>{@code formaDePago}</b>: «CONTADO TOTAL» describe lo que esta consulta ya hace y se
 *       acepta. <b>«PRECONVENIO» se rechaza con 422</b> y con el motivo: acogerse fraccionando es
 *       otra cosa —tiene su cronograma, su interes de ordenanza y su cuota inicial (#35)— y se
 *       simula en la opcion de convenios. Aceptarlo y devolver la simulacion al contado le daria a
 *       quien pregunta por un fraccionamiento la cifra de otra cosa.
 *   <li><b>{@code benefAplicable}</b> es la campana. Si el conjunto sellado no la publica, 422
 *       nombrando la llave que falta ({@code BENEFICIO:‹CAMPANIA›}) — el mismo trato que la tasa de
 *       anuncios de #51, y por el mismo motivo: <b>un descuento inventado perdona deuda que ninguna
 *       ordenanza condona</b>. Las cuatro opciones que dibuja el desplegable del prototipo son las
 *       ordenanzas de Sullana: contra una instalacion sin campanas cargadas, cualquiera de ellas da
 *       ese 422, que es exactamente lo que hay que decir.
 * </ul>
 *
 * <p>Lo que la respuesta anade y el contrato no pedia: {@code campaniasAplicables}, las campanas
 * que <b>esta</b> municipalidad publica. Sin ella, el desplegable del prototipo seria la unica
 * fuente y diria las de otra ciudad.
 *
 * <p>{@code pagina}, {@code tamano}, {@code ordenarPor} y {@code direccion} se aplican a la rejilla
 * de obligaciones. El resumen —total, acogida, ahorro— se calcula sobre <b>todas</b> las
 * seleccionadas y no sobre la pagina devuelta: un ahorro que cambiara al pasar de pagina es el
 * defecto que #25 documenta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/deudas-con-beneficio")
@RequiereAcceso(acceso = "consulta_deudas_beneficio", privilegio = Privilegio.LECTURA)
public class DeudasConBeneficioController {

    private static final String ORDEN_POR_OMISION = "ejercicio";

    /** Como se asienta cada familia de multa en el libro. Ver el javadoc de la clase. */
    private static final String TRIBUTO_TRIBUTARIA = TributoDelLibro.MULTA_TRIBUTARIA.texto();

    private static final String TRIBUTO_TRANSITO = TributoDelLibro.MULTA_TRANSITO.texto();
    private static final String TRIBUTO_ADMINISTRATIVA =
            TributoDelLibro.MULTA_ADMINISTRATIVA.texto();

    private final SimularAcogimiento simulacion;

    public DeudasConBeneficioController(SimularAcogimiento simulacion) {
        this.simulacion = simulacion;
    }

    @GetMapping
    public DeudasConBeneficioResource deudas(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String tipoDePapeleta,
            @RequestParam(required = false) @Nullable String formaDePago,
            @RequestParam(required = false) @Nullable String benefAplicable,
            ParametrosDePaginacion parametros) {

        if (contribuyente == null || contribuyente.isBlank()) {
            throw new IllegalArgumentException(
                    "contribuyente es obligatorio: la simulacion del acogimiento es de una persona"
                            + " concreta, no del padron entero");
        }
        exigirContado(formaDePago);

        SimularAcogimiento.Criterio criterio =
                new SimularAcogimiento.Criterio(
                        contribuyente,
                        // Hoy, del reloj inyectado y no de LocalDate.now() (regla 6). El contrato
                        // de esta operacion no declara fecha de corte —el campo «Fecha de consulta»
                        // que dibuja la pantalla no viaja—, y agregarla por comodidad seria
                        // publicar una entrada que ninguna pantalla sabe mandar. Lo que RNF-075
                        // exige —que toda cifra diga a que fecha esta— lo cumple cada
                        // ImporteActualizado de la respuesta.
                        simulacion.hoy(),
                        tributoDe(tipoDePapeleta),
                        filtroPedido(benefAplicable));

        try {
            return DeudasConBeneficioResource.de(simulacion.de(criterio, paginacionDe(parametros)));
        } catch (CampaniasDeBeneficioParametrizadas.CampaniaSinParametrizar
                | CampaniasDeBeneficioParametrizadas.CampaniaIncompleta
                | CampaniasDeBeneficioParametrizadas.BaseDesconocida falta) {
            // 422 y no 500: la peticion esta bien y el sistema tampoco esta roto. Lo que falta es
            // un dato de configuracion —la ordenanza de D-02b/D-02c— y quien opera tiene que
            // enterarse de cual para poder pedirlo. Mismo trato que TASA_ANUNCIO:<CLASE> en #51.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        }
    }

    /**
     * «CONTADO TOTAL» se acepta; «PRECONVENIO» se rechaza nombrando donde se hace.
     *
     * <p>No se ignora: ignorarlo devolveria la simulacion al contado a quien pregunto por un
     * fraccionamiento, y las dos cifras no se parecen —un convenio anade interes de ordenanza y
     * cuota inicial—. Es el mismo criterio con que {@code consulta_valores} rechaza «RECLAMADO».
     */
    private static void exigirContado(@Nullable String formaDePago) {
        String pedida = filtroPedido(formaDePago);
        if (pedida == null) {
            return;
        }
        String normalizada = sinTildes(pedida).toUpperCase(Locale.ROOT);
        if (normalizada.startsWith("CONTADO")) {
            return;
        }
        if (normalizada.startsWith("PRECONVENIO")) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Esta consulta simula el acogimiento al contado. El acogimiento fraccionado"
                            + " —preconvenio— tiene su propio cronograma, su interes de ordenanza y"
                            + " su cuota inicial, y se simula en la opcion de convenios de"
                            + " fraccionamiento");
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Forma de pago desconocida: '" + pedida + "'. Se admite «CONTADO TOTAL»");
    }

    /** El nombre del tributo con que se asienta esa familia de multas, o nulo si no se filtra. */
    private static @Nullable String tributoDe(@Nullable String tipoDePapeleta) {
        String pedido = filtroPedido(tipoDePapeleta);
        if (pedido == null) {
            return null;
        }
        String normalizado = sinTildes(pedido).toUpperCase(Locale.ROOT);
        if (normalizado.startsWith("TRIBUTARIA")) {
            return TRIBUTO_TRIBUTARIA;
        }
        if (normalizado.contains("TRANSITO")) {
            return TRIBUTO_TRANSITO;
        }
        if (normalizado.contains("ADMINISTRATIVA")) {
            return TRIBUTO_ADMINISTRATIVA;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Tipo de papeleta desconocido: '"
                        + pedido
                        + "'. Se admite «TRIBUTARIA», «P. TRÁNSITO» y «P. ADMINISTRATIVA»");
    }

    /**
     * Un filtro que no viene, o que viene en blanco, no es un filtro.
     *
     * <p>«Todos» y la cadena vacia significan lo mismo —no acotar—, y el desplegable manda una u
     * otra segun por donde se llegue.
     */
    private static @Nullable String filtroPedido(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpio = texto.strip();
        return sinTildes(limpio).toUpperCase(Locale.ROOT).startsWith("TODO") ? null : limpio;
    }

    /** Un mensaje siempre, nunca un {@code null} en la respuesta. */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La peticion no se pudo completar" : mensaje;
    }

    private static String sinTildes(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Igual que {@link ParametrosDePaginacion#aPaginacion}, salvo la direccion por omision: aqui es
     * {@code DESCENDENTE} —el ejercicio mas reciente primero—, como en {@code consulta_deuda}.
     */
    private static Paginacion paginacionDe(ParametrosDePaginacion parametros) {
        return new Paginacion(
                parametros.pagina() == null ? 0 : parametros.pagina(),
                parametros.tamano() == null ? 20 : parametros.tamano(),
                parametros.ordenarPor() == null || parametros.ordenarPor().isBlank()
                        ? ORDEN_POR_OMISION
                        : parametros.ordenarPor(),
                parametros.direccion() == null
                        ? Paginacion.Direccion.DESCENDENTE
                        : parametros.direccion());
    }
}
