package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da de alta una via del catalogo vial, la edita o la da de baja.
 *
 * <p>Es el primer caso de uso de escritura del sistema, y esta escrito para servir de plantilla.
 * Tres cosas que conviene copiar tal cual:
 *
 * <ol>
 *   <li><b>La {@link Observacion} esta en la firma.</b> No es un campo opcional del cuerpo de la
 *       peticion: es un argumento sin el cual el metodo no se puede llamar. La regla de ArchUnit
 *       {@code TODO_CASO_DE_USO_DE_ESCRITURA_EXIGE_OBSERVACION} lo verifica sobre todos los
 *       {@code @Transactional} de escritura, para que el dia que alguien escriba el caso de uso
 *       numero cuarenta no dependa de que se acuerde.
 *   <li><b>La auditoria va en la misma transaccion</b>, no en otra ni despues. Si el alta se
 *       deshace, su auditoria se deshace con ella; si la auditoria no se puede escribir, el alta se
 *       deshace entera.
 *   <li><b>El reloj se inyecta.</b> La fecha decide en que particion cae la auditoria, y una prueba
 *       tiene que poder fijarla.
 * </ol>
 *
 * <h2>Dos metodos y no uno, porque son tres operaciones de auditoria</h2>
 *
 * <p>{@link #registrar} es el alta y solo el alta: asienta {@link Operacion#ALTA}. {@link #editar}
 * recibe <b>el estado anterior</b> ademas del nuevo, y de la comparacion salen las otras dos: si la
 * via estaba activa y deja de estarlo, es una {@link Operacion#BAJA} —«una via retirada del
 * catalogo», dice el enum—; en cualquier otro caso, una {@link Operacion#MODIFICACION}.
 *
 * <p>El estado anterior no es un adorno. El contrato de {@code MODIFICACION} es explicito —«los
 * datos anteriores quedan en la propia auditoria»— y sin el la fila registra que algo cambio pero
 * no desde que: una auditoria que no permite reconstruir el valor previo no sirve para deshacer un
 * error ni para sostener una reclamacion. Por eso entra en la firma en lugar de releerse aqui: el
 * que llama ya lo tiene, y volver a leerlo abriria la ventana entre la lectura y la escritura.
 *
 * <p>Ningun argumento es el identificador de municipalidad (regla 2): sale del token y lo aplica la
 * politica RLS.
 */
@Service
public class RegistrarVia {

    private final ViaRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarVia(ViaRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Alta de una via que todavia no esta en la base.
     *
     * <p>Rechaza una via que ya tiene identificador en lugar de tratarla como edicion: una via con
     * id llega de la base, y guardarla por aqui asentaria un {@code ALTA} sobre algo que ya existia
     * y perderia el estado anterior. Para eso esta {@link #editar}.
     */
    @Transactional
    public Via registrar(Via via, Observacion observacion) {
        if (!via.esNueva()) {
            throw new IllegalArgumentException(
                    "registrar da de alta una via nueva; la que llego ya tiene identificador."
                            + " Para cambiar una via existente esta editar(anterior, cambiada, …)");
        }
        Via guardada = repositorio.save(via);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "via",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));

        return guardada;
    }

    /**
     * Edicion de una via existente, o su baja logica.
     *
     * <p>{@code anterior} es la via tal como esta en la base y {@code cambiada} la que se quiere
     * dejar; las dos han de ser la misma fila. La operacion que se asienta la decide el paso de
     * {@code activa} de {@code true} a {@code false}: eso es una {@link Operacion#BAJA}, y no un
     * borrado (RNF-051) —la fila sigue ahi con {@code activa = false}—. Reactivar una via dada de
     * baja es una {@link Operacion#MODIFICACION} como cualquier otra.
     */
    @Transactional
    public Via editar(Via anterior, Via cambiada, Observacion observacion) {
        Long idAnterior = anterior.id();
        Long idCambiada = cambiada.id();
        if (idAnterior == null || idCambiada == null) {
            throw new IllegalArgumentException(
                    "editar cambia una via ya guardada; alguna de las dos no tiene identificador");
        }
        if (!idAnterior.equals(idCambiada)) {
            throw new IllegalArgumentException(
                    "El antes y el despues han de ser la misma via; llegaron la "
                            + idAnterior
                            + " y la "
                            + idCambiada);
        }

        Via guardada = repositorio.save(cambiada);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "via",
                                String.valueOf(guardada.id()),
                                esBaja(anterior, guardada)
                                        ? Operacion.BAJA
                                        : Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(anterior), descripcion(guardada)));

        return guardada;
    }

    /** Retirar del catalogo: estaba activa y deja de estarlo. */
    private static boolean esBaja(Via anterior, Via despues) {
        return anterior.activa() && !despues.activa();
    }

    /**
     * Un JSON escrito a mano y no un serializador: son cinco campos, y traer Jackson hasta aqui
     * ataria la capa de aplicacion a la de presentacion. Cuando haya mas de dos casos de uso que lo
     * necesiten, saldra a un componente propio.
     */
    private static String descripcion(Via via) {
        return "{\"codigo\":\""
                + via.codigo()
                + "\",\"tipo\":\""
                + via.tipo()
                + "\",\"nombre\":\""
                + via.nombre().replace("\"", "\\\"")
                + "\",\"activa\":"
                + via.activa()
                + "}";
    }
}
