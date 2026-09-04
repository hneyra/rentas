package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Quien revisa el proyecto del FUE (#48, RF-113): los revisores urbanos o la comision tecnica.
 *
 * <p>Es opcional en el modelo, y a proposito: en la modalidad A no hay revision, y darle un valor
 * por omision decidiria por descuido si el expediente pasa por comision tecnica —que es lo que
 * decide si la licencia se otorga en ventanilla o en sesion—.
 */
public enum RevisionDelProyecto {
    REVISORES_URBANOS("Revisores urbanos"),
    COMISION_TECNICA("Comision tecnica");

    private final String etiqueta;

    RevisionDelProyecto(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static RevisionDelProyecto porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
