package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.IniciarCorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generación masiva de valores por papeletas: {@code POST
 * /api/v1/transito/valores/generacion-masiva} y {@code POST
 * /api/v1/infracciones/administrativas/valores/generacion-masiva} (#53, RF-066, RF-073).
 *
 * <h2>Dos rutas y dos permisos, un solo camino</h2>
 *
 * <p>{@code transito_valores} y {@code adm_valores} son dos opciones del menú con dos
 * {@code @RequiereAcceso} distintos: quien puede emitir los valores administrativos no tiene por
 * qué poder emitir los de tránsito. Lo que hay detrás es el mismo caso de uso con otra {@link
 * Familia}, y por eso los dos métodos viven en la misma clase: dos clases gemelas se separarían el
 * día que alguien arreglara un defecto en una sola.
 *
 * <h2>Esta petición registra el criterio; no emite ni un valor</h2>
 *
 * <p>Devuelve <b>201</b> con la corrida y sus candidatos. La emisión corre después, en el perfil
 * batch (ADR-0003): una corrida de miles de papeletas puede tardar minutos, y esa espera no tiene
 * por qué competir con la ventanilla por el mismo proceso. Es el mismo reparto que {@code POST
 * /valores/masivo} (#38).
 *
 * <h2>El número no entra por aquí</h2>
 *
 * <p>{@link PeticionDeCorridaDeValores} no tiene ningún campo para el número del valor, la serie ni
 * un correlativo de arranque. El número lo pone {@code valor_correlativo} (V26) al emitir, por el
 * mismo camino que la emisión individual de #37: es el primer criterio de aceptación de #53, y su
 * garantía es que no hay por dónde mandar otro.
 */
@RestController
public class GeneracionMasivaDeValoresController {

    private final IniciarCorridaDeValores iniciar;
    private final Clock reloj;

    public GeneracionMasivaDeValoresController(IniciarCorridaDeValores iniciar, Clock reloj) {
        this.iniciar = iniciar;
        this.reloj = reloj;
    }

    @PostMapping(Api.RAIZ + "/transito/valores/generacion-masiva")
    @RequiereAcceso(acceso = "transito_valores", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<CorridaDeValoresResource> transito(
            @RequestBody PeticionDeCorridaDeValores peticion) {
        return registrar(Familia.TRANSITO, peticion);
    }

    @PostMapping(Api.RAIZ + "/infracciones/administrativas/valores/generacion-masiva")
    @RequiereAcceso(acceso = "adm_valores", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<CorridaDeValoresResource> administrativas(
            @RequestBody PeticionDeCorridaDeValores peticion) {
        return registrar(Familia.ADMINISTRATIVA, peticion);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<CorridaDeValoresResource> registrar(
            Familia familia, PeticionDeCorridaDeValores peticion) {

        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        LocalDate fechaCriterio =
                peticion.fechaCriterio() == null || peticion.fechaCriterio().isBlank()
                        ? LocalDate.now(reloj)
                        : PeticionesDeSanciones.fechaDe(peticion.fechaCriterio(), "fechaCriterio");

        List<String> papeletas = peticion.papeletas();
        boolean porSeleccion = papeletas != null && !papeletas.isEmpty();
        boolean porRango =
                peticion.desde() != null
                        && !peticion.desde().isBlank()
                        && peticion.hasta() != null
                        && !peticion.hasta().isBlank();

        if (porSeleccion == porRango) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Se necesita 'papeletas' (seleccion) o el par 'desde'/'hasta' (rango), y solo"
                            + " uno de los dos: con los dos, cual gana dependeria del orden en que"
                            + " se miren");
        }

        try {
            CorridaDeValores corrida =
                    porSeleccion
                            ? iniciar.porSeleccion(
                                    familia,
                                    java.util.Objects.requireNonNull(papeletas),
                                    fechaCriterio,
                                    observacion)
                            : iniciar.porRango(
                                    familia,
                                    PeticionesDeSanciones.fechaDe(peticion.desde(), "desde"),
                                    PeticionesDeSanciones.fechaDe(peticion.hasta(), "hasta"),
                                    fechaCriterio,
                                    observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(CorridaDeValoresResource.de(corrida));
        } catch (IniciarCorridaDeValores.CandidatosInvalidos invalidos) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    PeticionesDeSanciones.mensajeDe(invalidos),
                    List.copyOf(invalidos.numeros()));
        } catch (IniciarCorridaDeValores.SinCandidatos vacia) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, PeticionesDeSanciones.mensajeDe(vacia));
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }
}
