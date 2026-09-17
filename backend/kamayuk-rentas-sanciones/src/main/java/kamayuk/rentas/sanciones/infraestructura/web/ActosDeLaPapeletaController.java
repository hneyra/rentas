package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeActosDeLaPapeleta;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.dominio.ActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.AcuseDelActo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.EstadoDelActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Todos los documentos emitidos por una papeleta: {@code GET
 * /api/v1/transito/papeletas/{numero}/actos} (#50, RF-065, AC 4).
 *
 * <p>«Registra los documentos emitidos por papeleta y conserva la secuencia del trámite», dice la
 * pantalla {@code transito_documentos}. La secuencia es una sola aunque los papeles salgan de tres
 * registros, y cada uno viaja con <b>su fecha y todos sus acuses</b> —una fila por intento—: el AC
 * 4 pide exactamente eso, y quedarse con el último acuse escondería que las diligencias anteriores
 * no encontraron a nadie.
 *
 * <p>No hay verbo de escritura aquí. Los documentos se emiten al dictar la resolución o al internar
 * el vehículo, en la misma transacción que el acto que los explica; una ruta que emitiera un
 * documento suelto produciría papel sin procedimiento detrás.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/papeletas/{numero}/actos")
@RequiereAcceso(acceso = "transito_documentos", privilegio = Privilegio.LECTURA)
public class ActosDeLaPapeletaController {

    private final ConsultaDeActosDeLaPapeleta consulta;

    public ActosDeLaPapeletaController(ConsultaDeActosDeLaPapeleta consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public ExpedienteResource listar(
            @PathVariable String numero, @RequestParam(required = false) @Nullable String familia) {

        Familia laFamilia =
                familia == null || familia.isBlank()
                        ? Familia.TRANSITO
                        : PeticionesDeSanciones.enumeradoDe(Familia.class, familia, "familia");
        try {
            return ExpedienteResource.de(consulta.de(laFamilia, numero));
        } catch (RegistrarDescargo.PapeletaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        }
    }

    /** El expediente de una papeleta: sus recursos y todos sus documentos, en orden de fecha. */
    public record ExpedienteResource(
            String papeleta,
            String familia,
            String estado,
            List<DescargoDelExpediente> descargos,
            List<ActoResource> actos) {

        static ExpedienteResource de(ConsultaDeActosDeLaPapeleta.Expediente expediente) {
            List<DescargoDelExpediente> recursos = new ArrayList<>();
            for (Descargo descargo : expediente.descargos()) {
                recursos.add(
                        new DescargoDelExpediente(
                                descargo.identificador(),
                                descargo.numeroExpediente(),
                                descargo.fecha(),
                                descargo.tipoRecurso().name(),
                                descargo.presentadoHasta(),
                                descargo.enPlazo()));
            }
            List<ActoResource> actos = new ArrayList<>();
            for (ActoDeLaPapeleta acto : expediente.actos()) {
                actos.add(ActoResource.de(acto));
            }
            return new ExpedienteResource(
                    expediente.papeleta().numero(),
                    expediente.papeleta().familia().name(),
                    expediente.papeleta().estado().name(),
                    List.copyOf(recursos),
                    List.copyOf(actos));
        }
    }

    /** Un recurso presentado contra la papeleta. */
    public record DescargoDelExpediente(
            long id,
            String nDeExpediente,
            LocalDate fecha,
            String tipoDeRecurso,
            LocalDate presentadoHasta,
            boolean enPlazo) {}

    /**
     * Un documento emitido, con su fecha, su estado y sus acuses.
     *
     * <h2>{@code estado} se añade a los acuses, no en su lugar (#185)</h2>
     *
     * <p>Hasta #185 lo único que este recurso decía de <b>cómo quedó</b> un acto eran sus {@code
     * acuses}, una fila por intento, y resumirlos en el último lo prohíbe {@code
     * ConsultaDeActosDeLaPapeleta} en su propio javadoc. La pantalla {@code tra-pap} dibujaba la
     * columna «Estado» diciendo que no había dato, y era lo correcto: escribir «Conforme» porque el
     * último intento salió bien afirmaría que la papeleta se puede cobrar, cuando una no notificada
     * dentro del plazo <b>caduca</b>.
     *
     * <p>Lo que se publica ahora es {@link EstadoDelActoDeLaPapeleta}, derivado en el dominio y no
     * en la pantalla, y {@code acuses} <b>sigue viajando entero</b>: la traza de los tres intentos
     * está donde estaba, y es lo que el administrado discute. Lo que el estado dice es si el acto
     * llegó a surtir efecto, que es un hecho de la lista completa y no de su última fila.
     *
     * <p><b>Lo que no dice, y por qué:</b> nada del plazo. Ver {@link EstadoDelActoDeLaPapeleta}:
     * el plazo es un valor del conjunto sellado, y pedirlo aquí dejaría esta operación contestando
     * 422 en toda municipalidad sin sellar —hoy, todas—.
     *
     * @param estado en qué punto de su notificación está, derivado de {@code acuses}
     */
    public record ActoResource(
            String clase,
            String tipo,
            String numero,
            LocalDate fecha,
            long documentoId,
            String observacion,
            String estado,
            List<AcuseResource> acuses) {

        static ActoResource de(ActoDeLaPapeleta acto) {
            List<AcuseResource> acuses = new ArrayList<>();
            for (AcuseDelActo acuse : acto.acuses()) {
                acuses.add(
                        new AcuseResource(
                                acuse.intento(),
                                acuse.fecha(),
                                acuse.modalidad().name(),
                                acuse.resultado().name(),
                                acuse.receptor(),
                                acuse.acuse(),
                                acuse.exigibleDesde()));
            }
            return new ActoResource(
                    acto.clase(),
                    acto.tipo(),
                    acto.numero(),
                    acto.fecha(),
                    acto.documentoId(),
                    acto.observacion().texto(),
                    acto.estado().name(),
                    List.copyOf(acuses));
        }
    }

    /** Una diligencia de notificación de un acto, con su acuse. */
    public record AcuseResource(
            int intento,
            LocalDate fecha,
            String modalidad,
            String resultado,
            @Nullable String recibidoPor,
            @Nullable String acuse,
            @Nullable LocalDate exigibleDesde) {}
}
