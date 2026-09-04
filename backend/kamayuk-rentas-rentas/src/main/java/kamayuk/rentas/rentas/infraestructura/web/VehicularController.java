package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.rentas.aplicacion.RegistrarDeterminacionVehicular;
import kamayuk.rentas.rentas.dominio.CriterioDeVehiculo;
import kamayuk.rentas.rentas.dominio.EstadoVehiculo;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoEncontrado;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cálculo del impuesto vehicular: {@code POST /api/v1/rentas/vehicular/calculo} (RF-025, #32).
 *
 * <p>Resuelve el objetivo de tres formas —un {@code vehiculoId}, una {@code placa}, o todos los
 * vehículos activos de un {@code codContribuyente}—, tal como pide el catálogo («por contribuyente
 * o por placa»). Cuando el objetivo es un contribuyente, un vehículo fuera de su plazo de
 * afectación se excluye del resultado en silencio —es la respuesta automática que #32 exige—;
 * cuando el objetivo es un vehículo puntual, la misma situación se informa como error: quien pidió
 * ese vehículo esperaba una respuesta sobre él, no una lista vacía.
 *
 * <h2>Los filtros del contrato se leen de la consulta; lo demás, del cuerpo (#399)</h2>
 *
 * <p>Hasta #399 este controlador leía {@code placa}, {@code codContribuyente} y {@code ejercicio}
 * <b>solo del cuerpo</b>, y el contrato los declara <b>de consulta</b> —son los tres filtros que la
 * pantalla dibuja, y un filtro viaja por la URL en las 134—. Con las dos mitades separadas la
 * petición que la interfaz sabe construir llegaba con los tres nulos, y el controlador respondía
 * «falta el objetivo del cálculo»: la operación estaba en {@code IMPLEMENTADAS} y ninguna pantalla
 * podía llamarla.
 *
 * <p>Se corrigió el controlador y no el contrato, por tres motivos. El contrato está <b>derivado
 * del prototipo</b> (#312): los tres salen de los {@code filtros} de la pantalla, y sacarlos de la
 * consulta obligaría a que el generador contradijera al prototipo, que es justo lo que sus tablas
 * no son. Un filtro que viajara por el cuerpo dejaría a esta pantalla siendo la única de las 134
 * cuya búsqueda no se puede compartir por la URL ni volver atrás. Y {@code PredialController} ya
 * eligió esta forma para la pantalla hermana en #395 —los filtros de la consulta, el acto en el
 * cuerpo—, así que la otra salida dejaría a las dos determinaciones de Rentas hablando distinto.
 *
 * <p>Se aceptan los dos caminos y <b>gana el cuerpo</b> si trae el dato, igual que en el predial:
 * un cliente que ya mandaba el cuerpo sigue funcionando, y el que manda la URL —la interfaz— por
 * fin funciona. Lo que {@code ParametrosDeLaConsultaTest} fija es que los tres se puedan mandar por
 * la consulta, en las dos direcciones.
 *
 * <h2>Lo que NO viaja: el mínimo imponible</h2>
 *
 * <p>{@code minimoImponible} llegaba en el cuerpo, o sea <b>del cliente</b>. Es una cifra normativa
 * —el artículo 34 del TUO de la LTM lo escribe como porcentaje de la UIT— y sale del conjunto de
 * parámetros sellado del ejercicio (regla 5, ARQ-09 §3). Ya no se acepta: {@link
 * RegistrarDeterminacionVehicular} lo lee de {@code VEHICULAR_MINIMO} y {@code UIT}, y si el
 * conjunto no las trae la operación responde <b>422 nombrando la llave</b> —mismo trato que {@code
 * TRAMO_PREDIAL_LIMITE:2} en #395, {@code TASA_ANUNCIO:‹CLASE›} en #51 y {@code
 * BENEFICIO:‹CAMPANIA›} en #72—. No hay valor por omisión: el cero que se usaba antes no falla,
 * deja el impuesto en su importe bruto y solo se nota en los vehículos baratos, que son los que el
 * mínimo existe para cubrir.
 *
 * <p><b>Hoy faltan las dos llaves</b>, y eso es lo que esta operación contesta: ni {@code
 * ALICUOTA_VEHICULAR} —que ya se leía del conjunto desde #32— ni {@code VEHICULAR_MINIMO} están en
 * {@code docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv}, así que contra un
 * conjunto sellado de verdad la respuesta es un 422 que las nombra. Publicarlas es el circuito de
 * #188 —transcripción del artículo 34 al corpus, con sus dos firmas, y {@code publicar-parametros}—
 * y no cabe aquí: lo que sí cabe es que la operación lo diga en vez de calcular con un valor
 * inventado.
 *
 * <p><b>Y con la llave que falta va lo que la contiene</b> (#540): que el ejercicio no tenga ningún
 * conjunto sellado ({@code EjercicioSinSellar}) y que el vehículo no figure en la tabla de valores
 * referenciales del ejercicio ({@code SinValorReferencial}) salían como <b>500 {@code
 * ERROR_INTERNO} con identificador de incidencia</b>, y las dos son lo mismo que la llave ausente:
 * un dato normativo que todavía no se ha publicado. Un 500 le dice al cliente «reintenta», y
 * reintentar no lo va a arreglar nunca; además cada intento dejaba una incidencia ERROR en el
 * registro por lo que hoy es el estado normal del sistema (D-02a abierta).
 *
 * <p>Lo que <b>no</b> cambia: un fallo de verdad del servidor sigue siendo 500 con su incidencia.
 *
 * <h2>Simular y determinar, en la misma operación</h2>
 *
 * <p>{@code simulacion} sigue en el cuerpo, y ahí se queda: no identifica lo que se calcula —eso
 * son los filtros—, decide si la operación <b>escribe</b>. Y pasa a ser <b>obligatorio</b>, como en
 * {@code PredialController}: antes, una petición que no lo dijera asentaba la determinación, que es
 * la peor de las dos suposiciones posibles. Un cuerpo sin la marca se rechaza en vez de elegir por
 * quien atiende.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/vehicular/calculo")
@RequiereAcceso(acceso = "vehicular_calculo", privilegio = Privilegio.REGISTRO)
public class VehicularController {

    private static final String ORDEN_POR_OMISION = "placa";

    private static final String OBSERVACION_DE_LA_SIMULACION =
            "Simulacion del impuesto vehicular: se calcula y no se asienta ninguna determinacion"
                    + " (#399)";

    private final RegistrarDeterminacionVehicular servicio;
    private final ConsultaDeVehiculos consultaDeVehiculos;
    private final Clock reloj;

    public VehicularController(
            RegistrarDeterminacionVehicular servicio,
            ConsultaDeVehiculos consultaDeVehiculos,
            Clock reloj) {
        this.servicio = servicio;
        this.consultaDeVehiculos = consultaDeVehiculos;
        this.reloj = reloj;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalculoVehicularResource calcular(
            @RequestParam(required = false) @Nullable String placa,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestBody PeticionDeCalculoVehicular peticion) {

        Objetivo objetivo =
                new Objetivo(
                        peticion.vehiculoId(),
                        FiltroDeLaConsulta.primeroNoVacio(peticion.placa(), placa),
                        FiltroDeLaConsulta.primeroNoVacio(
                                peticion.codContribuyente(), codContribuyente));
        boolean simulacion = exigirSimulacion(peticion.simulacion());
        Observacion observacion = observacionDe(peticion.observacion(), simulacion);
        Ejercicio delCalculo =
                ejercicioDe(
                        FiltroDeLaConsulta.primeroNoVacio(peticion.ejercicio(), ejercicio),
                        simulacion);
        LocalDate fechaCalculo = LocalDate.now(reloj);

        List<DeterminacionVehicularResource> resultado = new ArrayList<>();
        RegistrarDeterminacionVehicular.@Nullable Calculo ultimo = null;
        try {
            for (Vehiculo vehiculo : resolverVehiculos(objetivo)) {
                try {
                    RegistrarDeterminacionVehicular.Calculo calculo =
                            servicio.calcular(
                                    exigirId(vehiculo.id()), delCalculo, simulacion, observacion);
                    ultimo = calculo;
                    resultado.add(DeterminacionVehicularResource.de(calculo, vehiculo));
                } catch (RegistrarDeterminacionVehicular.VehiculoNoAfecto fueraDePlazo) {
                    if (objetivo.esPuntual()) {
                        throw new ProblemaDeNegocio(
                                CodigoDeError.VALIDACION, mensajeDe(fueraDePlazo));
                    }
                    // Calculo por contribuyente: un vehiculo fuera de plazo se excluye, no se
                    // informa.
                }
            }
        } catch (ParametrosSellados.ParametroAusente
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarDeterminacionVehicular.SinValorReferencial sinValor) {
            // Esta NO lleva el discriminador, y esa es la mitad del criterio: el valor
            // referencial de un vehiculo no es una fila del conjunto sellado sino del cuadro que
            // publica el MEF por marca, modelo y ano (D-13, ADR-0017). Lo que falta se busca en
            // otro sitio, asi que darle el mismo miembro diria por contrato que no.
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinValor));
        }
        return ultimo == null
                ? CalculoVehicularResource.sinDeterminaciones(fechaCalculo)
                : CalculoVehicularResource.de(fechaCalculo, ultimo, resultado);
    }

    // ------------------------------------------------------------------

    /** Sobre qué se calcula, ya reunido de la consulta y del cuerpo. */
    private record Objetivo(
            @Nullable Long vehiculoId, @Nullable String placa, @Nullable String codContribuyente) {

        boolean esPuntual() {
            return vehiculoId != null || placa != null;
        }
    }

    private List<Vehiculo> resolverVehiculos(Objetivo objetivo) {
        Long vehiculoId = objetivo.vehiculoId();
        if (vehiculoId != null) {
            return List.of(
                    consultaDeVehiculos
                            .vehiculoPorId(vehiculoId)
                            .orElseThrow(
                                    () ->
                                            new ProblemaDeNegocio(
                                                    CodigoDeError.NO_ENCONTRADO,
                                                    "No hay ningun vehiculo con identificador "
                                                            + vehiculoId)));
        }
        String placa = objetivo.placa();
        if (placa != null) {
            return List.of(
                    consultaDeVehiculos
                            .vehiculoPorPlaca(Placa.de(placa))
                            .orElseThrow(
                                    () ->
                                            new ProblemaDeNegocio(
                                                    CodigoDeError.NO_ENCONTRADO,
                                                    "No hay ningun vehiculo con placa '"
                                                            + placa
                                                            + "'")));
        }
        String codContribuyente = objetivo.codContribuyente();
        if (codContribuyente != null) {
            CriterioDeVehiculo criterio =
                    new CriterioDeVehiculo(null, null, codContribuyente, EstadoVehiculo.ACTIVO);
            Pagina<VehiculoEncontrado> pagina =
                    consultaDeVehiculos.activosDe(
                            criterio,
                            Paginacion.de(0, Paginacion.TAMANO_MAXIMO, ORDEN_POR_OMISION));
            if (pagina.contenido().isEmpty()) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.NO_ENCONTRADO,
                        "El contribuyente '"
                                + codContribuyente
                                + "' no tiene ningun vehiculo activo");
            }
            return pagina.contenido().stream().map(VehiculoEncontrado::vehiculo).toList();
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Falta el objetivo del calculo: 'vehiculoId', 'placa' o 'codContribuyente'");
    }

    /**
     * Sin observación no se guarda (regla 10, RNF-052) — <b>cuando se guarda</b>.
     *
     * <p>Mismo reparto que {@code PredialController}: una simulación no modifica ningún dato —no
     * escribe fila de {@code determinacion} ni de {@code auditoria}—, así que la que se pasa
     * entonces la compone el sistema. Con {@code simulacion = false} vuelve a ser obligatoria del
     * usuario, porque ahí sí hay una determinación nueva que alguien tendrá que explicar.
     */
    private static Observacion observacionDe(@Nullable String texto, boolean simulacion) {
        if (texto == null || texto.isBlank()) {
            if (simulacion) {
                return Observacion.de(OBSERVACION_DE_LA_SIMULACION);
            }
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    /** Sin valor por omisión: ver el javadoc de la clase. */
    private static boolean exigirSimulacion(@Nullable Boolean simulacion) {
        if (simulacion == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir si esto simula o determina: «simulacion» es obligatorio. Con"
                            + " true se calcula y no se guarda nada; con false se asienta la"
                            + " determinacion");
        }
        return simulacion;
    }

    /**
     * El ejercicio del cálculo: obligatorio para asentar, optativo para simular.
     *
     * <p>Simular sin decirlo se lee como «el que corre», que es lo que la pantalla enseña al
     * abrirse. Asentar sin decirlo, no: el vehicular es un impuesto anual y elegir por el operador
     * de qué año se determina es la clase de suposición que nadie revisa hasta que llega el valor
     * del ejercicio equivocado.
     */
    private Ejercicio ejercicioDe(@Nullable String texto, boolean simulacion) {
        if (texto == null) {
            if (simulacion) {
                return Ejercicio.de(LocalDate.now(reloj));
            }
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir que ejercicio se determina: falta «ejercicio»");
        }
        try {
            return new Ejercicio(Integer.parseInt(texto));
        } catch (IllegalArgumentException mal) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ejercicio tiene que ser un ano: '" + texto + "'");
        }
    }

    private static long exigirId(@Nullable Long valor) {
        if (valor == null) {
            throw new IllegalStateException("Un vehiculo ya guardado siempre tiene identificador");
        }
        return valor;
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo del cálculo vehicular. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * <p>Exactamente uno de {@code vehiculoId}, {@code placa} o {@code codContribuyente} resuelve
     * el objetivo, y los tres últimos se pueden mandar también por la consulta, que es donde el
     * contrato los declara (#399). {@code minimoImponible} <b>ya no está</b>: es una cifra
     * normativa y sale del conjunto sellado, no del cliente (regla 5).
     */
    public record PeticionDeCalculoVehicular(
            @Nullable String observacion,
            @Nullable Long vehiculoId,
            @Nullable String placa,
            @Nullable String codContribuyente,
            @Nullable String ejercicio,
            @Nullable Boolean simulacion) {}
}
