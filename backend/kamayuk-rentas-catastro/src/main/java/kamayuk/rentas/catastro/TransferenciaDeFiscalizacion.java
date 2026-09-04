package kamayuk.rentas.catastro;

import java.time.LocalDate;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La <b>unica</b> puerta por la que {@code fiscalizacion} escribe en {@code catastro} (ARQ-01 §3.5
 * y §4 regla 4, #52, RF-054).
 *
 * <p>Vive en el paquete raiz de {@code catastro}, que es su API publica (ARQ-01 §4, regla 1), junto
 * a {@link LectorDeFichas}, {@link LectorDeCaracteristicas}, {@link PadronDePredios} y {@link
 * GestorDeTitularidad}. De los cinco, este es el <b>unico que escribe</b> para {@code
 * fiscalizacion}, y esa asimetria no se deja a la buena voluntad: una regla de arquitectura la
 * comprueba —{@code SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION}— con su clase de muestra
 * que la viola.
 *
 * <h2>Por que un puerto propio y no {@code ActualizarFichaCatastral}</h2>
 *
 * <p>Porque el caso de uso de catastro vive en {@code .aplicacion}, y Spring Modulith trata como
 * interno todo lo que esta en un subpaquete: {@code fiscalizacion} no lo puede importar. Pero el
 * motivo de fondo no es tecnico. Un puerto con <b>un</b> metodo y un nombre que dice de donde viene
 * la escritura es lo que permite responder, mirando el codigo y no la memoria de nadie, a la
 * pregunta que ARQ-01 §3.5 llama la frontera delicada: por donde entra al padron un dato que no
 * declaro el contribuyente.
 *
 * <h2>Lo que este puerto NO admite, y es deliberado</h2>
 *
 * <p>No admite un identificador de version ni un numero de version: la version nueva se abre sobre
 * la que este <b>vigente en la fecha del acto</b>, y esa la resuelve {@code catastro}. Si el
 * llamador pudiera elegir, podria versionar sobre una version ya cerrada y ramificar el historial.
 *
 * <p>No admite borrar, ni cerrar sin abrir, ni tocar una version anterior. Lo unico que hace es lo
 * que el manual llama actualizar el catastro: copiar la vigente, cerrarla y abrir la siguiente
 * (cap. 2 §Actualizacion del Catastro).
 */
public interface TransferenciaDeFiscalizacion {

    /**
     * Inscribe lo hallado en fiscalizacion como version nueva de la ficha {@code UNICA} del predio.
     *
     * <p>La version anterior queda <b>intacta y cerrada</b> el dia anterior a {@code desde}; la
     * nueva nace con {@code origen = FISCALIZACION}, el documento que la sustenta, el usuario que
     * la registro y la observacion (AC 2 de #52). Reconstruir el padron anterior es preguntar por
     * la ficha vigente a una fecha anterior a {@code desde} (AC 5).
     *
     * <p>{@code areaHallada} y {@code usoHallado} nulos significan «lo mismo que tenia»: una
     * fiscalizacion puede hallar conforme el area y distinto el uso, o al reves, y sobrescribir con
     * un nulo borraria lo declarado sin que ningun {@code DELETE} apareciera en el diff. Es el
     * mismo criterio que las listas nulas de {@code ActualizarFichaCatastral}.
     *
     * @param predioId el predio fiscalizado
     * @param desde desde cuando rige lo hallado; la version anterior se cierra el dia antes
     * @param documentoOrigen el numero de la resolucion de determinacion que lo sustenta
     * @param areaHallada la superficie medida en campo, si difiere; {@code null} si no se corrige
     * @param usoHallado el uso observado, si difiere; {@code null} si no se corrige
     * @param observacion por que se inscribe (regla 10, RNF-052)
     * @throws SinFichaQueVersionar si el predio no tiene ficha {@code UNICA} vigente a esa fecha:
     *     la primera version se registra, no se transfiere
     */
    VersionTransferida inscribirLoHallado(
            long predioId,
            LocalDate desde,
            String documentoOrigen,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            Observacion observacion);

    /**
     * El predio no tiene ficha vigente a esa fecha.
     *
     * <p>Es una excepcion del puerto y no de {@code .aplicacion} por lo mismo que el puerto existe:
     * quien la captura esta en otro contexto acotado y no puede importar el interior de este.
     */
    final class SinFichaQueVersionar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinFichaQueVersionar(long predioId, LocalDate fecha) {
            super(
                    "El predio "
                            + predioId
                            + " no tiene ficha unica vigente al "
                            + fecha
                            + ": la transferencia versiona lo que ya esta inscrito, y la primera"
                            + " version se registra en catastro");
        }
    }
}
