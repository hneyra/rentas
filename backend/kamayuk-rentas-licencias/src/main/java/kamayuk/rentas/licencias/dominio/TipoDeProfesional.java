package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Los profesionales que firman el FUE (#48, RF-113): los tres proyectistas por especialidad y el
 * responsable de obra.
 *
 * <p>El issue los enumera como dos secciones distintas —«proyectistas» y «responsable de obra»— y
 * aqui son un solo catalogo con cuatro valores, porque lo que los distingue es exactamente eso: su
 * papel en el expediente. Separarlos en dos tablas repetiria las mismas cuatro columnas.
 */
public enum TipoDeProfesional {
    PROYECTISTA_ARQUITECTURA("Proyectista de arquitectura", true),
    PROYECTISTA_ESTRUCTURAS("Proyectista de estructuras", true),
    PROYECTISTA_INSTALACIONES("Proyectista de instalaciones", true),
    RESPONSABLE_OBRA("Responsable de obra", false);

    private final String etiqueta;
    private final boolean proyectista;

    TipoDeProfesional(String etiqueta, boolean proyectista) {
        this.etiqueta = etiqueta;
        this.proyectista = proyectista;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public boolean esProyectista() {
        return proyectista;
    }

    public static TipoDeProfesional porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
