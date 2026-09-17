package kamayuk.rentas.sanciones.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una línea de un resumen de papeletas: cuántas hay de ese grupo y por cuánto (#53, RF-073).
 *
 * <h2>Aquí no hay ninguna cifra de recaudación, y es deliberado</h2>
 *
 * <p>Todos los importes de esta línea son <b>los del acta</b> —{@code papeleta.importe_a_pagar},
 * congelado al registrar la papeleta—, agrupados por el estado en que está cada una. {@link
 * #importeDeLasPagadas} es «cuánto sumaban las actas de las papeletas que constan como pagadas», y
 * <b>no</b> «cuánto se cobró»: no cuenta los intereses cobrados, cuenta entero un pago parcial y
 * sigue contando un recibo anulado.
 *
 * <p>Lo recaudado sale del libro, por {@code cuentacorriente.RecaudacionDelLibro}, y es la suma
 * exacta de los abonos vivos (AC 3 de #53). Sumar estas columnas para escribir «recaudado» daría
 * una cifra <b>parecida y distinta</b>, que es la peor clase de cifra: la que nadie comprueba
 * porque se parece a la buena. Los nombres de este record existen para que esa confusión no se
 * pueda escribir sin darse cuenta.
 *
 * <h2>{@code conResolucionNotificada} se llama así porque es lo único que consta (#222)</h2>
 *
 * <p>El panel de tránsito dibuja un recuento rotulado «Notificadas», y ese no se puede publicar:
 * {@code EstadoDePapeleta} declara {@code NOTIFICADA} y <b>ningún código de producción lo
 * escribe</b> — medido sobre {@code src/main}, el único {@code UPDATE papeleta} que existe es
 * {@code SET numero = :numeroNuevo}, y el estado se escribe una sola vez, en el {@code INSERT}, y
 * siempre {@code IMPUESTA}. Contar {@code estado = 'NOTIFICADA'} daría <b>cero para siempre</b>
 * bajo un rótulo que dice cuántas se notificaron: la cifra plausible y equivocada.
 *
 * <p>Lo que el sistema <b>sí</b> registra de la notificación es la diligencia de la <b>resolución
 * de gerencia</b> de esa papeleta —una fila de {@code notificacion} con {@code objeto =
 * 'RESOLUCION'}, #50—, y eso es exactamente lo que esta columna cuenta. No es lo mismo que el
 * rótulo del artboard prometía, y por eso el rótulo cambia en el artboard y no la cifra aquí: una
 * papeleta puede estar notificada en la calle sin que conste, y esta columna no la cuenta.
 *
 * <p><b>Sin importe al lado, y no por descuido.</b> Las otras tres cuentas llevan su importe porque
 * alguna pantalla pregunta cuánto suman; de esta no lo pregunta ninguna, y publicar una cifra de
 * dinero que nadie consume es lo que #184 encontró en las cuatro {@code resumen-*}.
 *
 * <h2>{@code conResolucionDeMulta} nace por el mismo motivo, y alcanza a {@code enCoactiva} (#243)
 * </h2>
 *
 * <p>La misma medida de #222 dice más de lo que aquel issue miró: de los <b>siete</b> valores de
 * {@link EstadoDePapeleta} la producción escribe <b>uno</b>. Así que {@link #pagadas} —{@code
 * FILTER (WHERE p.estado = 'PAGADA')}— y {@link #enCoactiva} —{@code FILTER (WHERE p.estado =
 * 'COACTIVA')}— valen <b>cero para siempre</b> en una instalación nueva, igual que valdría {@code
 * NOTIFICADA}.
 *
 * <p><b>Los dos campos se quedan, y el panel deja de dibujarlos.</b> No es una contradicción: el
 * nombre de este campo dice <b>lo que cuenta</b> —«cuántas constan {@code PAGADA}», que es
 * literalmente cierto—, y el rótulo de una pantalla dice <b>un hecho</b> —«cuántas se pagaron»—,
 * que este sistema no sabe. En una instalación con datos migrados esas columnas pueden traer
 * valores que otro sistema escribió, y esta lectura tiene que poder contarlos; lo que no puede es
 * dibujarlos en un panel junto a cifras que este sistema sí produce, porque entonces la pantalla
 * mezcla dos procedencias sin decirlo.
 *
 * <p>Lo que <b>sí</b> consta de esa etapa, y este contexto lo posee entero, es que a la papeleta ya
 * se le <b>emitió su resolución de multa</b>: la fila {@code GENERADO} de {@code
 * papeleta_masivo_item} con su {@code valor_id}. Eso es {@code conResolucionDeMulta}, y es el mismo
 * predicado con que {@code transito_padron_coactiva} define «las papeletas enviadas a cobranza».
 * <b>Sin importe</b>, por lo mismo que la de arriba.
 *
 * <p>Lo que <b>no</b> se hace es derivar «cancelada» de nada. Lo cobrado vive en el libro y el
 * libro no tiene por dónde cruzar a una papeleta —{@code cuenta_corriente_asiento} no lleva ni
 * {@code papeleta_id} ni {@code valor_id}—, y {@code valor.estado = 'PAGADO'} tampoco lo escribe
 * nadie: medido, {@code 'PAGADO'} sólo aparece en {@code src/main} como filtro de lectura.
 * Escribirlo en la papeleta sería además una <b>segunda verdad</b> sobre el mismo hecho, que es lo
 * que #214 se negó a introducir.
 *
 * @param clave el valor por el que se agrupó: el estado, el código, las dos letras, el mes o el año
 * @param descripcion su descripción, cuando el agrupador la tiene —el código la trae—; nula si no
 * @param ano el año de la línea, cuando el agrupador lo determina —{@code ANO} y {@code MES}—; nulo
 *     si no. Agrupar por estado, por código o por iniciales mezcla años dentro de un grupo, y
 *     publicar ahí «el año» sería elegir uno: una cifra plausible y falsa (#398)
 * @param cantidad cuántas papeletas hay en el grupo
 * @param importe la suma de sus importes de acta
 * @param pagadas cuántas constan pagadas
 * @param importeDeLasPagadas la suma de <b>sus actas</b>, no lo cobrado
 * @param pendientes cuántas siguen debiéndose: ni pagadas, ni anuladas, ni prescritas
 * @param importeDeLasPendientes la suma de sus actas
 * @param enCoactiva cuántas de las pendientes están en cobranza coactiva
 * @param importeEnCoactiva la suma de sus actas
 * @param conResolucionNotificada cuántas tienen ya una resolución de gerencia con al menos una
 *     diligencia que surtió efecto. <b>No es «cuántas se notificaron»</b> y el nombre lo dice: lo
 *     notificado es la <b>resolución</b>, que es otro documento. Ver el javadoc de la clase
 * @param conResolucionDeMulta cuántas tienen ya emitida su resolución de multa, o sea su fila
 *     {@code GENERADO} en {@code papeleta_masivo_item} con el valor puesto. <b>No es «cuántas están
 *     en cobranza coactiva»</b>: el expediente coactivo cuelga del valor y vive en otro contexto.
 *     Ver el javadoc de la clase
 */
public record LineaDelResumen(
        String clave,
        @Nullable String descripcion,
        @Nullable Integer ano,
        long cantidad,
        Dinero importe,
        long pagadas,
        Dinero importeDeLasPagadas,
        long pendientes,
        Dinero importeDeLasPendientes,
        long enCoactiva,
        Dinero importeEnCoactiva,
        long conResolucionNotificada,
        long conResolucionDeMulta) {

    public LineaDelResumen {
        Objects.requireNonNull(clave, "La linea del resumen necesita su clave");
        Objects.requireNonNull(importe, "La linea del resumen necesita su importe");
        Objects.requireNonNull(importeDeLasPagadas, "La linea necesita el importe de las pagadas");
        Objects.requireNonNull(
                importeDeLasPendientes, "La linea necesita el importe de las pendientes");
        Objects.requireNonNull(importeEnCoactiva, "La linea necesita el importe en coactiva");
        if (conResolucionNotificada > cantidad) {
            throw new IllegalArgumentException(
                    "No puede haber mas papeletas con su resolucion notificada ("
                            + conResolucionNotificada
                            + ") que papeletas en el grupo ("
                            + cantidad
                            + ")");
        }
        if (conResolucionDeMulta > cantidad) {
            throw new IllegalArgumentException(
                    "No puede haber mas papeletas con su resolucion de multa emitida ("
                            + conResolucionDeMulta
                            + ") que papeletas en el grupo ("
                            + cantidad
                            + ")");
        }
        if (cantidad < 0
                || pagadas < 0
                || pendientes < 0
                || enCoactiva < 0
                || conResolucionNotificada < 0
                || conResolucionDeMulta < 0) {
            throw new IllegalArgumentException("Ninguna cuenta de un resumen puede ser negativa");
        }
        if (pagadas + pendientes > cantidad) {
            throw new IllegalArgumentException(
                    "Las pagadas y las pendientes no pueden sumar mas que el total del grupo: "
                            + pagadas
                            + " + "
                            + pendientes
                            + " > "
                            + cantidad);
        }
        if (enCoactiva > pendientes) {
            throw new IllegalArgumentException(
                    "Una papeleta en coactiva sigue debiendose: no puede haber mas en coactiva ("
                            + enCoactiva
                            + ") que pendientes ("
                            + pendientes
                            + ")");
        }
    }
}
