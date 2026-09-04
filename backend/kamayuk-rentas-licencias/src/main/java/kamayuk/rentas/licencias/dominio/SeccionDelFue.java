package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Locale;

/**
 * Las secciones del Formulario Unico de Edificaciones que se completan <b>por partes</b> (#48 AC 1,
 * RF-113).
 *
 * <h2>Que hace esta enumeracion, y que no</h2>
 *
 * <p>Dice <b>cuales</b> son las secciones y <b>cuales de ellas son obligatorias para emitir</b>. No
 * dice cuando estan completas —eso lo sabe cada tabla— ni que requisitos exige cada modalidad —eso
 * es TUPA, ordenanza local, D-02b—.
 *
 * <p>Las secciones de licencia, solicitante y representante legal no estan aqui: viven en la
 * cabecera y se escriben al presentar el FUE, asi que no se pueden «completar despues». Lo que se
 * completa por partes es lo que el administrado trae en visitas sucesivas.
 */
public enum SeccionDelFue {

    /** Datos urbanos: ubicacion, area del terreno, zonificacion y partida registral. */
    TERRENO("Datos del terreno"),

    /** Caracteristicas del proyecto: uso, pisos, areas, estacionamientos y plazo. */
    PROYECTO("Caracteristicas del proyecto"),

    /** La valorizacion por pisos y estructuras. Sin importes: solo partida, categoria y area. */
    VALORIZACION("Valorizacion por pisos y estructuras"),

    /** Proyectistas por especialidad y responsable de obra. */
    PROFESIONALES("Proyectistas y responsable de obra"),

    /** Documentos adjuntos, con el nombre que el TUPA les da. */
    DOCUMENTOS("Documentos adjuntos");

    private final String etiqueta;

    SeccionDelFue(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /**
     * Las que tienen que estar antes de emitir (AC 1).
     *
     * <p>Son las cinco. Y son las cinco porque cada una responde una pregunta sin la cual la
     * licencia no dice nada: donde se construye, que se construye, cuanto vale lo que se construye,
     * quien responde por ello y con que documentos se sustenta. Una licencia emitida sin
     * profesionales no tiene a quien reclamar cuando la obra falla, y una emitida sin valorizacion
     * no tiene contra que contrastar el derecho de tramite que se cobro.
     */
    public static List<SeccionDelFue> obligatoriasParaEmitir() {
        return List.of(values());
    }

    public static SeccionDelFue porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
