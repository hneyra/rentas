package kamayuk.rentas.fiscalizacion.dominio;

/**
 * Lo que el fiscalizador encontró en campo, contrastado contra lo declarado.
 *
 * <h2>Cinco, los mismos nombres que {@link CondicionFiscalizada}, y no el mismo concepto</h2>
 *
 * <p>Los dos vocabularios coinciden desde #599 y siguen siendo dos cosas: esto es lo que una
 * <b>persona anota</b> en el acta, y {@link CondicionFiscalizada} es lo que el sistema
 * <b>deriva</b> comparando los dos lados ({@link ComparacionHalladoDeclarado}). Uno puede
 * equivocarse y el otro no: el acta puede decir {@code CONFORME} sobre un predio cuya área hallada
 * supera la declarada, y la liquidación lo clasificará {@code SUBVALUADOR} igual, porque la
 * condición sale de las superficies y no de la casilla. Que coincidan letra por letra es lo que
 * permite leer un acta sin traducir; unificarlos borraría esa diferencia, y {@code
 * ParametrosDeLaConsultaTest} lo impide.
 *
 * <p><b>{@code USO_DISTINTO} llegó cuando el acta tuvo dónde sustentarlo</b>, que es lo que #599
 * construyó: {@code acta_fiscalizacion.uso_hallado} (V76). Hasta entonces el acta guardaba el área
 * y ninguna columna de uso, el uso observado lo tecleaba quien liquidaba —argumento de {@code
 * LiquidarFiscalizacion.liquidar}— y #546 se negó a añadir el valor por eso mismo: un acta que
 * anota un hallazgo que no puede sustentar es peor que una que no lo ofrece. Hoy el acta lo anota,
 * la liquidación lo <b>lee de ella</b>, y {@link ActaFiscalizacion} exige el uso observado en toda
 * acta que declare este valor —reforzado en la base por {@code acta_fisc_uso_distinto_ck}—.
 *
 * <p><b>Un acta vehicular no puede anotarlo</b>, y no hace falta escribirlo aparte: un vehículo no
 * tiene uso declarado contra el que contrastar, así que el acta vehicular no consigna uso hallado,
 * y sin uso hallado este valor no se puede declarar.
 *
 * <h2>El desplegable del manual ofrece seis, y ahora uno de ellos sí es uno de estos (#546)</h2>
 *
 * <p>«Hallazgo principal» dibuja SIN OBSERVACIONES, AMPLIACIÓN NO DECLARADA, USO DISTINTO AL
 * DECLARADO, OMISO A LA DECLARACIÓN, PREDIO SUBVALUADO y PREDIO INEXISTENTE. <b>Ninguno se
 * traduce</b>, que es el criterio de #427 al negarse a leer «ACTIVA» como {@code VIGENTE} y el de
 * #431 al no mapear los cuatro de este mismo desplegable. Y aquí pesa más que en un filtro: {@code
 * hallazgo} es <b>opcional</b> en el cuerpo del acta, así que una palabra que el enumerado no
 * reconoce no deja una lista vacía sino un acta registrada <b>sin hallazgo</b> —una inspección sin
 * conclusión, que {@code RegistrarActaFiscalizacion} ya rechaza desde #481—.
 *
 * <p>La decisión, valor a valor, y de qué lado se arregla cada uno:
 *
 * <ul>
 *   <li><b>SIN OBSERVACIONES → el desplegable lo pierde.</b> Es {@link #CONFORME} con otras
 *       palabras; el enumerado no gana nada.
 *   <li><b>AMPLIACIÓN NO DECLARADA → el desplegable lo pierde.</b> Una ampliación no declarada es
 *       declarar de menos, o sea {@link #SUBVALUADOR}. Es una <i>causa</i> del hallazgo, y la causa
 *       va en {@code detalle}, que es texto libre y está para eso.
 *   <li><b>OMISO A LA DECLARACIÓN → el desplegable lo pierde.</b> Es {@link #OMISO}.
 *   <li><b>PREDIO SUBVALUADO → el desplegable lo pierde.</b> Es {@link #SUBVALUADOR}.
 *   <li><b>PREDIO INEXISTENTE → el desplegable lo pierde.</b> Cae en {@link #NO_UBICADO}, que es
 *       «no se pudo verificar». Y conviene que caiga ahí y no en un valor propio: afirmar que un
 *       predio <i>no existe</i> desde una visita es una conclusión que el acta no puede sostener
 *       —lo que consta es que no se ubicó—, y darle valor propio invitaría a darlo de baja del
 *       padrón con eso como único sustento.
 *   <li><b>USO DISTINTO AL DECLARADO → {@link #USO_DISTINTO}, desde #599.</b> Es el único de los
 *       seis que nombraba algo que el enumerado no sabía decir, y su arreglo no era de vocabulario
 *       sino de esquema: hizo falta la columna donde consignar el uso observado. El desplegable
 *       sigue sin mandar su rótulo —el enumerado publica {@code USO_DISTINTO}, letra por letra,
 *       igual que los otros cuatro—.
 * </ul>
 *
 * <p>El contrato publica estos cinco valores como {@code enum} del parámetro {@code hallazgo} de
 * {@code POST /fiscalizacion/vehicular} (tabla {@code VOCABULARIOS} del generador), y {@code
 * ParametrosDeLaConsultaTest} compara ese texto contra {@link #values()}: el vocabulario deja de
 * poder divergir en silencio.
 */
public enum Hallazgo {

    /** Lo declarado coincide con lo hallado. «SIN OBSERVACIONES» del desplegable. */
    CONFORME,

    /** No hay declaración presentada para el ejercicio. «OMISO A LA DECLARACIÓN». */
    OMISO,

    /** Declaró de menos. «PREDIO SUBVALUADO» y «AMPLIACIÓN NO DECLARADA». */
    SUBVALUADOR,

    /**
     * El uso real no es el declarado. «USO DISTINTO AL DECLARADO».
     *
     * <p>Sólo en un acta predial, y sólo acompañado del uso observado: sin él, el acta afirmaría un
     * hallazgo que no puede sustentar (V76, #599).
     */
    USO_DISTINTO,

    /** No se pudo verificar: no se ubicó o no se permitió el acceso. «PREDIO INEXISTENTE». */
    NO_UBICADO
}
