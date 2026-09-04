package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.aplicacion.ConsultaDePrescripciones;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la relacion de prescripciones declaradas (#674, RF-094).
 *
 * <p>Es la relacion, no la resolucion: no lleva el computo de cada ejercicio ni los hechos
 * alegados, que salen enteros del {@code POST} que declara. Lo que lleva es lo que quien audita
 * necesita para identificar la deuda afectada —contribuyente, tributo y <b>que ejercicios de verdad
 * prescribieron</b>— y para llegar al papel que lo sustenta.
 *
 * <p><b>{@code ejerciciosPrescritos} y no un booleano.</b> Una solicitud pide un rango y se
 * resuelve ejercicio por ejercicio: «procede en parte» es el caso corriente, y decir solo que
 * procedio dejaria sin contestar la unica pregunta que importa —cual de los seis anios sigue siendo
 * exigible—.
 *
 * <p>Sin ninguna cifra de dinero, por el mismo motivo que {@link PrescripcionResource}: la
 * prescripcion no extingue un importe, deja sin accion su cobro.
 *
 * @param codContribuyente el codigo del padron, o {@code null} si el padron no resolvio el
 *     identificador; la fila sale igual, que es justo la que hay que revisar
 * @param contribuyente el nombre, con la misma salvedad
 */
public record PrescripcionEnListaResource(
        long id,
        @Nullable String codContribuyente,
        @Nullable String contribuyente,
        String tributo,
        int ejercicioDesde,
        int ejercicioHasta,
        String fechaDePresentacion,
        String plazoAplicable,
        String plazo,
        String resultado,
        @Nullable String nDeResolucion,
        List<Integer> ejerciciosPrescritos,
        String usuario,
        String observacion) {

    public static PrescripcionEnListaResource de(ConsultaDePrescripciones.FilaDePrescripcion fila) {
        PrescripcionEnLista prescripcion = fila.prescripcion();
        return new PrescripcionEnListaResource(
                prescripcion.id(),
                fila.contribuyente() == null ? null : fila.contribuyente().codigo(),
                fila.contribuyente() == null ? null : fila.contribuyente().nombre(),
                prescripcion.tributo(),
                prescripcion.ejercicioDesde().valor(),
                prescripcion.ejercicioHasta().valor(),
                prescripcion.fechaPresentacion().toString(),
                prescripcion.causal().name(),
                prescripcion.plazo().toString(),
                prescripcion.resultado().name(),
                prescripcion.resolucion(),
                prescripcion.ejerciciosPrescritos().stream().map(Ejercicio::valor).toList(),
                prescripcion.usuarioRegistro(),
                prescripcion.observacion());
    }
}
