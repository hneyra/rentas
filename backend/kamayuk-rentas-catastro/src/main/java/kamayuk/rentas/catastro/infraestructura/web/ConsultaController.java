package kamayuk.rentas.catastro.infraestructura.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Consulta transversal de fichas: {@code GET /api/v1/catastro/fichas} (RF-006).
 *
 * <p>Los filtros son los que declara el contrato, que salio de la pantalla del prototipo. Uno de
 * ellos, {@code conciliadaConRentas}, <b>lo sirve otra ruta</b>: el estado de conciliacion se
 * deriva de {@code declaracion_jurada}, que es de {@code rentas}, y este contexto no puede mirarla
 * —dependeria de rentas y {@code verificarArquitectura} rechaza el ciclo (ADR-0015 §2)—.
 *
 * <p>Hasta #344 eso se contestaba con un <b>422 deliberado</b>, porque la lectura compuesta no
 * existia. Ahora existe, en {@code kamayuk-rentas-rentas}, y este endpoint <b>redirige</b> a ella
 * con 307 conservando la peticion entera. No se ignora el filtro y no se responde sin el: devolver
 * el listado completo daria un resultado plausible y equivocado —quien lo mira creeria estar viendo
 * solo las conciliadas— y esa es la razon por la que el 422 se puso; el redirigir la conserva y
 * ademas contesta.
 *
 * <p>La ruta de destino es un subcamino de este mismo recurso, {@code
 * /catastro/fichas/conciliacion}, asi que aqui no aparece ninguna ruta de otro modulo: quien la
 * sirve —rentas— es un detalle de donde vive el codigo, no del contrato (mismo criterio que {@code
 * ConsultaPrediosController}, que sirve {@code consulta_predios} desde rentas).
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/fichas")
@RequiereAcceso(acceso = "consulta_fichas", privilegio = Privilegio.LECTURA)
public class ConsultaController {

    /** Por codigo de referencia catastral, que es como se recorre un sector. */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    /**
     * La misma grilla, con la columna «Conciliada» (ADR-0015 §2, #344). La sirve {@code rentas}.
     */
    static final String RUTA_DE_LA_CONCILIACION = Api.RAIZ + "/catastro/fichas/conciliacion";

    private final ConsultaDeFichas consulta;
    private final Clock reloj;

    public ConsultaController(ConsultaDeFichas consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public ResponseEntity<RespuestaPaginada<FichaEncontradaResource>> consultar(
            @RequestParam(required = false) @Nullable String codRefCatastral,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String manzana,
            @RequestParam(required = false) @Nullable String lote,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String conciliadaConRentas,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion paginacion,
            HttpServletRequest peticion) {

        if (conciliadaConRentas != null && !conciliadaConRentas.isBlank()) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(destinoDeLaConciliacion(peticion.getParameterMap()))
                    .build();
        }

        FiltroDeFichas filtro =
                new FiltroDeFichas(codRefCatastral, contribuyente, manzana, lote, tipoDe(tipo));
        LocalDate cuando = fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : parsear(fecha);

        return ResponseEntity.ok(
                RespuestaPaginada.de(
                        consulta.buscar(filtro, cuando, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                        FichaEncontradaResource::de));
    }

    /**
     * El destino del 307, con la peticion entera.
     *
     * <p>Se reenvian <b>todos</b> los parametros recibidos y no solo los que este metodo declara:
     * cualquier filtro que el contrato anada despues a la otra ruta se perderia por el camino sin
     * que nada avise, que es la clase de defecto que este endpoint existe para no cometer.
     *
     * <p>Se reconstruyen en vez de reenviar la cadena de consulta tal cual, y por dos motivos:
     * {@code UriComponentsBuilder} vuelve a codificar cada valor —asi nada que venga del cliente
     * entra crudo en una cabecera {@code Location}— y el resultado es el mismo con un contenedor de
     * verdad y con el de las pruebas, que no rellena la cadena original.
     */
    private static URI destinoDeLaConciliacion(Map<String, String[]> parametros) {
        UriComponentsBuilder destino = UriComponentsBuilder.fromPath(RUTA_DE_LA_CONCILIACION);
        for (Map.Entry<String, String[]> parametro : parametros.entrySet()) {
            destino.queryParam(parametro.getKey(), (Object[]) parametro.getValue());
        }
        return URI.create(destino.build().encode().toUriString());
    }

    private static @Nullable TipoFicha tipoDe(@Nullable String tipo) {
        if (tipo == null || tipo.isBlank()) {
            return null;
        }
        try {
            return TipoFicha.valueOf(tipo.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de ficha va entre UNICA, ECONOMICA, BIENES_COMUNES y RURAL: '"
                            + tipo
                            + "'");
        }
    }

    private static LocalDate parsear(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + fecha + "'");
        }
    }
}
