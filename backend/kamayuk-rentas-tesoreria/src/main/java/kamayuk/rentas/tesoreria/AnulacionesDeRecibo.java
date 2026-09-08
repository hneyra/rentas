package kamayuk.rentas.tesoreria;

import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import org.jspecify.annotations.Nullable;

/**
 * Si un recibo esta anulado, preguntado a la caja (P5D, ADR-0026).
 *
 * <h2>Por que existe, y por que aparece justo ahora</h2>
 *
 * <p>Hasta P5D esto no era un puerto: {@code CerrarConvenio} inyectaba {@code
 * MovimientoDeReciboRepository} y leia {@code recibo_movimiento} de esta misma base. `V7` retiro
 * esa tabla —el recibo vive en {@code caja}— y el convenio se quedo (ADR-0026 §5), asi que la
 * pregunta sigue haciendo falta y ya no se puede contestar leyendo. Un puerto es lo unico que
 * queda.
 *
 * <p>Vive en el paquete raiz, con {@link RecibosDeTramite}, {@link CobrosDeTasas} y {@link
 * AvanceDeCaja}, aunque hoy <b>solo lo pregunte una clase de este modulo</b>: los cuatro son la
 * misma cosa —lo que {@code rentas} le pregunta a {@code caja}— y tenerlos juntos es lo que hace
 * que la frontera se vea de un vistazo. Repartirlos por quien pregunta la escondería.
 *
 * <h2>Por que NO se resuelve con {@link RecibosDeTramite}</h2>
 *
 * <p>Seria lo natural —{@code ReciboDeTramite} ya publica {@code anulado}— y no se puede: ese
 * puerto pregunta <b>por el numero impreso</b> y {@code convenio_movimiento} guarda el {@code
 * recibo_id} interno, no el numero. Resolver uno desde el otro exigiria una ruta que {@code caja}
 * no publica, que es exactamente lo que el adaptador de este puerto declara.
 *
 * <p>Ensanchar {@link RecibosDeTramite} con un {@code porId} tampoco: ese puerto es el contrato de
 * {@code licencias} desde #44 y sus diez sitios de llamada no tienen por que enterarse de un
 * problema del convenio.
 *
 * <h2>Desde #40 tiene ruta, y sus dos modos de fallo son parte del puerto</h2>
 *
 * <p>{@code caja} publica {@code GET /recibos/por-id/&#123;reciboId&#125;} desde el propio P5D
 * —{@code e54c443}, el mismo commit que creo el muñon de este lado—: las dos mitades se escribieron
 * a la vez y nadie las unio. El adaptador vive en {@code infraestructura.AnulacionesDeReciboHttp}.
 *
 * <p>Las dos excepciones se declaran <b>aqui</b>, en el puerto, y no en el cliente HTTP, por lo
 * mismo que {@code AvanceDeCaja.CajaInalcanzable} y {@code OrdenesDeCobro.CajaInalcanzable}: quien
 * las caza —{@code ConvenioController}— no tiene por que conocer las clases de un cliente HTTP, y
 * el dia que la caja se llame por otro camino el llamador no cambia.
 */
public interface AnulacionesDeRecibo {

    /**
     * `caja` no contesta. No es «ese recibo no existe»: es que no se pudo preguntar.
     *
     * <p>Sale como {@code 503} y no como {@code 500}: no es un defecto de este servidor, es que el
     * otro no esta, y <b>reintentar si puede cambiar el resultado</b>. Es exactamente el trato que
     * {@code OrdenDeCobroController} le da a la caja al emitir una orden.
     *
     * <p><b>Lleva su {@link MotivoDeInalcanzable} como dato</b>, igual que {@code
     * AvanceDeCaja.CajaInalcanzable} desde #25 y al reves que {@code
     * OrdenesDeCobro.CajaInalcanzable}, que es de P5D y no lo tenia. Son dos cosas con dos remedios
     * distintos —«a este despliegue le falta {@code kamayuk.caja.url}», que no se cura solo, y «la
     * caja se cayo», que si— y el mensaje del cliente HTTP las separa <b>dentro de la frase</b>:
     * quien decide leyendo castellano deja de decidir bien en cuanto alguien reescribe el texto, y
     * nada se pone rojo.
     */
    final class CajaInalcanzable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: es un enum, que se serializa por su nombre.
        @SuppressWarnings("serial")
        private final MotivoDeInalcanzable motivo;

        public CajaInalcanzable(
                MotivoDeInalcanzable motivo, @Nullable String mensaje, @Nullable Throwable causa) {
            super(mensaje, causa);
            this.motivo = motivo;
        }

        /** Si falto la variable de entorno o si la caja no contesto (#25, AC-4). */
        public MotivoDeInalcanzable motivo() {
            return motivo;
        }
    }

    /**
     * Se pregunto, {@code caja} contesto, y lo que contesta es que <b>ese recibo no existe</b>.
     *
     * <p>Desde `V7` no hay clave foranea que lo garantice (ADR-0026 §3): {@code
     * convenio_movimiento.recibo_id} guarda un identificador de OTRA base, y nadie impide que
     * apunte a una fila que ya no esta. Es una incoherencia entre los dos padrones, no una averia y
     * no una falta de dato.
     *
     * <p><b>Y sobre todo no es {@code false}</b>: {@code caja} lo avisa por escrito en el javadoc
     * de esa ruta —«un identificador que no existe es 404, y el cliente NO puede leerlo como “no
     * esta anulado”»—. Leerlo asi dejaria anular un convenio con su cuota inicial cobrada y viva,
     * que es literalmente el hecho que la guarda de {@code CerrarConvenio} existe para impedir,
     * producido por la guarda misma.
     */
    final class ReciboQueNoConsta extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ReciboQueNoConsta(long reciboId) {
            super(
                    "El convenio dice que su cuota inicial se cobro con el recibo "
                            + reciboId
                            + ", y `caja` no tiene ningun recibo con ese identificador. No se puede"
                            + " dar por anulado ni por vigente: mientras eso no se aclare, anular"
                            + " el convenio dejaria —o no— dinero cobrado por un acto que ya no"
                            + " existe");
        }
    }

    /**
     * Si ese recibo tiene su anulacion registrada.
     *
     * <p><b>No devuelve {@code false} cuando no se puede preguntar</b>, y ahi esta todo: un {@code
     * false} significa «ese recibo sigue vigente», que es la respuesta que <b>impide</b> anular el
     * convenio, y un {@code true} inventado dejaria anular un convenio cuya cuota inicial se cobro
     * y sigue cobrada — dinero recibido por un acto que ya no existe, que es literalmente lo que la
     * guarda de {@code CerrarConvenio} existe para impedir y lo que ningun arqueo detecta. Cuando
     * no hay como preguntarlo, se lanza.
     *
     * @param reciboId el identificador interno del recibo <b>en {@code caja}</b>, tal como lo
     *     guardo {@code convenio_movimiento.recibo_id} al formalizar. Desde `V7` no hay clave
     *     foranea que garantice que exista (ADR-0026 §3)
     */
    boolean estaAnulado(long reciboId);
}
