package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Locale;

/**
 * En qué situación queda una unidad fiscalizada después de comparar lo hallado con lo declarado
 * (RF-055).
 *
 * <p>Es el vocabulario de los desplegables «Hallazgo» de {@code fisc_resultados} y «Condición» de
 * {@code fisc_omisos}, y coincide deliberadamente con {@link Hallazgo}, que es lo que el
 * fiscalizador anota en campo: la diferencia es que el hallazgo lo escribe una persona y esto lo
 * <b>calcula</b> {@link ComparacionHalladoDeclarado} a partir de los dos lados.
 *
 * <p><b>{@code EXTEMPORANEO} no está aquí, y es el punto del AC 3 de #49.</b> Presentar la
 * declaración fuera de plazo no convierte a nadie en omiso: son cosas distintas y el manual las
 * distingue. Quien declaró tarde es un declarante —{@link #CONFORME} si lo declarado coincide, o
 * {@link #SUBVALUADOR} si no—, y lo que le corresponde es la multa del art. 176, no la
 * determinación de oficio del omiso. Que el plazo se incumplió viaja aparte, en {@code
 * fueraDePlazo}, porque es otra pregunta.
 */
public enum CondicionFiscalizada {

    /** Lo declarado coincide con lo hallado. */
    CONFORME,

    /** No hay declaración presentada para el ejercicio: nunca declaró. */
    OMISO,

    /** Declaró de menos: el área hallada supera la declarada. */
    SUBVALUADOR,

    /** Declaró, con el área correcta, pero el uso real no es el declarado. */
    USO_DISTINTO,

    /** No se pudo verificar: el predio no se ubicó o no se permitió el acceso. */
    NO_UBICADO;

    public static CondicionFiscalizada porNombre(String texto) {
        String normalizado = texto.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (CondicionFiscalizada condicion : values()) {
            if (condicion.name().equals(normalizado)) {
                return condicion;
            }
        }
        throw new IllegalArgumentException("Condicion fiscalizada desconocida: '" + texto + "'");
    }

    /** Si esta condición justifica emitir una determinación de oficio. */
    public boolean hayDiferencia() {
        return this == OMISO || this == SUBVALUADOR || this == USO_DISTINTO;
    }
}
