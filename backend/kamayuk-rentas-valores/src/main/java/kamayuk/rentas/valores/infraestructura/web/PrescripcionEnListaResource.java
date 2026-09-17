package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.aplicacion.ConsultaDePrescripciones;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import kamayuk.rentas.valores.dominio.RelojDelEjercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la relacion de prescripciones declaradas (#674, RF-094).
 *
 * <p>Es la relacion, no la resolucion: no lleva los hechos alegados ni los dos inicios del computo
 * —la explicacion de por que la fecha no es «el inicio mas el plazo»—, que salen enteros del {@code
 * POST} que declara. Lo que lleva es lo que quien audita necesita para identificar la deuda
 * afectada —contribuyente, tributo y <b>que ejercicios de verdad prescribieron</b>— y para llegar
 * al papel que lo sustenta.
 *
 * <p><b>{@code ejercicios} es el reloj, y entra con #230.</b> Una fila por ejercicio del rango, con
 * el dia en que prescribe y si ya habia prescrito. La pantalla {@code val-tip} dibuja una tabla
 * titulada «Reloj de prescripcion» y su columna «Prescribe el» no salia de ningun campo: {@code
 * plazo} es el plazo <b>aplicable</b> —un texto, «4 ANIOS»—, no una fecha de vencimiento. La fecha
 * ya estaba guardada en {@code prescripcion_ejercicio} desde #39; lo que hacia esta operacion era
 * tirarla. <b>Viaja como dato y no se resta en la pantalla</b>: restar alli daria otro dia en
 * cuanto un plazo cambiara, y la resolucion ya emitida dice lo que dice.
 *
 * <p>{@code ejerciciosPrescritos} se queda, y ahora se <b>deriva</b> de {@code ejercicios}: es el
 * subconjunto con {@code prescrita}. Se publica igualmente porque es la pregunta que quien audita
 * hace —«cual de los seis anios sigue siendo exigible»— y hacersela recorrer la lista filtrando por
 * un booleano seria darle el trabajo del servidor.
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
        List<Reloj> ejercicios,
        String usuario,
        String observacion) {

    /**
     * Cuando prescribe un ejercicio de esta declaracion.
     *
     * @param ejercicio de que ejercicio es
     * @param prescribeEl el dia en que el plazo vence, ya con las interrupciones y suspensiones
     *     aplicadas
     * @param prescrita si a {@code fechaDePresentacion} ya habia vencido. <b>No es «a hoy»</b>: es
     *     lo que la resolucion resolvio, con el plazo del conjunto sellado de entonces (regla 9)
     */
    public record Reloj(int ejercicio, String prescribeEl, boolean prescrita) {

        static Reloj de(RelojDelEjercicio reloj) {
            return new Reloj(
                    reloj.ejercicio().valor(),
                    reloj.fechaDePrescripcion().toString(),
                    reloj.prescrita());
        }
    }

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
                prescripcion.ejercicios().stream().map(Reloj::de).toList(),
                prescripcion.usuarioRegistro(),
                prescripcion.observacion());
    }
}
