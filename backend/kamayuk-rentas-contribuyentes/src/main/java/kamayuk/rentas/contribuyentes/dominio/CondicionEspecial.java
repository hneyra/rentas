package kamayuk.rentas.contribuyentes.dominio;

/**
 * La condicion que puede dar derecho a una deduccion.
 *
 * <p><b>Aqui solo se registra; no se aplica nada.</b> Cuanto deduce un pensionista es un valor
 * normativo bloqueado por D-02a, y el reparto entre pensionista y adulto mayor no pensionista tiene
 * cuatro casos borde sin resolver (NEG-05 §RT-012). Guardar la condicion no es calcular con ella.
 */
public enum CondicionEspecial {
    PENSIONISTA,
    ADULTO_MAYOR,
    DISCAPACIDAD
}
