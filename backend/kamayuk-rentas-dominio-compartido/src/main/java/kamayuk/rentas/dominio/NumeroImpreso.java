package kamayuk.rentas.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * El numero que la municipalidad imprime en un documento —una papeleta, un expediente de descargo,
 * una notificacion, una liquidacion, una resolucion, un valor, un acto coactivo— y con el que
 * despues se lo busca (#515).
 *
 * <p>La regla es la misma para todos: se guarda y se compara <b>recortado y en mayusculas</b>.
 * Quien lo teclea en ventanilla lo escribe como lo lee, y «pap-2026-000123» es la papeleta
 * PAP-2026-000123, no una que no existe. Hasta #515 esa regla estaba copiada a mano en cada
 * adaptador y en cada caso de uso que buscaba por numero —la causa del defecto que #423 midio con
 * el codigo de contribuyente: una copia que un dia diverge—.
 *
 * <p>Es una funcion y no un objeto de valor por cada documento a proposito: la regla es identica y
 * cinco tipos con el mismo cuerpo serian cinco copias. El documento cuya forma deje de ser esta
 * tendra la suya, como la tiene {@link CodigoContribuyente}.
 *
 * <p><b>Y los que #515 dejo fuera, anotados (#629)</b>: el expediente del FUE y el numero de su
 * licencia, el codigo del programa de fiscalizacion, el acta de internamiento y el expediente
 * coactivo —que en {@code porNumero} solo se recortaba: en minusculas no existia—. Y el codigo CIIU
 * del catalogo de giros, que no lo imprime la municipalidad pero se teclea para buscarlo igual. El
 * ejecutor del filtro coactivo no: es un nombre, y se compara contra {@code upper()} en el motor.
 *
 * <p><b>Y dos que no estaban en ninguna de las dos listas (#637)</b>: la liquidacion de costas y la
 * constancia libre, cuyo {@code porNumero} tambien solo recortaba.
 */
public final class NumeroImpreso {

    private NumeroImpreso() {}

    /**
     * La forma con la que se guarda y se compara un numero impreso. <b>No valida</b>: sirve para
     * buscar, y un numero con otra composicion no es una peticion mal formada sino uno que no esta.
     */
    public static String formaDeBusqueda(String texto) {
        Objects.requireNonNull(texto, "No hay numero que buscar");
        return texto.strip().toUpperCase(Locale.ROOT);
    }
}
