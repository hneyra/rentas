package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * El representante legal del solicitante, seccion propia del FUE (#48, RF-113).
 *
 * <p>V4 lo tenia como un {@code varchar(240)} suelto. Un nombre no acredita representacion: lo que
 * la acredita es la <b>partida registral del poder</b> y su vigencia, y sin ellas el expediente no
 * puede decir si quien firma podia firmar. Por eso los tres van juntos o no va ninguno, y el {@code
 * CHECK} {@code edificacion_representante_ck} de V43 lo impone tambien en la base.
 *
 * @param documento el documento de identidad del representante
 * @param nombre su nombre
 * @param partidaRegistral la partida donde esta inscrito el poder
 * @param vigenciaDelPoder hasta cuando rige el poder; opcional, porque hay poderes sin plazo
 */
public record RepresentanteLegal(
        String documento,
        String nombre,
        String partidaRegistral,
        @Nullable LocalDate vigenciaDelPoder) {

    /**
     * {@code licencia_edificacion.representante_documento varchar(20)} (V1).
     *
     * <p>El ancho de la columna, topado aqui y no en la base (#422): hasta entonces un texto de mas
     * llegaba al {@code INSERT}, el motor lo rechazaba con 22001 y el borde contestaba 500 con
     * incidencia ERROR. La {@code IllegalArgumentException} sale 422 con su mensaje, que nombra el
     * campo y no la tabla. Aqui se topa lo que llega; no se ensancha la base.
     */
    public static final int DOCUMENTO_MAXIMO = 20;

    public RepresentanteLegal {
        Objects.requireNonNull(documento, "El representante se identifica con su documento");
        Objects.requireNonNull(nombre, "El representante tiene nombre");
        Objects.requireNonNull(
                partidaRegistral,
                "Sin partida registral del poder no hay representacion acreditada");

        documento = documento.strip();
        nombre = nombre.strip();
        partidaRegistral = partidaRegistral.strip();

        if (documento.isEmpty() || nombre.isEmpty() || partidaRegistral.isEmpty()) {
            throw new IllegalArgumentException(
                    "El representante legal va entero —documento, nombre y partida registral del"
                            + " poder— o no va: un nombre sin partida no acredita nada, y una"
                            + " partida sin nombre no dice de quien");
        }
        if (documento.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento del representante '"
                            + documento
                            + "' excede los "
                            + DOCUMENTO_MAXIMO
                            + " caracteres");
        }
    }
}
