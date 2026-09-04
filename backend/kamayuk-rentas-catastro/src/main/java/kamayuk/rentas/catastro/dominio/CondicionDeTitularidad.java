package kamayuk.rentas.catastro.dominio;

/**
 * En que calidad alguien figura como titular de un predio.
 *
 * <p>{@code PROPIETARIO_UNICO} lo es por el total: su porcentaje no se declara, es 100, y la tabla
 * lo comprueba. Los demas admiten una parte.
 */
public enum CondicionDeTitularidad {
    PROPIETARIO_UNICO,
    COPROPIETARIO,
    CONYUGE,
    POSEEDOR,
    SUCESION,
    USUFRUCTUARIO;

    public boolean esPorElTotal() {
        return this == PROPIETARIO_UNICO;
    }
}
