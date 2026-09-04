package kamayuk.rentas.coactiva.aplicacion;

import java.util.Locale;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vuelve a sacar la REC —o cualquier otro acto— tal como salio el dia en que se dicto (#41, RF-101,
 * RF-132).
 *
 * <h2>Reimprimir no es volver a generar</h2>
 *
 * <p>La deuda de 2027 recalculada en 2037 no da la misma cifra: los parametros cambiaron, se
 * pagaron cuotas, el interes siguio corriendo. Y el obligado tiene el papel de 2027 en la mano. Por
 * eso {@link EmitirDocumento} guarda <b>los datos con que se dibujo</b> y reimprimir es volver a
 * dibujarlos.
 *
 * <p>Que salga identico no se afirma: se comprueba. La emision guardo el SHA-256 de los bytes, y la
 * reimpresion lo vuelve a calcular; si no coinciden —porque alguien cambio el renderizador— la
 * operacion <b>falla</b> en vez de entregar un papel distinto al original con el mismo numero. En
 * un procedimiento coactivo esa diferencia es la que anula una resolucion.
 *
 * <p>El duplicado sale <b>marcado</b> como tal. Uno sin marcar circula como si fuera el original, y
 * en un expediente coactivo eso es un documento de mas.
 *
 * <h2>D-05</h2>
 *
 * <p>La reimpresion pasa por el mismo {@code PuntoDeFirma} que la emision, asi que cerrar D-05 no
 * obliga a volver sobre este codigo. Hoy devuelve los bytes sin firma digital, y el documento es
 * imprimible igual: el bloque de firmas del ejecutor y del auxiliar va en el pie.
 */
@Service
public class ReimprimirActoCoactivo {

    private final ActoCoactivoRepository actos;
    private final ExpedienteRepository expedientes;
    private final EmitirDocumento documentos;

    public ReimprimirActoCoactivo(
            ActoCoactivoRepository actos,
            ExpedienteRepository expedientes,
            EmitirDocumento documentos) {
        this.actos = actos;
        this.expedientes = expedientes;
        this.documentos = documentos;
    }

    /**
     * Vuelve a sacar el acto identificado por el numero de su documento.
     *
     * @param numeroDelActo el numero impreso
     * @param formato en que formato se quiere ahora; no tiene por que ser el de la emision
     * @param observacion por que se reimprime (regla 10, RNF-052)
     * @throws NotificarActoCoactivo.ActoInexistente si no hay ningun acto con ese numero
     * @throws EmitirDocumento.LaReimpresionNoCoincide si dibujar los datos guardados ya no da los
     *     mismos bytes
     */
    @Transactional
    public Reimpresion reimprimir(
            String numeroDelActo, FormatoDeDocumento formato, Observacion observacion) {

        ActoCoactivo acto =
                actos.porNumero(numeroDelActo.strip().toUpperCase(Locale.ROOT))
                        .orElseThrow(
                                () -> new NotificarActoCoactivo.ActoInexistente(numeroDelActo));
        ExpedienteCoactivo expediente =
                expedientes
                        .porId(acto.expedienteId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El acto "
                                                        + acto.numero()
                                                        + " apunta a un expediente que no"
                                                        + " existe"));

        EmitirDocumento.Emision emision =
                documentos.reimprimir(
                        acto.tipo().name(),
                        expediente.ejercicio(),
                        acto.numero(),
                        formato,
                        observacion);
        return new Reimpresion(acto, emision);
    }

    /**
     * Vuelve a sacar el ultimo acto de ese tipo del expediente.
     *
     * <p>Es lo que las acciones «Imprimir», «Caratula» y «REC 2» de {@code rec_impresion} piden:
     * quien opera marca expedientes, no numeros de documento. El «ultimo» importa porque de una
     * medida cautelar puede haber varias; de la REC-1 solo hay una ({@code acto_rec1_uq}).
     *
     * @throws ActoSinDictar si el expediente no tiene ningun acto de ese tipo
     */
    @Transactional
    public Reimpresion delExpediente(
            String numeroDeExpediente,
            TipoDeActoCoactivo tipo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(numeroDeExpediente)
                        .orElseThrow(
                                () ->
                                        new CambiarEstadoDelExpediente.ExpedienteInexistente(
                                                numeroDeExpediente));
        ActoCoactivo acto =
                actos.ultimoDe(expediente.identificador(), tipo)
                        .orElseThrow(() -> new ActoSinDictar(expediente.numero(), tipo));

        return new Reimpresion(
                acto,
                documentos.reimprimir(
                        acto.tipo().name(),
                        expediente.ejercicio(),
                        acto.numero(),
                        formato,
                        observacion));
    }

    /** El expediente no tiene ningun acto de ese tipo que reimprimir. */
    public static final class ActoSinDictar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ActoSinDictar(String expediente, TipoDeActoCoactivo tipo) {
            super(
                    "El expediente "
                            + expediente
                            + " no tiene ninguna "
                            + tipo.titulo()
                            + " que reimprimir: reimprimir es volver a sacar lo emitido, no"
                            + " emitir");
        }
    }

    /**
     * El acto y el papel que se volvio a sacar.
     *
     * @param acto el acto reimpreso
     * @param emision los bytes y el registro, ya con una reimpresion mas
     */
    public record Reimpresion(ActoCoactivo acto, EmitirDocumento.Emision emision) {}
}
