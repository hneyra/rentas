package kamayuk.rentas.catastro.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code consulta_resumen_predial}: {@code GET /api/v1/consultas/resumen-predial} (RF-046, #25).
 *
 * <p>Vive en {@code catastro} porque es el contexto que mas datos aporta: el listado base de la
 * pantalla —«Predios encontrados»— es una busqueda de predios y de sus fichas vigentes, y eso ya lo
 * sabe hacer {@link ConsultaDeFichas}. Reusarla en vez de escribir una consulta paralela es lo que
 * garantiza que este endpoint y {@code GET /catastro/fichas} entiendan «vigente a la fecha» de la
 * misma manera.
 *
 * <h2>Que sirve esta ruta, y que no</h2>
 *
 * <p>La pantalla del prototipo es una ficha de pestañas. <b>Solo el listado base es implementable
 * hoy</b>, y las tres pestañas se resuelven asi:
 *
 * <ul>
 *   <li><b>«Movimientos del Predio»</b>: ya esta publicada. El historial versionado de la ficha
 *       —cada version con su origen, su documento, su autor y su observacion— sale por {@code GET
 *       /api/v1/catastro/fichas/{tipo}/{codigo}?historico=true}, y cada fila de este listado lleva
 *       el {@code codCatastral} y el {@code tipo} con que pedirlo. No se duplica aqui: seria la
 *       misma consulta con otra forma, y dos formas del mismo historico acaban divergiendo.
 *       <p>El filtro «Tipo de movimiento» de esa pestaña ofrece ALTA, TRANSFERENCIA, MODIFICACIÓN y
 *       BAJA. <b>TRANSFERENCIA no es un movimiento que el dominio registre</b>: una transferencia
 *       se anota como el cierre de una titularidad y la apertura de otra —una BAJA y un ALTA sobre
 *       {@code titularidad}—, y asi es como se ve. Presentarla como un movimiento propio obligaria
 *       a inventar una fila que no existe.
 *   <li><b>«Impuesto Predial»</b> (insoluto, reajuste, interes, gasto y total por ejercicio): <b>no
 *       se sirve</b>. El impuesto predial se determina <b>por contribuyente</b>, no por predio: los
 *       tramos progresivos se aplican al conjunto de sus predios (NEG-05 §1). No existe una cifra
 *       atribuible a un predio concreto salvo inventando un reparto, y un reparto inventado produce
 *       un numero plausible que nadie podria explicar en una reclamacion. Sus claves no viajan en
 *       la respuesta.
 *   <li><b>«Valúo Predial / Arbitrios»</b>: <b>tampoco</b>. El valuo depende de las tablas de
 *       valores unitarios, depreciacion y aranceles, que siguen sin firmar (D-02a); y los arbitrios
 *       no tienen dominio todavia (#31). Un cero aqui seria peor que la ausencia: se leeria como
 *       «este predio no paga arbitrios».
 * </ul>
 *
 * <h2>El filtro «Palabra» se rechaza</h2>
 *
 * <p>El contrato lo declara y no se implementa. Es texto libre sin columna a la que apuntar, y la
 * unica forma de responderlo seria un {@code LIKE '%…%'} sobre direccion, codigo y nombre de todo
 * el padron —justo lo que el diseño de {@link FiltroDeFichas} descarta por escrito—. Se responde
 * 422 con el motivo, no un listado sin filtrar: aceptarlo y devolver todo daria un resultado
 * plausible y equivocado, igual que {@code conciliadaConRentas} en {@code ConsultaController}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/resumen-predial")
@RequiereAcceso(acceso = "consulta_resumen_predial", privilegio = Privilegio.LECTURA)
public class ResumenPredialController {

    /** Por codigo de referencia catastral, que es como se recorre un sector. */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    /** Lo que el desplegable «Uso» manda cuando no se quiere filtrar. */
    private static final String TODOS = "TODOS";

    private final ConsultaDeFichas consulta;
    private final Clock reloj;

    public ResumenPredialController(ConsultaDeFichas consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<PredioDelResumenResource> consultar(
            @RequestParam(required = false) @Nullable String codCatastral,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String uso,
            @RequestParam(required = false) @Nullable String palabra,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion paginacion) {

        if (palabra != null && !palabra.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El filtro «Palabra» no se puede responder: es texto libre sin columna a la"
                            + " que apuntar, y resolverlo obligaria a recorrer todo el padron"
                            + " buscando en la direccion, el codigo y el nombre. Use «Cod."
                            + " Catastral», «Cod. Contribuyente» o «Uso»");
        }

        FiltroDeFichas filtro =
                new FiltroDeFichas(codCatastral, codContribuyente, null, null, null, usoDe(uso));
        LocalDate cuando = fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : parsear(fecha);

        return RespuestaPaginada.de(
                consulta.resumenPredial(filtro, cuando, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PredioDelResumenResource::de);
    }

    /** «Todos» del desplegable no es un uso: es la ausencia de filtro. */
    private static @Nullable String usoDe(@Nullable String uso) {
        if (uso == null || uso.isBlank()) {
            return null;
        }
        String limpio = uso.strip();
        return TODOS.equalsIgnoreCase(limpio) ? null : limpio;
    }

    private static LocalDate parsear(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + fecha + "'");
        }
    }
}
