package kamayuk.rentas.autorizacion;

/**
 * Los <b>siete</b> privilegios del manual (cap. 4, RF-121).
 *
 * <p>Se conservan tal cual, con sus nombres, en lugar de reducirlos a los tres o cuatro habituales
 * de un CRUD. No es nostalgia: son los que las municipalidades ya tienen configurados y los que sus
 * administradores saben leer. «Impresion» separada de «lectura» tiene sentido en un sistema donde
 * imprimir un valor es un acto administrativo con numeracion correlativa; y «especial» es la puerta
 * de las capacidades que no son una pantalla —anular un recibo ajeno, cambiar el ejercicio de
 * trabajo—.
 *
 * <p>Cada uno lleva el nombre de su columna booleana en {@code permiso}, para que agregar un
 * privilegio obligue a decir donde se guarda.
 *
 * <p>Nota: {@code ELIMINACION} existe como privilegio porque el manual lo tiene, y gobierna la
 * <b>baja</b> —desactivar—, no un {@code DELETE}: la aplicacion no borra nada (RNF-051) y el rol
 * {@code sgtm_app} no tiene el privilegio en la base.
 */
public enum Privilegio {
    EJECUCION("ejecucion"),
    LECTURA("lectura"),
    REGISTRO("registro"),
    MODIFICACION("modificacion"),
    ELIMINACION("eliminacion"),
    IMPRESION("impresion"),
    ESPECIAL("especial");

    private final String columna;

    Privilegio(String columna) {
        this.columna = columna;
    }

    public String columna() {
        return columna;
    }
}
