package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.AnularPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ObligacionCompartidaConOtraPapeleta;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anulación de una papeleta: {@code POST /api/v1/transito/papeletas/{numero}/anulacion} (#267).
 *
 * <p>Es la <b>única</b> escritura que mueve el estado de una papeleta, y hasta #267 no existía: los
 * siete valores de {@code EstadoDePapeleta} eran uno alcanzable y seis que nadie escribía, de modo
 * que las dos guardas que descartan una papeleta muerta —{@code RegistrarDescargo} y {@code
 * ResolverConResolucionDeGerencia}— no descartaban nada. Por qué sólo {@code ANULADA} y no también
 * {@code PRESCRITA}, con la medida, está en {@link AnularPapeleta}.
 *
 * <p><b>Sirve a las dos familias aunque la ruta diga «tránsito»</b>, igual que {@link
 * DescargosController} y por lo mismo: la pantalla que el manual da es la de tránsito y el modelo
 * de papeleta es uno solo (ARQ-01 §3.6). El cuerpo lleva la familia, y por omisión es tránsito.
 *
 * <p>{@code MODIFICACION} y no {@code REGISTRO}: no nace ninguna fila, se mueve una columna de una
 * que ya existe. Y lleva {@code oTambien} por lo mismo que {@code POST
 * /fiscalizacion/actas/{id}/anulacion} (#214): quien opera las infracciones administrativas tiene
 * que poder anular la suya sin el acceso de tránsito.
 *
 * <p>El cuerpo es la observación (regla 10) y la fecha del acto, que es la del día en que se anula
 * y no la de su registro (regla 9). La fecha viaja porque la anulación puede registrarse después
 * del acto, y además es la <b>fecha valor</b> de los asientos de la baja: sin ella el libro diría
 * el día en que alguien tecleó.
 *
 * <p>{@code 201} y no {@code 200} por lo mismo que los demás actos de este contrato: lo que se crea
 * es el <b>acto</b>, que queda en {@code auditoria} con su antes y su después.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/papeletas")
@RequiereAcceso(
        acceso = "papeletas",
        oTambien = "infracciones_adm",
        privilegio = Privilegio.MODIFICACION)
public class AnulacionDePapeletaController {

    private final AnularPapeleta servicio;

    public AnulacionDePapeletaController(AnularPapeleta servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/{numero}/anulacion")
    @ResponseStatus(HttpStatus.CREATED)
    public PapeletaAnuladaResource anular(
            @PathVariable String numero, @RequestBody PeticionDeAnulacion peticion) {

        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        Familia familia =
                peticion.familia() == null
                        ? Familia.TRANSITO
                        : PeticionesDeSanciones.enumeradoDe(
                                Familia.class, peticion.familia(), "familia");

        try {
            return PapeletaAnuladaResource.de(
                    servicio.anular(
                            familia,
                            numero,
                            PeticionesDeSanciones.fechaDe(peticion.fecha(), "fecha"),
                            observacion));
        } catch (RegistrarDescargo.PapeletaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        } catch (AnularPapeleta.PapeletaConResolucionDeMulta
                | AnularPapeleta.AnulacionQueNoDaDeBaja
                | ObligacionCompartidaConOtraPapeleta
                | Papeleta.TransicionIlegal conflicto) {
            // 409 y no 422: la peticion es correcta, lo que no admite el acto es la situacion en
            // que esta la papeleta. La interfaz distingue las dos para saber si reintentar sirve.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, PeticionesDeSanciones.mensajeDe(conflicto));
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    // ------------------------------------------------------------------

    /**
     * El cuerpo de una anulación. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se anula (regla 10, RNF-052)
     * @param fecha el día del acto, y la fecha valor de los asientos de la baja (regla 9)
     * @param familia {@code TRANSITO} o {@code ADMINISTRATIVA}; por omisión, tránsito
     */
    public record PeticionDeAnulacion(
            @Nullable String observacion, @Nullable String fecha, @Nullable String familia) {}

    /**
     * La papeleta anulada y lo que la anulación dio de baja en el libro.
     *
     * @param papeleta la fila entera, ya {@code ANULADA}: no se borró ni se editó nada más
     * @param dadoDeBaja lo que se extinguió, con su fecha (regla 9); nulo si no se debía nada
     * @param asientosDeBaja cuántos asientos escribió la baja; cero si no se debía nada
     */
    public record PapeletaAnuladaResource(
            PapeletaResource papeleta,
            @Nullable ImporteActualizado dadoDeBaja,
            int asientosDeBaja) {

        static PapeletaAnuladaResource de(AnularPapeleta.Anulada anulada) {
            return new PapeletaAnuladaResource(
                    PapeletaResource.de(anulada.papeleta()),
                    anulada.baja().asientos() == 0
                            ? null
                            : new ImporteActualizado(
                                    anulada.baja().importe(), anulada.baja().fecha()),
                    anulada.baja().asientos());
        }
    }
}
