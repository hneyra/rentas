package kamayuk.rentas.catastro.dominio;

/**
 * En que estado esta la titularidad de un predio, como filtro del padron (#690).
 *
 * <h2>Para que hace falta</h2>
 *
 * <p>En Catacaos, <b>4 977 de 14 422 predios no tienen ningun titular vigente</b> y otros <b>304
 * tienen cuotas que no suman 100 %</b> —hay cincuenta sumas distintas por debajo de cien, y el peor
 * predio esta al 0,349 %—. No es una lectura mal hecha: es lo que hay en {@code titularidad}, y
 * ninguna cifra parece mal en ninguna pantalla porque la determinacion sale correcta <b>para lo
 * registrado</b>.
 *
 * <p>Y eso cuesta dinero: el {@code %} de propiedad <b>pondera la base imponible</b> de cada predio
 * (NEG-05 §1, y {@code DeterminarPredial} lo aplica desde #395). Un predio cuyas cuotas suman 0,349
 * % tributa por el 0,349 % de su valor; uno sin ninguna cuota no tiene a quien cargarselo.
 *
 * <p>Hasta #690 no habia forma de <b>censarlos</b>: la ficha del predio lo dice de uno en uno, y
 * preguntarselo a los 14 422 serian 14 422 peticiones.
 *
 * <h2>Por que son tres valores y no uno</h2>
 *
 * <p>Porque son dos poblaciones con dos remedios distintos —al predio sin ninguna cuota hay que
 * encontrarle dueño; al que suma 78 % hay que averiguar de quien es el 22 % restante— y el panel
 * las cuenta por separado. {@link #COMPLETA} existe para poder medir el contraste: sin ella, una
 * consulta que devolviera el padron entero no se distinguiria de una que acierta.
 *
 * <h2>«Vigente» aqui es «cuota abierta», y es a proposito</h2>
 *
 * <p>Se suman las cuotas con {@code vigencia_hasta IS NULL}, que es <b>exactamente</b> lo que suma
 * {@code titularidad_no_excede_trg} (V1) para comprobar que no se pase de 100. Las dos miradas
 * tienen que coincidir: si el censo sumara «lo vigente a una fecha» y el disparador «lo abierto»,
 * habria predios que el censo llama incompletos y la base considera correctos, y nadie sabria cual
 * de las dos manda.
 */
public enum TitularidadDelPredio {

    /** Ninguna cuota abierta: el predio no tiene a quien cargarle nada. */
    SIN_TITULAR,

    /** Tiene cuotas abiertas y no suman 100: la base se pondera por lo que falta. */
    INCOMPLETA,

    /** Tiene cuotas abiertas y suman exactamente 100. Es el caso correcto. */
    COMPLETA
}
