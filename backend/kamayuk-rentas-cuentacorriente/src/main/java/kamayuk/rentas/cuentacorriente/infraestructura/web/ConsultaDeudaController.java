package kamayuk.rentas.cuentacorriente.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Agregacion;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeudaPorContribuyente;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
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
 * {@code consulta_deuda}: {@code GET /api/v1/consultas/deuda} (RF-041, RF-042, #25).
 *
 * <p>Todas las obligaciones del contribuyente —tributo, ejercicio, unidad— con su deuda actualizada
 * a una fecha de corte, en una fila por obligacion. Sin {@code fechaDeCorte}, se calcula a hoy, con
 * el reloj inyectado de {@link ConsultarDeuda#hoy()} y no con {@code LocalDate.now()} (regla 6).
 *
 * <p>{@code incluyeConvenios} esta en el contrato de la pantalla pero se ignora: el contexto de
 * convenios de fraccionamiento todavia no existe (#25 depende de el solo para esa parte). Se acepta
 * el parametro para no romper la pantalla, no se aplica —el mismo patron que {@code situacion} en
 * {@code CuentaCorrienteController}—.
 *
 * <h2>{@code porPeriodo}: la fila que se puede dar de baja (#551)</h2>
 *
 * <p>Por omision cada fila es una <b>obligacion</b> con sus periodos agregados —{@code
 * periodoDesde}/{@code periodoHasta} son el minimo y el maximo del grupo—, que es lo que la
 * ventanilla necesita para cobrar: {@code POST /tesoreria/caja/cobranza} pide esa clave.
 *
 * <p>Pero {@code POST /rentas/deuda/bajas} extingue <b>una</b> cuota, y una fila que agrega cinco
 * periodos no dice cuanto debe cada una: no hay ningun cuerpo que se pueda componer desde ella sin
 * repartir el total en la pantalla, que es componer dinero en la interfaz (RNF-083) y ademas
 * produciria {@code BajaMayorQueLaDeuda} en cuanto el reparto no coincidiera al centimo. Con {@code
 * porPeriodo=true} cada fila <b>es</b> una cuota, con {@code periodoDesde == periodoHasta}, su
 * propio desglose en cuatro partes y su {@code actualizadoA}: la forma del recurso no cambia, solo
 * donde se corta.
 *
 * <p>Se admite {@code true} o {@code false} y <b>nada mas</b>: cualquier otra palabra es 422
 * nombrando el parametro, por lo mismo que el {@code activa} del catalogo vial (#565). Un «si»
 * tecleado que se leyera como «false» devolveria filas agregadas a quien pidio cuotas, y esa
 * respuesta es indistinguible de la correcta hasta que alguien intenta dar una de baja.
 *
 * <h2>Y la caja tributaria lee de aqui: {@code caja_tributaria} tambien autoriza (#548)</h2>
 *
 * <p>Esta es la <b>unica</b> lectura que publica la deuda por obligacion, asi que es de aqui de
 * donde la ventanilla saca las filas que se marcan para cobrar: {@code POST
 * /tesoreria/caja/cobranza} exige {@code obligaciones[]} con tributo, ejercicio y unidad, y ninguna
 * otra operacion las tiene una a una. Con el acceso a secas, un <b>perfil de cajero puro</b>
 * —{@code caja_tributaria} y nada mas— podia cobrar y no ver que cobrar: la pantalla de cobro se
 * abre y su grilla contesta 403.
 *
 * <p><b>La decision es que {@code consulta_deuda} deje de hacer falta ahi</b>, y no que el grupo de
 * cajero lo incluya en la implantacion. Dos motivos, y ninguno es de comodidad:
 *
 * <ul>
 *   <li><b>No hay grupo de cajero que otorgar.</b> {@code ImplantarMunicipalidad} deja exactamente
 *       dos grupos —«Administracion del sistema» y «Seguridad»—; inventar un tercero seria inventar
 *       la organizacion de una municipalidad, y aun asi nacen sin miembros, de modo que el usuario
 *       que solo tiene {@code caja_tributaria} seguiria recibiendo 403.
 *   <li><b>Es estructural, no configurable.</b> Sin la deuda marcada no hay nada que cobrar: quien
 *       puede cobrar tiene por fuerza que poder verla. Dejarlo a que cada implantacion se acuerde
 *       de otorgar una opcion de <b>otro modulo</b> convierte un no-negociable en algo que se
 *       olvida, y el sintoma —una grilla en 403 dentro de la pantalla de cobro— no se parece a su
 *       causa. Si el desarrollador no lo maneja, no puede olvidarlo (regla 2, mismo criterio).
 * </ul>
 *
 * <p>Es el reparto contrario al que #366 eligio para {@code GET
 * /catastro/predios/{predioId}/titulares}, y a proposito: alli el acceso es el del <b>padron</b>
 * porque lo que se pide no es catastro y su publico es mas estrecho que el de la pantalla desde la
 * que se hace clic. Aqui lo que se pide <b>es</b> la caja.
 *
 * <p>Lo que <b>no</b> cambia: el privilegio sigue siendo {@code LECTURA} en las dos opciones —un
 * cajero con solo {@code REGISTRO} sobre {@code caja_tributaria} no entra—, y {@code
 * consulta_deuda} sigue autorizando exactamente como antes.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/deuda")
@RequiereAcceso(
        acceso = "consulta_deuda",
        oTambien = "caja_tributaria",
        privilegio = Privilegio.LECTURA)
public class ConsultaDeudaController {

    private static final String ORDEN_POR_OMISION = "ejercicio";

    private final ConsultarDeuda consulta;

    public ConsultaDeudaController(ConsultarDeuda consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<ObligacionConDeudaResource> deuda(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String fechaDeCorte,
            @RequestParam(required = false) @Nullable String fase,
            @RequestParam(required = false) @Nullable String incluyeConvenios,
            @RequestParam(required = false) @Nullable String porPeriodo,
            ParametrosDePaginacion parametros) {

        String codigo = exigirContribuyente(codContribuyente);
        if (consulta.contribuyentePorCodigo(codigo).isEmpty()) {
            throw noEstaEnElPadron(codigo);
        }

        CriterioDeDeudaPorContribuyente criterio =
                new CriterioDeDeudaPorContribuyente(
                        codigo, fechaDe(fechaDeCorte), faseDe(fase), agregacionDe(porPeriodo));

        return RespuestaPaginada.de(
                consulta.porContribuyente(criterio, paginacionDe(parametros)),
                ObligacionConDeudaResource::de);
    }

    /**
     * Como se corta cada fila, dicho por la peticion y nunca adivinado (#551).
     *
     * <p>Ausente es {@link Agregacion#POR_OBLIGACION}, que es lo que esta operacion ha devuelto
     * siempre. Lo que no se hace es leer cualquier texto como «false»: {@code porPeriodo=si} tiene
     * que decirlo, porque una respuesta agregada a quien pidio cuotas se lee igual que la correcta.
     */
    private static Agregacion agregacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return Agregacion.POR_OBLIGACION;
        }
        String valor = texto.strip().toLowerCase(Locale.ROOT);
        if ("true".equals(valor)) {
            return Agregacion.POR_PERIODO;
        }
        if ("false".equals(valor)) {
            return Agregacion.POR_OBLIGACION;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "El parametro «porPeriodo» admite «true» o «false»: '"
                        + texto
                        + "'. Con «true» cada fila es una cuota con su propio desglose; sin el,"
                        + " cada fila es una obligacion con sus periodos agregados");
    }

    private static @Nullable Fase faseDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return Fase.valueOf(texto.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * La fecha de corte pedida, o hoy si no viene ninguna.
     *
     * <p>{@code DateTimeParseException} no extiende {@code IllegalArgumentException} —a diferencia
     * de {@code NumberFormatException}—, asi que sin este {@code catch} el manejador global la
     * trataria como error interno (500) en vez de una entrada mal formada (422).
     */
    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return consulta.hoy();
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException excepcion) {
            throw new IllegalArgumentException(
                    "La fecha de corte debe tener formato AAAA-MM-DD: '" + texto + "'", excepcion);
        }
    }

    /**
     * Igual que {@link ParametrosDePaginacion#aPaginacion}, salvo la direccion por omision: aqui es
     * {@code DESCENDENTE} —el ejercicio mas reciente primero, como se lee un listado de deuda en
     * ventanilla—, en vez de la {@code ASCENDENTE} que asume {@code aPaginacion}.
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

    /**
     * Lo que la peticion diga de quien es la consulta, con los dos nombres (#622).
     *
     * <p>Uno de los dos es <b>obligatorio</b>: sin ninguno, 422. Sin esa exigencia esto seria una
     * puerta al padron entero.
     */
    private static String exigirContribuyente(@Nullable String canonico) {
        String codigo = limpio(canonico);
        if (codigo == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de quien es la consulta: falta «codContribuyente»");
        }
        return codigo;
    }

    private static @Nullable String limpio(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String sinBlancos = texto.strip();
        return sinBlancos.isEmpty() ? null : sinBlancos;
    }

    /**
     * Un codigo que no esta en el padron es {@code 404} nombrandolo, no una pagina vacia (#622).
     *
     * <p>Es el mismo defecto que #541 y #595 cerraron en las dos lecturas de Rentas: el expediente
     * pide siete lecturas con el mismo codigo, una contestaba 404 y las otras seis «existe y no
     * tiene nada». Quien atiende leia seis afirmaciones de que la persona existe debajo de una que
     * decia que no — y la que mas cuesta es la de deuda, porque «no tiene deuda pendiente» sobre
     * alguien que el padron no reconoce es lo que se dice antes de emitir una constancia de no
     * adeudo.
     */
    private static RuntimeException noEstaEnElPadron(String codigo) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "En el padron de esta municipalidad no hay ningun contribuyente con codigo '"
                        + codigo
                        + "'");
    }
}
