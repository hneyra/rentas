package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeConciliacion;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeConciliacion.FichaConciliada;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository.ResumenDeConciliacion;
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
 * La conciliacion catastro-rentas: {@code GET /api/v1/catastro/fichas/conciliacion} (ADR-0015,
 * #344).
 *
 * <p>Es la grilla de {@code consulta_fichas} con la columna «Conciliada» y el filtro {@code
 * conciliadaConRentas}. Vive en {@code rentas} y no en {@code catastro} por lo mismo que {@code
 * ConsultaPrediosController}: el dato que la distingue —si el predio declaro— es de este contexto,
 * y catastro no puede depender de el (ARQ-01 §4 regla 2, ciclo de modulos).
 *
 * <h2>El acceso es el de la pantalla, no el del modulo</h2>
 *
 * <p>{@code consulta_fichas}, aunque la operacion viva en {@code kamayuk-rentas-nucleo} (ADR-0015
 * §2.1). El permiso sigue a la opcion del catalogo, no al modulo Gradle donde acabo el codigo: si
 * siguiera al modulo, ver la consulta de fichas exigiria permiso de rentas y quien atiende catastro
 * se quedaria fuera de su propia pantalla.
 *
 * <h2>«No» no es un filtro mas</h2>
 *
 * <p>{@code conciliadaConRentas=No} <b>es la lista de los predios que no generan deuda predial</b>,
 * ordenada y paginada: el producto de trabajo de la fiscalizacion de omisos y, en manos
 * equivocadas, el mapa de a quien no le va a llegar recibo. Se sirve solo a quien tenga lectura
 * sobre {@code fisc_omisos} y cada consulta deja fila en la bitacora con operacion {@code ACCESO}
 * (ADR-0015 §2.3). «Si» y «Todas» no lo necesitan: dicen quien esta dentro, no quien falta.
 *
 * <p>El privilegio se comprueba aqui y no con la anotacion por lo mismo que en {@code
 * ViaController}: {@link RequiereAcceso} no sabe expresar «segun lo que traiga un parametro», y el
 * guardia corre antes de leerlo. La respuesta es la misma que da el guardia —{@code
 * SIN_PRIVILEGIO}, 403— para que negar por esta via no se distinga de negar por aquella.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/fichas/conciliacion")
@RequiereAcceso(acceso = ConciliacionController.ACCESO, privilegio = Privilegio.LECTURA)
public class ConciliacionController {

    /** La opcion del catalogo (NEG-03) que esta operacion sirve: la pantalla, no el modulo. */
    static final String ACCESO = "consulta_fichas";

    /** Y la que hace falta ademas para pedir la lista de los que faltan (ADR-0015 §2.3). */
    static final String ACCESO_DE_FISCALIZACION = "fisc_omisos";

    /** Por codigo de referencia catastral, que es como se recorre un sector. */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    private final ConsultaDeConciliacion consulta;
    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public ConciliacionController(
            ConsultaDeConciliacion consulta, ComprobadorDeAcceso comprobador, Clock reloj) {
        this.consulta = consulta;
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<FichaConciliadaResource> consultar(
            @RequestParam(required = false) @Nullable String codRefCatastral,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String manzana,
            @RequestParam(required = false) @Nullable String lote,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String conciliadaConRentas,
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion parametros) {

        BusquedaDeFichas criterio =
                new BusquedaDeFichas(codRefCatastral, contribuyente, manzana, lote, tipo);
        LocalDate aLaFecha = fechaDe(fecha);
        Ejercicio delEjercicio = ejercicioDe(ejercicio, aLaFecha);
        Paginacion paginacion = parametros.aPaginacion(ORDEN_POR_OMISION);

        Pagina<FichaConciliada> pagina =
                switch (filtroDe(conciliadaConRentas)) {
                    case TODAS -> consulta.todas(criterio, delEjercicio, aLaFecha, paginacion);
                    case CONCILIADAS ->
                            consulta.conciliadas(criterio, delEjercicio, aLaFecha, paginacion);
                    case NO_CONCILIADAS -> {
                        exigirPrivilegioDeFiscalizacion();
                        yield consulta.noConciliadas(criterio, delEjercicio, aLaFecha, paginacion);
                    }
                };

        return RespuestaPaginada.de(pagina, FichaConciliadaResource::de);
    }

    /**
     * El recuento del ejercicio: cuantos predios hay, cuantos declararon y cuantos no (#564).
     *
     * <p>La grilla de arriba <b>no sirve para contar</b>: el filtro se aplica sobre la pagina y su
     * {@code totalElementos} es el del padron sin filtrar. Medido sobre Catacaos, los tres valores
     * de {@code conciliadaConRentas} devolvian 14 422, o sea el padron entero, y el panel de
     * Catastro pintaba con esa cifra «Predios sin conciliar: 14 422» encima de «14 422 predios en
     * el padron».
     *
     * <p><b>Va detras del permiso de la pantalla y no del de fiscalizacion</b>, al reves que {@code
     * conciliadaConRentas=No}, y no deja fila en la bitacora. La diferencia es que aquella
     * <b>nombra</b> —es la lista de a quien no le va a llegar recibo— y esta cuenta: dice cuantos,
     * no cuales. Auditar cada pintada del panel llenaria la bitacora de filas que no contestan la
     * pregunta que la bitacora existe para contestar.
     *
     * <p>No acepta los filtros de la grilla: la pregunta del panel es sobre el padron. Aceptarlos
     * obligaria a repetir aqui el {@code WHERE} de aquella consulta, y dos copias de la misma
     * poblacion divergen — el mismo defecto, un escalon mas abajo.
     */
    @GetMapping("/resumen")
    public ResumenDeConciliacionResource resumen(
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String fecha) {

        LocalDate aLaFecha = fechaDe(fecha);
        ResumenDeConciliacion resumen =
                consulta.resumen(ejercicioDe(ejercicio, aLaFecha), aLaFecha);
        return ResumenDeConciliacionResource.de(resumen);
    }

    // ------------------------------------------------------------------

    /** Los tres valores del desplegable de la pantalla: «Todas», «Si» y «No». */
    private enum FiltroDeConciliacion {
        TODAS,
        CONCILIADAS,
        NO_CONCILIADAS
    }

    private static FiltroDeConciliacion filtroDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return FiltroDeConciliacion.TODAS;
        }
        String valor = texto.strip().toUpperCase(Locale.ROOT);
        return switch (valor) {
            case "TODAS", "TODOS" -> FiltroDeConciliacion.TODAS;
            case "SI", "SÍ" -> FiltroDeConciliacion.CONCILIADAS;
            case "NO" -> FiltroDeConciliacion.NO_CONCILIADAS;
            default ->
                    throw new ProblemaDeNegocio(
                            CodigoDeError.VALIDACION,
                            "El filtro de conciliacion va entre Todas, Si y No: '" + texto + "'");
        };
    }

    /**
     * El ejercicio al que se contesta.
     *
     * <p>Si no viene, el de la fecha de corte —que por omision es hoy—: las dos cosas van juntas,
     * porque consultar «a la fecha 2024-06-30» y contestar por el padron afecto de 2026 mezclaria
     * dos años en la misma fila. Venga o no venga, <b>la respuesta lo dice</b> (regla 9).
     */
    private static Ejercicio ejercicioDe(@Nullable String texto, LocalDate aLaFecha) {
        if (texto == null || texto.isBlank()) {
            return Ejercicio.de(aLaFecha);
        }
        try {
            return new Ejercicio(Integer.parseInt(texto.strip()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ejercicio va en cuatro digitos: '" + texto + "'");
        }
    }

    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private void exigirPrivilegioDeFiscalizacion() {
        String usuario = OrigenContext.actual().usuario();
        if (!comprobador.autoriza(
                usuario, ACCESO_DE_FISCALIZACION, Privilegio.LECTURA, LocalDate.now(reloj))) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "No tiene el privilegio "
                            + Privilegio.LECTURA
                            + " sobre "
                            + ACCESO_DE_FISCALIZACION);
        }
    }
}
