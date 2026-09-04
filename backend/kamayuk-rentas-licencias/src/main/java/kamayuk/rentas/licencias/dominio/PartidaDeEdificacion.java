package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Las <b>tres</b> partidas de apreciacion exterior del Cuadro de Valores Unitarios Oficiales de
 * Edificacion, tal como la valorizacion del FUE las declara (#48 AC 2, RF-113).
 *
 * <h2>Eran siete hasta #436, y las cuatro que se fueron no venian de la norma</h2>
 *
 * <p>Venian de los <b>manuales</b>: el formulario de ficha catastral del manual del SGTM y el
 * manual M02 del MEF, que las clasifica bajo «Confirmado por los manuales» — no por la resolucion.
 * El Cuadro vigente (R.M. 277-2025-VIVIENDA, con la metodologia de la R.D.
 * 003-2022-VIVIENDA/VMVU-DGPRVU) tiene <b>tres</b>, y lo dice en su propia nota al pie: «SE OBTIENE
 * SUMANDO LOS VALORES SELECCIONADOS DE CADA UNA DE LAS 3 COLUMNAS DEL CUADRO». Leer los cuatro
 * anexos regionales (#436) confirmo que <b>ninguna region publica las otras cuatro</b>.
 *
 * <p>Lo que si sigue teniendo siete es {@code construccion.categoria_*}, la ficha catastral: ahi
 * son el formulario del manual y describen una edificacion, no le ponen precio. Que se parecieran
 * no las hacia la misma cosa, y {@code V59} deshizo esa confusion.
 *
 * <h2>Por que esta enumeracion existe aqui y no se importa de {@code catastro}</h2>
 *
 * <p>{@code catastro.dominio.Partida} dice lo mismo, pero es modelo interno de otro contexto
 * acotado: importarla cruzaria el limite que Spring Modulith vigila. Lo que las dos mitades
 * comparten no es un tipo de Java sino el <b>vocabulario</b>, y ese vive donde tiene que vivir: en
 * el {@code CHECK} de {@code valor_unitario_edificacion.partida} (V1) y en el de {@code
 * edificacion_estructura.partida} (V43), escritos palabra por palabra iguales.
 *
 * <p><b>Duplicar tres nombres no es duplicar una cifra.</b> La regla 5 prohibe lo segundo, y esta
 * enumeracion no lleva ninguna: cuanto vale cada letra de cada partida esta en la tabla de #17 y
 * solo ahi.
 */
public enum PartidaDeEdificacion {
    MUROS("Muros y columnas"),
    TECHOS("Techos"),
    PUERTAS("Puertas y ventanas");

    private final String etiqueta;

    PartidaDeEdificacion(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static PartidaDeEdificacion porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
