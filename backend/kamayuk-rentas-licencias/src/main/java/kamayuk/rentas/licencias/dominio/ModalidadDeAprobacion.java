package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * La modalidad de aprobacion del FUE (#48, RF-113): la que decide si basta la verificacion
 * administrativa o hace falta una comision tecnica.
 *
 * <p>Las cuatro letras son las de la pantalla {@code fue_edificacion} y las del {@code CHECK} de
 * {@code licencia_edificacion.modalidad} (V4). <b>Que obra cae en cada una</b> —cuantos metros,
 * cuantos pisos, que uso— lo fija la Ley 29090 y su reglamento con cifras, y esas cifras no se
 * escriben aqui: la modalidad la declara el administrado en el FUE y la verifica el evaluador, que
 * es como el manual describe la pantalla.
 */
public enum ModalidadDeAprobacion {

    /** Aprobacion automatica con firma de profesionales. */
    A("Aprobacion automatica", false),

    /** Aprobacion con evaluacion previa por la municipalidad. */
    B("Aprobacion con evaluacion previa", false),

    /** Evaluacion previa por comision tecnica. */
    C("Comision tecnica", true),

    /** Evaluacion previa por comision tecnica. */
    D("Comision tecnica", true);

    private final String etiqueta;
    private final boolean exigeComision;

    ModalidadDeAprobacion(String etiqueta, boolean exigeComision) {
        this.etiqueta = etiqueta;
        this.exigeComision = exigeComision;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** Si la modalidad se resuelve en comision tecnica y no en ventanilla. */
    public boolean exigeComisionTecnica() {
        return exigeComision;
    }

    public static ModalidadDeAprobacion porNombre(String nombre) {
        String texto = nombre.strip().toUpperCase(Locale.ROOT);
        // La pantalla ofrece «A — APROBACION AUTOMATICA»: lo que identifica la
        // modalidad es la letra, y el resto es la etiqueta que la explica.
        return valueOf(texto.substring(0, 1));
    }
}
