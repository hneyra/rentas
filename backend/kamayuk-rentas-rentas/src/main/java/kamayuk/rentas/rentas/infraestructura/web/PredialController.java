package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.rentas.aplicacion.CandadoDeEmision;
import kamayuk.rentas.rentas.aplicacion.CuadroPredialParametrizado;
import kamayuk.rentas.rentas.aplicacion.DeterminarPredial;
import kamayuk.rentas.rentas.aplicacion.DeterminarPredialMasivo;
import kamayuk.rentas.rentas.aplicacion.RegistrarCorridaDeEmision;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La capa web de la determinacion predial (#395): {@code predial_individual} y {@code
 * predial_masivo}.
 *
 * <p>#30 dejo la regla de negocio —{@code RegistrarDeterminacionPredial}, {@code
 * TramosProgresivosAcumulativos}, {@code MinimoImponible}— y su prueba; ningun controlador la
 * publicaba, asi que el calculo del predial se seguia haciendo fuera del sistema. Esto es esa capa.
 *
 * <h2>Simular y determinar, en la misma operacion</h2>
 *
 * <p>El contrato declara <b>una</b> operacion por pantalla, y la pantalla tiene los dos botones. La
 * diferencia va en el cuerpo, como ya hacia {@code VehicularController} con {@code simulacion}
 * (#32), y aqui es <b>obligatoria</b>: una peticion que no diga cual de las dos cosas quiere se
 * rechaza. Suponer «determina» emitiria deuda al pulsar un boton que dice «Simular» —el defecto que
 * la guarda del frontend evitaba mientras contestaba el proxy de datos—; suponer «simula» dejaria
 * de emitirla al pulsar el que dice «Calcular». Ninguna de las dos equivocaciones avisa, asi que no
 * hay valor por omision que elegir.
 *
 * <h2>Los filtros del contrato se leen de la consulta; lo demas, del cuerpo</h2>
 *
 * <p>El contrato declara {@code codContribuyente} y {@code ano} como parametros de consulta —son
 * los filtros que la pantalla dibuja— y el cuerpo como un objeto libre. Se aceptan los dos caminos
 * y gana el cuerpo si trae el dato: es lo que evita repetir aqui el desajuste que {@code
 * vehicular_calculo} arrastra desde #32, donde el controlador lee del cuerpo lo que el contrato
 * declara de consulta y ninguna pantalla puede llamarlo.
 *
 * <h2>Y el ejercicio se llama igual en las dos mitades (#541)</h2>
 *
 * <p>Se llamaba {@code ano} en la consulta y {@code ejercicio} en el cuerpo: dos nombres para el
 * mismo dato en la misma operacion, con el cliente obligado a saber cual toca en cada mitad. El
 * <b>canonico es {@code ejercicio}</b> —es como se llama en el dominio ({@link Ejercicio}), en la
 * columna, en el cuerpo de las dos prediales y en el {@code @RequestParam} del endpoint de las
 * corridas, tres metodos mas arriba— y ahora tambien se acepta por la consulta, declarado en el
 * contrato. {@code ano} se conserva porque es lo que produce el rotulo «Año» del prototipo, del que
 * el contrato esta derivado (#312), y es lo que la pantalla manda hoy: retirarlo dejaria a {@code
 * parametrosDeBusqueda} descartando el filtro en silencio, que es el defecto de #431.
 *
 * <p>La precedencia, de mas a menos: el cuerpo (#425 lo dejo decidido para las nueve operaciones
 * que aceptan los dos caminos), despues {@code ejercicio} de la consulta, y por ultimo {@code ano}.
 *
 * <h2>Que devuelve 422</h2>
 *
 * <p>Todo lo que es un dato que falta y no un sistema roto: una cifra del cuadro que el conjunto
 * sellado no trae —y el mensaje dice <b>cual</b>, {@code TRAMO_PREDIAL_LIMITE:2}, {@code
 * DERECHO_EMISION_PREDIAL}—, un predio de la base sin autovaluo declarado, un predio que no es del
 * contribuyente. Mismo trato que {@code TASA_ANUNCIO:‹CLASE›} en #51 y {@code BENEFICIO:‹CAMPANIA›}
 * en #72: quien opera tiene que enterarse de que le falta para poder pedirlo.
 *
 * <p><b>Y ahi entran tambien las dos que faltaban</b> (#540). Que el ejercicio no tenga ningun
 * conjunto sellado ({@code EjercicioSinSellar}) y que el conjunto sellado no parametrice ningun
 * punto de redondeo ({@code SinPuntosObservados}, con sus tres hermanas de {@link
 * PoliticasDeRedondeoSelladas}) salian como <b>500 {@code ERROR_INTERNO} con identificador de
 * incidencia</b>: nadie las traducia aqui y caian en el {@code @ExceptionHandler(Exception.class)}
 * de {@code ManejadorDeErrores}. Dos consecuencias, y la segunda es la que no se ve:
 *
 * <ul>
 *   <li>la interfaz no podia distinguir «falta publicar un parametro» —que se arregla publicandolo—
 *       de «el servidor esta roto» —que se arregla llamando a alguien—, y un cliente que reintenta
 *       un 500 reintenta para siempre;
 *   <li>y cada intento <b>escribia una incidencia con nivel ERROR en el registro</b>. Con D-02a
 *       abierta y ningun ejercicio sellado, eso es el estado <i>normal</i> del sistema hoy: el
 *       registro de errores se llenaba de lo que no es un error. Es el mismo razonamiento por el
 *       que #486 saco de ahi las peticiones ilegibles.
 * </ul>
 *
 * <p><b>Y una tercera, que se escapo de las dos anteriores</b> (#633). Un conjunto sellado que
 * <b>si</b> observa puntos de redondeo pero no el que este calculo atraviesa no es ninguna de las
 * dos de arriba: {@code SinPuntosObservados} no se lanza —hay filas—, y lo que sale es {@code
 * PoliticasDeRedondeo.PuntoSinPolitica}, del dominio compartido, que hasta #633 no capturaba nadie
 * en todo el backend. Las cuatro determinaciones que este controlador sirve atraviesan cuatro
 * puntos —{@code BASE_IMPONIBLE_DEL_PREDIO}, {@code BASE_DEL_CONTRIBUYENTE}, {@code
 * IMPUESTO_POR_TRAMO} y {@code CUOTA}—, asi que basta con que la campana de observacion vaya por el
 * tercero para que la emision entera conteste 500.
 *
 * <p><b>Y las siete salen con el miembro {@code parametroQueFalta}</b> (#691). #604 lo cableo solo
 * en {@code ConvenioController}, y aqui quedo anotado que extenderlo era su propio trabajo: ese
 * trabajo es #691, que lo llevo a los veinticinco puntos de traduccion de los seis modulos que
 * faltaban. Lo traduce {@link FaltaPublicar}, no un ayudante privado por controlador.
 *
 * <p>La septima —{@code PoliticasDeRedondeo.PuntoSinPolitica}— tiene su propio {@code catch} y no
 * por gusto: es la unica que no puede declarar {@code ParametroSinPublicar}, porque vive en el
 * dominio puro y no sabe de que ejercicio salieron sus politicas (regla 7). El ejercicio se lo pone
 * quien lo pidio, que es este controlador, y la llave se compone con el punto que la excepcion si
 * publica.
 *
 * <p>Lo que <b>no</b> cambia: un fallo de verdad del servidor sigue siendo 500 con su incidencia.
 * Una traduccion demasiado ancha —convertirlo todo en 422— es peor que el defecto que arregla, y
 * hay una prueba de contraste que lo mide.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/predial")
public class PredialController {

    private static final String ORDEN_DE_OBSERVADOS = "id";

    private final DeterminarPredial individual;
    private final DeterminarPredialMasivo masivo;
    private final RegistrarCorridaDeEmision corridas;
    private final Clock reloj;

    public PredialController(
            DeterminarPredial individual,
            DeterminarPredialMasivo masivo,
            RegistrarCorridaDeEmision corridas,
            Clock reloj) {
        this.individual = individual;
        this.masivo = masivo;
        this.corridas = corridas;
        this.reloj = reloj;
    }

    /**
     * La ultima corrida de emision del ejercicio, <b>sin</b> sus observados (#523).
     *
     * <p>Hasta este endpoint la corrida viajaba solo en la respuesta del {@code POST} que la
     * ejecuta: cerrar la pestana perdia el resultado de un proceso que toca decenas de miles de
     * cuentas, y volver a verlo exigia volver a correrlo. Con esto, la pantalla del calculo masivo
     * dibuja al abrir lo que hizo la ultima, y el panel del modulo deja de estar bloqueado (#503
     * F6).
     *
     * <p><b>Devuelve tambien las simulaciones</b>, y lo dice: {@code simulacion} distingue las dos,
     * y esconderlas haria que «ver los observados antes de emitir» —que es lo que el prototipo pide
     * hacer— no dejara nada que mirar despues.
     *
     * <p>Sin corridas del ejercicio contesta <b>204</b> y no una corrida vacia de ceros: «todavia
     * no se ha corrido» y «se corrio y no emitio nada» son dos cosas distintas, y una cabecera de
     * ceros las dice igual.
     */
    @GetMapping("/corridas/ultima")
    @RequiereAcceso(acceso = "predial_masivo", privilegio = Privilegio.LECTURA)
    public ResponseEntity<CorridaGuardadaResource> ultimaCorrida(
            @RequestParam(required = false) @Nullable String ejercicio) {
        Ejercicio elEjercicio = ejercicioDeLaCorrida(ejercicio, true);
        return corridas.ultimaDe(elEjercicio)
                .map(corrida -> ResponseEntity.ok(CorridaGuardadaResource.de(corrida)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Los observados de una corrida: la lista de cosas que arreglar (#523).
     *
     * <p>Aparte de la cabecera y paginados, no dentro de ella. Son cientos —534 en la corrida que
     * el prototipo dibuja— y una portada que los trajera siempre seria la peticion mas pesada del
     * sistema para una cifra que casi nadie abre.
     */
    @GetMapping("/corridas/{corridaId}/observados")
    @RequiereAcceso(acceso = "predial_masivo", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<CorridaPredialResource.ObservadoResource> observadosDeLaCorrida(
            @PathVariable long corridaId, ParametrosDePaginacion parametros) {
        return RespuestaPaginada.de(
                corridas.observadosDe(corridaId, parametros.aPaginacion(ORDEN_DE_OBSERVADOS)),
                observado ->
                        new CorridaPredialResource.ObservadoResource(
                                observado.codContribuyente(),
                                observado.nombre(),
                                observado.motivo()));
    }

    /**
     * Determina —o simula— el predial de un contribuyente sobre el autovaluo acumulado de todos sus
     * predios, con la escala progresiva acumulativa y el minimo imponible del conjunto sellado.
     */
    @PostMapping("/calculo-individual")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "predial_individual", privilegio = Privilegio.REGISTRO)
    public DeterminacionPredialResource calcular(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String ano,
            @RequestBody PeticionDeCalculoPredial peticion) {

        boolean simulacion = exigirSimulacion(peticion.simulacion());
        Observacion observacion =
                observacionDe(
                        peticion.observacion(),
                        simulacion,
                        "Simulacion del impuesto predial individual: se calcula y no se asienta"
                                + " ninguna determinacion (#395)");
        String contribuyente =
                exigir(
                        FiltroDeLaConsulta.primeroNoVacio(
                                peticion.codContribuyente(), codContribuyente),
                        "Hay que decir de que contribuyente se determina: falta"
                                + " «codContribuyente»");
        Ejercicio elEjercicio =
                ejercicioDe(
                        FiltroDeLaConsulta.primeroNoVacio(
                                peticion.ejercicio(),
                                FiltroDeLaConsulta.elCanonicoOSuAlias(ejercicio, ano)));

        List<DeterminarPredial.PredioDeclarado> predios = new ArrayList<>();
        List<PeticionDeCalculoPredial.PredioDelCalculo> declarados =
                peticion.predios() == null ? List.of() : peticion.predios();
        for (PeticionDeCalculoPredial.PredioDelCalculo predio : declarados) {
            Long id = predio.predioId();
            if (id == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Cada predio declarado dice a que predio corresponde su autovaluo: falta"
                                + " «predioId»");
            }
            predios.add(
                    new DeterminarPredial.PredioDeclarado(
                            id,
                            importe(predio.autovaluo(), "autovaluo"),
                            predio.valuoExonerado() == null
                                    ? null
                                    : importe(predio.valuoExonerado(), "valuoExonerado")));
        }

        try {
            return DeterminacionPredialResource.de(
                    individual.determinar(
                            new DeterminarPredial.Peticion(
                                    elEjercicio,
                                    contribuyente,
                                    predios,
                                    peticion.modalidad() == null ? "" : peticion.modalidad(),
                                    simulacion),
                            observacion));
        } catch (DeterminarPredial.ContribuyenteInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (DeterminarPredial.SinPrediosEnElPadron
                | DeterminarPredial.PredioSinAutovaluo
                | DeterminarPredial.PredioRepetido
                | DeterminarPredial.PredioAjeno mal) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(mal));
        } catch (CuadroPredialParametrizado.ParametroDelPredialAusente
                | ParametrosSellados.ParametroAusente
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (PoliticasDeRedondeo.PuntoSinPolitica sinPolitica) {
            // La unica que no puede publicar su ejercicio: vive en el dominio puro y no sabe de
            // que conjunto salieron sus politicas (regla 7). El ejercicio lo pone quien lo pidio,
            // y la llave se compone con el punto, que la excepcion si nombra (#691).
            throw FaltaPublicar.problema(elEjercicio, sinPolitica);
        }
    }

    /**
     * Recalcula el padron declarado del ejercicio y deja constancia de los contribuyentes
     * observados que quedan fuera de la emision.
     */
    @PostMapping("/calculo-masivo")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "predial_masivo", privilegio = Privilegio.EJECUCION)
    public CorridaPredialResource correr(@RequestBody PeticionDeCalculoMasivo peticion) {
        boolean simulacion = exigirSimulacion(peticion.simulacion());
        Observacion observacion =
                observacionDe(
                        peticion.observacion(),
                        simulacion,
                        "Simulacion de la emision anual del predial: se recorre el padron y no se"
                                + " asienta ninguna determinacion (#395)");
        Ejercicio ejercicio = ejercicioDeLaCorrida(peticion.ejercicio(), simulacion);
        rechazarLoQueNoHace(peticion);

        try {
            return CorridaPredialResource.de(
                    masivo.ejecutar(
                            new DeterminarPredialMasivo.Peticion(
                                    ejercicio,
                                    peticion.alcance() == null ? "" : peticion.alcance(),
                                    peticion.sector(),
                                    peticion.codigoDesde(),
                                    peticion.codigoHasta(),
                                    peticion.modalidad() == null ? "" : peticion.modalidad(),
                                    Boolean.TRUE.equals(peticion.recalculaYaEmitidos()),
                                    simulacion),
                            observacion));
        } catch (CandadoDeEmision.ValuacionSinCerrar
                | CandadoDeEmision.ValuacionIncompleta
                | CandadoDeEmision.ValuacionQueNoCuadra faltaLaValuacion) {
            // El candado de ADR-0027 §2, dicho por HTTP (P5C).
            //
            // 409 y no 422, y la diferencia importa: un 422 dice «corrige lo que mandaste», y
            // aqui no hay nada en la peticion que corregir. Lo que pasa es que el sistema NO
            // ESTA EN ESTADO de emitir —falta que `catastro` cierre su corrida, o que la cola
            // termine de aplicarse—, y eso se arregla esperando o corriendo otra cosa, no
            // reescribiendo el formulario. El mensaje del candado dice cual de las tres es y
            // cuantas valuaciones faltan.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(faltaLaValuacion));
        } catch (CuadroPredialParametrizado.ParametroDelPredialAusente
                | ParametrosSellados.ParametroAusente
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (PoliticasDeRedondeo.PuntoSinPolitica sinPolitica) {
            // La unica que no puede publicar su ejercicio: vive en el dominio puro y no sabe de
            // que conjunto salieron sus politicas (regla 7). El ejercicio lo pone quien lo pidio,
            // y la llave se compone con el punto, que la excepcion si nombra (#691).
            throw FaltaPublicar.problema(ejercicio, sinPolitica);
        } catch (IllegalArgumentException mal) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(mal));
        }
    }

    /**
     * Los dos interruptores que la pantalla dibuja y esta corrida no hace se rechazan en vez de
     * ignorarse.
     *
     * <p>Ignorarlos devolveria una corrida «correcta» a quien pidio ademas los arbitrios o la
     * cuponera, y la ausencia solo se notaria al buscar los recibos que nadie genero. Los arbitrios
     * son otro tributo con su propia determinacion —{@code DeterminarArbitrios}, #37— y la cuponera
     * es un documento, que es la capa de #43 y no esta.
     */
    private static void rechazarLoQueNoHace(PeticionDeCalculoMasivo peticion) {
        if (Boolean.TRUE.equals(peticion.incluyeArbitrios())) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Esta corrida determina el impuesto predial. Los arbitrios son otro tributo, con"
                            + " su propia determinacion por periodo, y no se emiten aqui");
        }
        if (Boolean.TRUE.equals(peticion.generaCuponeraPdf())) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Esta corrida determina; no genera documentos. La cuponera se imprime desde la"
                            + " emision de valores, con su numeracion y su rastro");
        }
    }

    /**
     * Sin observacion no se guarda (regla 10, RNF-052) — <b>cuando se guarda</b>.
     *
     * <p>La regla 10 gobierna las modificaciones de datos, y una simulacion no modifica ninguno: no
     * escribe fila de {@code determinacion} ni de {@code auditoria}. Exigirsela dejaria a la
     * pantalla sin poder ensenar el calculo antes de asentarlo, que es justo lo que su boton
     * «Simular» existe para hacer. La que se pasa entonces la <b>compone el sistema</b>, como en
     * las filas de {@code ACCESO} de {@code ConsultaDeTitulares}, y {@link
     * kamayuk.rentas.rentas.aplicacion.RegistrarDeterminacionPredial} la sigue recibiendo: la regla
     * de ArchUnit no se relaja, lo que cambia es quien la escribe cuando no hay nada que
     * justificar.
     *
     * <p>Con {@code simulacion = false} vuelve a ser obligatoria del usuario, porque ahi si hay una
     * determinacion nueva que alguien tendra que explicar.
     */
    private static Observacion observacionDe(
            @Nullable String texto, boolean simulacion, String laDelSistema) {
        if (texto == null || texto.isBlank()) {
            if (simulacion) {
                return Observacion.de(laDelSistema);
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

    /**
     * El ejercicio de la corrida: obligatorio para asentar, optativo para simular.
     *
     * <p>Simular sin decir el ejercicio se lee como «el que corre», que es lo que la pantalla
     * ensena al abrirse. Emitir sin decirlo, no: elegir por el operador que padron se emite es la
     * clase de suposicion que nadie revisa hasta que llegan los valores del ano equivocado.
     */
    private Ejercicio ejercicioDeLaCorrida(@Nullable String texto, boolean simulacion) {
        if ((texto == null || texto.isBlank()) && simulacion) {
            return Ejercicio.de(LocalDate.now(reloj));
        }
        return ejercicioDe(texto);
    }

    /** Sin valor por omision: ver el javadoc de la clase. */
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

    private static Ejercicio ejercicioDe(@Nullable String texto) {
        String pedido =
                exigir(texto, "Hay que decir que ejercicio se determina: falta «ejercicio»");
        try {
            return new Ejercicio(Integer.parseInt(pedido.strip()));
        } catch (IllegalArgumentException mal) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El ejercicio tiene que ser un ano: '" + pedido + "'");
        }
    }

    private static Dinero importe(@Nullable String texto, String campo) {
        String pedido = exigir(texto, "Falta «" + campo + "»");
        try {
            return Dinero.de(pedido.strip());
        } catch (IllegalArgumentException | ArithmeticException mal) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo «" + campo + "» tiene que ser un importe: '" + pedido + "'");
        }
    }

    private static String exigir(@Nullable String texto, String queFalta) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, queFalta);
        }
        return texto.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? CodigoDeError.VALIDACION.mensaje() : mensaje;
    }
}
