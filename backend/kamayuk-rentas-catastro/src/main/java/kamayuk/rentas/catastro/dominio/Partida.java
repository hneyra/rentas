package kamayuk.rentas.catastro.dominio;

/**
 * Las <b>tres</b> partidas de apreciacion exterior de la tabla de valores unitarios de edificacion
 * (RF-009): muros y columnas, techos, y puertas y ventanas.
 *
 * <h2>Eran siete, y el comentario que estaba aqui afirmaba algo que la norma desmiente</h2>
 *
 * <p>Decia: «Es el mismo catalogo que ya fija {@code construccion} para sus siete categorias por
 * partida (V1): esta enumeracion tiene que seguir coincidiendo con esa, porque <b>son las dos
 * mitades de la misma matriz</b>». No lo son.
 *
 * <p>Esta enumeracion es el vocabulario del <b>cuadro de la norma</b> —lo lee {@code
 * ValuacionRepositoryJdbc} de {@code valor_unitario_edificacion}, que guarda soles por metro
 * cuadrado—, y el Cuadro vigente publica <b>tres</b> partidas: la R.M. 277-2025-VIVIENDA lo dice en
 * su nota al pie —«SUMANDO LOS VALORES SELECCIONADOS DE CADA UNA DE LAS 3 COLUMNAS»— y sus
 * considerandos citan la R.D. 003-2022-VIVIENDA/VMVU-DGPRVU, que aprobo la metodologia de tres
 * partidas de apreciacion exterior. Leer los cuatro anexos regionales (#436) confirmo que
 * <b>ninguna region publica las otras cuatro</b>.
 *
 * <p>{@code construccion.categoria_*} sigue teniendo siete, y esta bien: es el <b>formulario de la
 * ficha catastral del manual</b> —{@code V1} lo cita: «manual, cap. 2 §Caract. Construccion»— y
 * describe una edificacion en vez de ponerle precio. Un catastro puede registrar mas
 * caracteristicas de las que la valorizacion usa; lo que no puede es ponerle precio a una partida
 * que la norma no publica. La distincion la fija {@code V59}.
 */
public enum Partida {
    MUROS,
    TECHOS,
    PUERTAS
}
