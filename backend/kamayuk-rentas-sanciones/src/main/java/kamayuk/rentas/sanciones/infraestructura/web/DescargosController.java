package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
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
 * Descargos y reclamos de papeletas: {@code POST /api/v1/transito/descargos} (#50, RF-064).
 *
 * <p>Sirve a las <b>dos familias</b> aunque la ruta diga «tránsito»: la pantalla que el manual da
 * es la de tránsito, y el modelo de papeleta es uno solo (ARQ-01 §3.6). El cuerpo lleva la familia,
 * y por omisión es tránsito —que es lo que la pantalla manda—.
 *
 * <p>Ningún {@code PUT} ni {@code PATCH}: un descargo es el escrito que alguien firmó y presentó,
 * no el estado de un trámite. Resolverlo es dictar una resolución de gerencia, y {@code descargo}
 * no admite {@code UPDATE} desde V41.
 *
 * <p><b>{@code nDeExpediente} y {@code papeleta} también viajan por la consulta</b> (#425). Son los
 * dos filtros que la pantalla dibuja —«Nº de expediente» y «Papeleta»— y el contrato los declara
 * {@code in: query}; leerlos solo del cuerpo dejaba la operación publicada y sin ninguna pantalla
 * que pudiera llamarla. Se siguen aceptando en el cuerpo, y ahí ganan: ver {@link
 * FiltroDeLaConsulta}.
 *
 * <h2>Qué devuelve 422, y por qué no 500 (#562)</h2>
 *
 * <p>El plazo para presentar el descargo sale del <b>conjunto sellado</b> que rige a la fecha de la
 * infracción ({@link PlazosDeSancionesParametrizados}, regla 5). Ni que falte el conjunto entero
 * ({@code EjercicioSinSellar}) ni que falte la llave dentro de él ({@code PlazoSinParametrizar})
 * estaban traducidas: las dos caían en el {@code @ExceptionHandler(Exception.class)} de {@code
 * ManejadorDeErrores} y salían como <b>500 {@code ERROR_INTERNO} con identificador de
 * incidencia</b>. Con D-02a abierta —y con {@code PLAZO:DESCARGO_PAPELETA} sin transcribir en el
 * corpus— ese es el estado <i>normal</i> del sistema, así que registrar un descargo era
 * inalcanzable y cada intento dejaba una incidencia de nivel ERROR en el registro del servidor.
 *
 * <p>Sanciones es el único módulo del censo de #562 donde escapaban <b>las dos</b>; en valores,
 * coactiva y licencias la llave que falta ya estaba traducida y solo faltaba el conjunto.
 *
 * <p>El mensaje es el de la propia excepción: nombra la llave —{@code PLAZO:DESCARGO_PAPELETA}— o,
 * cuando lo que falta es el conjunto entero y no hay llave que nombrar, el <b>ejercicio</b>. Un
 * fallo de verdad del servidor sigue siendo 500 con su incidencia: la lista nombra las excepciones
 * una a una y no captura {@code RuntimeException}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/descargos")
@RequiereAcceso(acceso = "transito_descargos", privilegio = Privilegio.REGISTRO)
public class DescargosController {

    private final RegistrarDescargo servicio;

    public DescargosController(RegistrarDescargo servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DescargoResource registrar(
            @RequestParam(required = false) @Nullable String nDeExpediente,
            @RequestParam(required = false) @Nullable String papeleta,
            @RequestBody PeticionDeDescargo peticion) {
        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        Familia familia =
                peticion.familia() == null
                        ? Familia.TRANSITO
                        : PeticionesDeSanciones.enumeradoDe(
                                Familia.class, peticion.familia(), "familia");

        try {
            RegistrarDescargo.Registrado registrado =
                    servicio.registrar(
                            familia,
                            PeticionesDeSanciones.exigir(
                                    FiltroDeLaConsulta.primeroNoVacio(
                                            peticion.papeleta(), papeleta),
                                    "papeleta"),
                            new RegistrarDescargo.Peticion(
                                    PeticionesDeSanciones.exigir(
                                            FiltroDeLaConsulta.primeroNoVacio(
                                                    peticion.nDeExpediente(), nDeExpediente),
                                            "nDeExpediente"),
                                    PeticionesDeSanciones.fechaDe(
                                            peticion.fechaDePresentacion(), "fechaDePresentacion"),
                                    PeticionesDeSanciones.enumeradoDe(
                                            TipoDeRecurso.class,
                                            peticion.tipoDeRecurso(),
                                            "tipoDeRecurso"),
                                    PeticionesDeSanciones.exigir(
                                            peticion.fundamento(), "fundamento")),
                            observacion);
            return DescargoResource.de(registrado);
        } catch (RegistrarDescargo.PapeletaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        } catch (PlazosDeSancionesParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Las dos de parámetros no son un fallo del servidor: es una cifra que todavía nadie
            // ha publicado, y con D-02a abierta es el estado normal. Ver la cabecera de la clase.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarDescargo.PapeletaSinNadaQueImpugnar | IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    /**
     * El cuerpo de un descargo. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se registra (regla 10, RNF-052)
     * @param familia {@code TRANSITO} o {@code ADMINISTRATIVA}; por omisión, tránsito
     * @param papeleta el número de la papeleta impugnada
     * @param nDeExpediente el número con que entra por mesa de partes
     * @param fechaDePresentacion el día en que se presentó
     * @param tipoDeRecurso descargo, reconsideración, apelación o nulidad
     * @param fundamento el fundamento del administrado
     */
    public record PeticionDeDescargo(
            @Nullable String observacion,
            @Nullable String familia,
            @Nullable String papeleta,
            @Nullable String nDeExpediente,
            @Nullable String fechaDePresentacion,
            @Nullable String tipoDeRecurso,
            @Nullable String fundamento) {}

    /**
     * El descargo registrado.
     *
     * <p>{@code presentadoHasta} y {@code plazo} viajan juntos a propósito: la pantalla dibuja
     * «Dentro del plazo (5 días hábiles)», y esa frase la compone el backend con el plazo
     * <b>parametrizado</b>, nunca la interfaz con un número escrito a mano (regla 5).
     */
    public record DescargoResource(
            long id,
            String nDeExpediente,
            String papeleta,
            LocalDate fecha,
            String tipoDeRecurso,
            String fundamento,
            LocalDate presentadoHasta,
            boolean enPlazo,
            String plazo,
            String observacion) {

        static DescargoResource de(RegistrarDescargo.Registrado registrado) {
            Descargo descargo = registrado.descargo();
            return new DescargoResource(
                    descargo.identificador(),
                    descargo.numeroExpediente(),
                    registrado.papeleta().numero(),
                    descargo.fecha(),
                    descargo.tipoRecurso().name(),
                    descargo.sustento(),
                    descargo.presentadoHasta(),
                    descargo.enPlazo(),
                    registrado.plazo().toString(),
                    descargo.observacion().texto());
        }
    }
}
