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
