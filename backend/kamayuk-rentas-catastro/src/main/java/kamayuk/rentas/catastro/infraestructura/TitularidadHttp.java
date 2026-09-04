package kamayuk.rentas.catastro.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.stereotype.Component;

/**
 * La titularidad de un predio: la mitad que se lee sale por HTTP; la que escribe, todavia no (C-5).
 *
 * <h2>{@link #vigenteDe} — conectada</h2>
 *
 * <p>{@code GET /catastro/titularidad/cuota?predio=&contribuyente=&fecha=}. Es la unica lectura de
 * la frontera que publica el {@code titularidadId}, porque es el identificador con el que despues
 * se transfiere; el listado de titulares no lo lleva a proposito.
 *
 * <p>«Esta persona no tiene cuota vigente en este predio» vuelve como {@code tieneCuota:false} con
 * 200 delante, no como 404: si fuera 404 seria indistinguible de haber pedido una ruta que no
 * existe, y las dos se arreglan de maneras opuestas —una es un dato del padron, la otra un
 * despliegue—. Aqui se traduce a {@code Optional.empty()}, que es lo que el puerto siempre
 * significo y lo que hace que un registro de transferencia falle diciendo {@code
 * TransferenteSinTitularidad}.
 *
 * <h2>{@link #transferir} — NO conectada, y el motivo no es que falte la ruta</h2>
 *
 * <p>Lanza {@link ClienteHttpDeCatastro.EscrituraSinTransaccionCompartida}. Su unico llamador es
 * {@code RegistrarTransferencia.transferirPredio}, que dentro de <b>una</b> {@code @Transactional}
 * hace tres cosas en este orden: cierra la cuota del transferente y abre la del adquiriente (esto),
 * inserta la fila de {@code transferencia} y escribe su auditoria. Las dos ultimas son de {@code
 * rentas}.
 *
 * <p>Servido por HTTP, {@code catastro} confirmaria la primera por su cuenta. Si la insercion de
 * {@code transferencia} fallara despues, el predio habria cambiado de dueno y <b>no existiria el
 * acto que lo justifica</b>: ninguna cifra pareceria mal, y el unico sintoma seria una titularidad
 * que nadie sabe por que se movio. Es literalmente lo que #52 midio cuando se le dio {@code
 * REQUIRES_NEW} a la version de la ficha —«12 fichas donde debe haber 11»—.
 *
 * <p><b>Lo que lo desbloquea no es publicar la ruta</b>, y por eso la excepcion es otra clase: hace
 * falta o bien que la escritura remota sea la ultima y reversible por compensacion, o bien el buzon
 * de eventos de ADR-0027, que P5C dejo declarado como hueco 3 —«no hay cola, no hay suscripcion, no
 * hay reintento»—. Y el buzon solo no basta: quien llama necesita el {@code titularidadId} nuevo en
 * la misma peticion, asi que una escritura asincrona tampoco lo contesta.
 */
@Component
public class TitularidadHttp implements GestorDeTitularidad {

    private final ClienteHttpDeCatastro catastro;

    public TitularidadHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public Optional<CuotaDeTitularidad> vigenteDe(
            long predioId, long contribuyenteId, LocalDate fecha) {

        String que =
                "leer la cuota del contribuyente "
                        + contribuyenteId
                        + " sobre el predio "
                        + predioId
                        + " al "
                        + fecha;
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/titularidad/cuota?predio="
                                + predioId
                                + "&contribuyente="
                                + contribuyenteId
                                + "&fecha="
                                + fecha,
                        que);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(cuerpo, fecha, que);

        if (cuerpo.path("predioId").asLong() != predioId
                || cuerpo.path("contribuyenteId").asLong() != contribuyenteId) {
            // Con esto se transfiere una cuota: leer la de otro predio o la de otra persona
            // moveria la titularidad equivocada, y el papel saldria impecable (#298).
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": la respuesta es del predio "
                            + cuerpo.path("predioId").asLong()
                            + " y del contribuyente "
                            + cuerpo.path("contribuyenteId").asLong(),
                    null);
        }
        if (!cuerpo.path("tieneCuota").asBoolean()) {
            return Optional.empty();
        }
        return Optional.of(
                new CuotaDeTitularidad(
                        cuerpo.path("titularidadId").asLong(),
                        predioId,
                        contribuyenteId,
                        Porcentaje.de(cuerpo.path("porcentaje").asText("0"))));
    }

    @Override
    public CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {

        throw new ClienteHttpDeCatastro.EscrituraSinTransaccionCompartida(
                "transferir la titularidad " + titularidadAnteriorId,
                "el registro de la transferencia en `rentas` y su auditoria");
    }
}
