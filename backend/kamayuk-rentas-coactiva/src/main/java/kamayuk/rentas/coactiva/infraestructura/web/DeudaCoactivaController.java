package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.PaginaDescartadaTrasPaginar;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ResumenDeLaCartera;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
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
 * Las dos consultas de deuda en coactiva y el resumen de su cartera, por HTTP (RF-107, #272).
 *
 * <h2>El resumen cuenta expedientes; las dos consultas devuelven su deuda</h2>
 *
 * <p>{@code GET /coactiva/cartera/resumen} entro con #272 y no repite ninguna de las dos: contesta
 * <b>cuantos</b> expedientes hay en cada etapa, sin una sola cifra de dinero y sin paginar. Vive
 * aqui —y no en {@code ExpedienteController}— por el acceso: la pantalla que lo pide es {@code
 * coa-panel}, que ya abre {@code GET /coactiva/deudas} con {@link #ACCESO_DEUDAS}, y colgarlo de
 * {@code coactiva_expedientes} dejaria el panel contestando 403 a quien puede abrirlo.
 *
 * <h2>Los dos filtros que se rechazan, y por que no se traducen a algo parecido</h2>
 *
 * <p>Es el mismo criterio con que {@code ConvenioController} rechaza «CUMPLIDO» y «EN RIESGO»:
 * devolver una lista bajo una etiqueta que el sistema no sabe calcular es peor que decir que no se
 * sabe, porque la lista se imprime y se cree.
 *
 * <ul>
 *   <li><b>{@code tipoDeDeuda}</b> ofrece «TRIBUTARIA», «P. TRANSITO», «P. ADMINISTRATIVA» y
 *       «CLAUSURA DE LOCAL». Solo la primera existe: a un expediente coactivo se importan
 *       <b>valores</b> (#40), y una papeleta llega a coactiva a traves del valor que la formaliza.
 *       Mientras {@code ImportarValoresACoactiva} sea el unico camino de entrada, filtrar por «P.
 *       TRANSITO» devolveria vacio o —peor— todo.
 *   <li><b>{@code estado = FRACCIONADO}</b> no es un estado del procedimiento: los seis del manual
 *       —011 a 051— son los que ofrece {@code expediente_historial}, y «FRACCIONADO» no esta entre
 *       ellos. Suscribir un convenio no mueve el expediente (vease {@code FraccionarEnCoactiva}):
 *       lo que ocurre, al formalizarse, es que su deuda pasa a fase {@code CONVENIO} en el libro y
 *       deja de contarse como deuda coactiva exigible, que es un hecho y no una etiqueta. Hasta
 *       #403 este parrafo y el 422 lo afirmaban sin que fuera cierto: la deuda del expediente
 *       sumaba lo acogido como exigible. Desde #403 {@code ConsultaDeExpedientes} lo separa por la
 *       fase.
 * </ul>
 *
 * <h2>La fecha de calculo es de quien consulta</h2>
 *
 * <p>{@code fechaDeCalculo} es el filtro que la pantalla {@code coactiva_deudas_beneficio} dibuja,
 * y aqui decide a que dia se actualizan <b>todas</b> las cifras de la respuesta. Sin el, hoy. Y
 * viaja de vuelta en cada fila: ninguna cifra sale sin su fecha (regla 9).
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class DeudaCoactivaController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_DEUDAS = "coactiva_consulta_deudas";

    static final String ACCESO_BENEFICIO = "coactiva_deudas_beneficio";

    private static final String ORDEN_POR_OMISION = "numero";

    /** El unico tipo de deuda que hoy llega a un expediente coactivo. */
    private static final String TIPO_TRIBUTARIA = "TRIBUTARIA";

    /** El valor del desplegable «Estado» que no es un estado del procedimiento. */
    private static final String FRACCIONADO = "FRACCIONADO";

    private final ConsultaDeDeudasCoactivas consulta;
    private final ConsultaDeExpedientes expedientes;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public DeudaCoactivaController(
            ConsultaDeDeudasCoactivas consulta,
            ConsultaDeExpedientes expedientes,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.consulta = consulta;
        this.expedientes = expedientes;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * La deuda en cobranza coactiva por expediente, a hoy (RF-107).
     *
     * <p><b>No sale como {@code RespuestaPaginada}</b>, y con su hermana de beneficio son las dos
     * unicas de esta API que no —medido sobre el contrato: 53 relaciones paginadas publican {@code
     * totalElementos} y estas dos no—. El motivo es que su recuento cuenta <b>expedientes del
     * criterio</b> y no las filas que devuelve, porque los expedientes sin nada que cobrar se
     * descartan despues de componer la pagina. Vease {@link RespuestaDeDeudasCoactivas} (#307).
     */
    @GetMapping("/deudas")
    @RequiereAcceso(acceso = ACCESO_DEUDAS, privilegio = Privilegio.LECTURA)
    public RespuestaDeDeudasCoactivas<DeudaCoactivaResource> deudas(
            @RequestParam(required = false) @Nullable String tipoDeDeuda,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String nExpediente,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        exigirTipoTributario(tipoDeDeuda);
        LocalDate aLaFecha = LocalDate.now(reloj);

        PaginaDescartadaTrasPaginar<ConsultaDeDeudasCoactivas.DeudaEnCoactiva> pagina =
                consulta.deudas(
                        criterioDe(nExpediente, contribuyente, estado),
                        aLaFecha,
                        paginacion.aPaginacion(ORDEN_POR_OMISION));

        Map<Long, ResumenDeContribuyente> padron =
                padronDe(
                        pagina.contenido().stream()
                                .map(fila -> fila.expediente().contribuyenteId())
                                .collect(java.util.stream.Collectors.toCollection(HashSet::new)));

        return RespuestaDeDeudasCoactivas.de(
                pagina,
                fila ->
                        DeudaCoactivaResource.de(
                                fila,
                                codigoDe(padron, fila.expediente().contribuyenteId()),
                                nombreDe(padron, fila.expediente().contribuyenteId())));
    }

    /**
     * La deuda coactiva de los obligados con beneficio registrado y vigente (RF-107).
     *
     * <p><b>Sin descuento aplicado.</b> Cada fila nombra el beneficio y lo que la norma declara; la
     * columna «Con beneficio S/» del prototipo no se responde porque su valor es D-02b (#191).
     * Vease {@code ConsultaDeDeudasCoactivas}.
     */
    @GetMapping("/deudas-en-beneficio")
    @RequiereAcceso(acceso = ACCESO_BENEFICIO, privilegio = Privilegio.LECTURA)
    public RespuestaDeDeudasCoactivas<DeudaCoactivaResource> enBeneficio(
            @RequestParam(required = false) @Nullable String tipoDeDeuda,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String benefAplicable,
            @RequestParam(required = false) @Nullable String fechaDeCalculo,
            ParametrosDePaginacion paginacion) {

        exigirTipoTributario(tipoDeDeuda);
        exigirQueNoSePidaUnBeneficioConcreto(benefAplicable);
        LocalDate aLaFecha = fechaOpcional(fechaDeCalculo, "fechaDeCalculo", LocalDate.now(reloj));

        PaginaDescartadaTrasPaginar<ConsultaDeDeudasCoactivas.DeudaConBeneficio> pagina =
                consulta.enBeneficio(
                        criterioDe(null, contribuyente, null),
                        aLaFecha,
                        paginacion.aPaginacion(ORDEN_POR_OMISION));

        Map<Long, ResumenDeContribuyente> padron =
                padronDe(
                        pagina.contenido().stream()
                                .map(fila -> fila.deuda().expediente().contribuyenteId())
                                .collect(java.util.stream.Collectors.toCollection(HashSet::new)));

        return RespuestaDeDeudasCoactivas.de(
                pagina,
                fila ->
                        DeudaCoactivaResource.de(
                                fila,
                                codigoDe(padron, fila.deuda().expediente().contribuyenteId()),
                                nombreDe(padron, fila.deuda().expediente().contribuyenteId())));
    }

    /**
     * El resumen de la cartera coactiva: cuantos expedientes hay en cada etapa (#272, RF-100).
     *
     * <h2>Que corrige, y por que era un defecto en verde</h2>
     *
     * <p>El panel {@code coa-panel} dibujaba «Expedientes abiertos» con el {@code totalElementos}
     * de {@code GET /coactiva/deudas}. La unidad era la correcta —esa consulta devuelve una fila
     * por <b>expediente</b> y no por deuda—, pero el adjetivo no: ese total cuenta <b>todos</b> los
     * expedientes del criterio, concluidos incluidos, y ademas cuenta los que la propia respuesta
     * descarta por no tener nada que cobrar. Aqui {@code expedientes} y {@code abiertos} viajan
     * separados y con su nombre.
     *
     * <h2>Ninguna cifra de dinero</h2>
     *
     * <p>«Deuda en cartera» no se publica, y esta escrito por que en {@code
     * ConsultaDeExpedientes.resumenDeLaCartera}: componerla costaria una lectura del libro por
     * expediente y contaria dos veces la obligacion que dos expedientes del mismo obligado
     * formalizaran por dos valores distintos. Un importe casi correcto en un panel es peor que un
     * hueco: nadie lo comprueba porque se parece al bueno.
     *
     * <p>Sin {@code ejercicio}, la cartera entera. La fecha de la respuesta es la de la lectura: el
     * estado se deriva del ultimo movimiento y no se sabe reconstruir a un dia pasado.
     */
    @GetMapping("/cartera/resumen")
    @RequiereAcceso(acceso = ACCESO_DEUDAS, privilegio = Privilegio.LECTURA)
    public ResumenDeLaCarteraResource resumenDeLaCartera(
            @RequestParam(required = false) @Nullable String ejercicio) {

        Integer delEjercicio = ejercicioOpcional(ejercicio);
        ResumenDeLaCartera resumen =
                expedientes.resumenDeLaCartera(
                        new CriterioDeExpedientes(null, null, null, null, delEjercicio));

        return ResumenDeLaCarteraResource.de(resumen, delEjercicio, LocalDate.now(reloj));
    }

    // ------------------------------------------------------------------

    private CriterioDeExpedientes criterioDe(
            @Nullable String numero, @Nullable String contribuyente, @Nullable String estado) {
        return new CriterioDeExpedientes(
                vacioAnulo(numero),
                contribuyenteOpcional(contribuyente),
                null,
                estadoOpcional(estado),
                null);
    }

    private static void exigirTipoTributario(@Nullable String tipoDeDeuda) {
        String valor = vacioAnulo(tipoDeDeuda);
        if (valor == null || TIPO_TRIBUTARIA.equalsIgnoreCase(valor)) {
            return;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Tipo de deuda no disponible: '"
                        + tipoDeDeuda
                        + "'. A un expediente coactivo se importan valores (#40), y una papeleta"
                        + " llega a coactiva a traves del valor que la formaliza; hoy el unico"
                        + " tipo que el sistema puede distinguir es TRIBUTARIA. Filtrar por los"
                        + " otros devolveria una lista que nadie puede auditar");
    }

    private static void exigirQueNoSePidaUnBeneficioConcreto(@Nullable String benefAplicable) {
        String valor = vacioAnulo(benefAplicable);
        if (valor == null || "TODOS".equalsIgnoreCase(valor)) {
            return;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Filtrar por campaña de beneficio exige saber que deuda alcanza cada campaña, y eso"
                        + " es D-02b (#191): el efecto de un beneficio sobre el importe no esta"
                        + " decidido. La consulta lista la deuda coactiva de los obligados con"
                        + " beneficio REGISTRADO y vigente, nombrando cual es");
    }

    private static @Nullable Integer ejercicioOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        try {
            return Integer.valueOf(valor);
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ejercicio va en cuatro digitos: '" + texto + "'");
        }
    }

    private static @Nullable EstadoDelExpediente estadoOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor)) {
            return null;
        }
        if (FRACCIONADO.equalsIgnoreCase(valor)) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "«FRACCIONADO» no es un estado del procedimiento coactivo: los del manual son"
                            + " 011 REC 01 EMITIDO, 012 REC 01 NOTIFICADA, 021 REC 02 EMITIDA, 031"
                            + " MEDIDA CAUTELAR, 041 SUSPENDIDO y 051 CONCLUIDO. Un convenio no"
                            + " mueve el expediente: al formalizarse mueve su deuda a fase"
                            + " CONVENIO en el libro, y esa deuda deja de contarse como deuda"
                            + " coactiva exigible");
        }
        try {
            return EstadoDelExpediente.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private Map<Long, ResumenDeContribuyente> padronDe(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : contribuyentes.porIds(ids);
    }

    private static String codigoDe(Map<Long, ResumenDeContribuyente> padron, long contribuyenteId) {
        ResumenDeContribuyente enElMapa = padron.get(contribuyenteId);
        // Sin nombre en el padron se cae al identificador en vez de ocultar la fila: un expediente
        // cuyo obligado se dio de baja es justamente el que hay que revisar.
        return enElMapa == null ? String.valueOf(contribuyenteId) : enElMapa.codigo();
    }

    private static String nombreDe(Map<Long, ResumenDeContribuyente> padron, long contribuyenteId) {
        ResumenDeContribuyente enElMapa = padron.get(contribuyenteId);
        return enElMapa == null ? "" : enElMapa.nombre();
    }

    private @Nullable Long contribuyenteOpcional(@Nullable String codigo) {
        String valor = vacioAnulo(codigo);
        if (valor == null) {
            return null;
        }
        return contribuyentes.porCodigo(valor).map(ResumenDeContribuyente::id).orElse(-1L);
    }

    private static LocalDate fechaOpcional(
            @Nullable String texto, String campo, LocalDate porOmision) {
        if (texto == null || texto.isBlank()) {
            return porOmision;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato ISO (2026-03-16): '" + texto + "'");
        }
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip().toUpperCase(Locale.ROOT);
        return limpio.isEmpty() ? null : limpio;
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La consulta no se pudo resolver" : mensaje;
    }
}
