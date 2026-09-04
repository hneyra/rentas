package kamayuk.rentas.coactiva.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acto del procedimiento de ejecucion coactiva, con el documento que lo materializa (V34, #41,
 * RF-101, RF-102).
 *
 * <h2>Solo se agrega</h2>
 *
 * <p>{@code acto_coactivo} pierde el privilegio de {@code UPDATE} en V34, y esta en {@code
 * TABLAS_INMUTABLES} del escaner de fuentes. Es lo mismo que le pasa al recibo (V30), al convenio
 * (V31) y al historial del expediente (V33), y por el mismo motivo: el acto se <b>notifica</b> al
 * obligado, que se lleva el papel. Corregirlo en la base deja al papel y al sistema diciendo cosas
 * distintas, y quien tenga el papel gana la discusion. Un acto equivocado se deja sin efecto con
 * otro acto —un levantamiento, una suspension—, y los dos quedan.
 *
 * <h2>El numero es el del documento, y no otro</h2>
 *
 * <p>{@link #numero} es {@code documento_emitido.numero}: el mismo que sale impreso. No hay un
 * correlativo propio del acto, porque dos numeraciones para el mismo papel divergen el dia que una
 * de las dos se reinicie.
 *
 * <h2>La REC-2 lleva su sustento dentro</h2>
 *
 * <p>{@link #rec1NotificacionId} y {@link #rec1ExigibleDesde} solo existen en la REC-2, y en ella
 * son obligatorios: son la diligencia que notifico la REC-1 y el dia desde el que —vencidos los
 * siete dias habiles del art. 14.1 de la Ley 26979— la medida cautelar se puede dictar. Se
 * <b>copian</b>, como {@code valor_movimiento} copia los suyos (V28): si se recalcularan al leer,
 * un plazo sellado despues daria otra fecha y la resolucion pareceria haber nacido en otro dia
 * (ARQ-09 §3).
 *
 * @param id nulo mientras no se ha guardado
 * @param expedienteId el expediente sobre el que se actua
 * @param tipo que acto es
 * @param numero el numero del documento emitido, tal como sale impreso
 * @param fecha el dia del acto; entra como argumento, no sale del reloj del dominio (regla 6)
 * @param descripcion la glosa del acto
 * @param medida la forma de la medida cautelar; obligatoria en la REC-2, nula en los demas
 * @param rec1NotificacionId la diligencia que notifico la REC-1; solo en la REC-2
 * @param rec1ExigibleDesde desde cuando se puede dictar la medida; solo en la REC-2
 * @param documentoId la fila de {@code documento_emitido} que lo dibujo
 * @param registradoEn el instante del registro; sale del reloj inyectado
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado, porque lo pone el
 *     repositorio desde el origen de la peticion y no quien construye el objeto
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record ActoCoactivo(
        @Nullable Long id,
        long expedienteId,
        TipoDeActoCoactivo tipo,
        String numero,
        LocalDate fecha,
        String descripcion,
        @Nullable TipoDeMedidaCautelar medida,
        @Nullable Long rec1NotificacionId,
        @Nullable LocalDate rec1ExigibleDesde,
        long documentoId,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code acto_coactivo.numero varchar(40)} tras V34. */
    public static final int NUMERO_MAXIMO = 40;

    /** {@code acto_coactivo.descripcion varchar(500)}. */
    public static final int DESCRIPCION_MAXIMA = 500;

    public ActoCoactivo {
        if (expedienteId <= 0) {
            throw new IllegalArgumentException("Un acto coactivo es de un expediente concreto");
        }
        Objects.requireNonNull(tipo, "El acto necesita su tipo");
        Objects.requireNonNull(fecha, "El acto necesita su fecha");
        Objects.requireNonNull(registradoEn, "El acto dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "Un acto coactivo se materializa en un documento emitido: sin el no hay nada"
                            + " que notificar ni que reimprimir (RF-132)");
        }

        numero = exigido(numero, NUMERO_MAXIMO, "El numero del acto");
        descripcion = exigido(descripcion, DESCRIPCION_MAXIMA, "La descripcion del acto");

        // Las mismas condiciones que acto_medida_ck y acto_rec2_sustento_ck (V34), aqui para que
        // fallen al construir el objeto y no al llegar a la base.
        if (tipo.llevaMedida() != (medida != null)) {
            throw new IllegalArgumentException(
                    tipo.llevaMedida()
                            ? "La REC-2 declara en que forma se traba la medida cautelar (art. 33"
                                    + " de la Ley 26979): sin ella queda una medida sin decir"
                                    + " sobre que"
                            : "Solo la REC-2 ordena la medida cautelar: un acto de tipo "
                                    + tipo
                                    + " con medida pegada seria una medida dictada sin resolucion"
                                    + " que la disponga");
        }
        boolean conSustento = rec1NotificacionId != null && rec1ExigibleDesde != null;
        if (tipo.exigeRec1Vencida() != conSustento) {
            throw new IllegalArgumentException(
                    tipo.exigeRec1Vencida()
                            ? "La REC-2 lleva dentro su sustento: que diligencia notifico la REC-1"
                                    + " y desde cuando, vencido el plazo, se puede dictar la"
                                    + " medida"
                            : "Solo la REC-2 se sustenta en el plazo de la REC-1");
        }
        if (rec1ExigibleDesde != null && fecha.isBefore(rec1ExigibleDesde)) {
            throw new IllegalArgumentException(
                    "La REC-2 no puede ser anterior al "
                            + rec1ExigibleDesde
                            + ", que es cuando vence el plazo que la REC-1 concedio: dictada"
                            + " antes, la medida cautelar es nula");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** Un acto sin guardar que no es la REC-2: sin medida y sin sustento. */
    public static ActoCoactivo nuevo(
            long expedienteId,
            TipoDeActoCoactivo tipo,
            String numero,
            LocalDate fecha,
            String descripcion,
            long documentoId,
            Instant registradoEn,
            Observacion observacion) {
        return new ActoCoactivo(
                null,
                expedienteId,
                tipo,
                numero,
                fecha,
                descripcion,
                null,
                null,
                null,
                documentoId,
                registradoEn,
                null,
                observacion);
    }

    /** La REC-2 sin guardar, con la forma de la medida y el sustento de la REC-1. */
    public static ActoCoactivo rec2(
            long expedienteId,
            String numero,
            LocalDate fecha,
            String descripcion,
            TipoDeMedidaCautelar medida,
            long rec1NotificacionId,
            LocalDate rec1ExigibleDesde,
            long documentoId,
            Instant registradoEn,
            Observacion observacion) {
        return new ActoCoactivo(
                null,
                expedienteId,
                TipoDeActoCoactivo.REC2,
                numero,
                fecha,
                descripcion,
                medida,
                rec1NotificacionId,
                rec1ExigibleDesde,
                documentoId,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "El acto todavia no se ha guardado");
    }

    private static String exigido(String valor, int maximo, String que) {
        String limpio = Objects.requireNonNull(valor, que + " es obligatorio").strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(que + " es obligatorio");
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
