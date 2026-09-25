package kamayuk.rentas.auditoria;

import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se va a asentar en la auditoria.
 *
 * <p>La {@link Observacion} es un campo del registro y no un {@code String} opcional: es la
 * diferencia entre una regla que se cumple y una que se recuerda. Ver ADR-0008 y la regla 10.
 *
 * <p><b>No lleva ni fecha ni ejercicio, y es a proposito</b> (#398). El ejercicio es la clave de
 * particion de {@code auditoria}, y lo decide quien pone la fecha: {@link AuditoriaJdbc}, con un
 * solo instante de su reloj para las dos columnas. Hasta #398 este registro traia el ejercicio y
 * cada llamador lo deducia de la fecha que tuviera a mano —la de presentacion de la DJ, la de la
 * transferencia, la de la infraccion—, con la premisa de que asi «una reejecucion produce el mismo
 * resultado». La premisa era falsa: la {@code fecha} de la fila ya salia del reloj, y lo unico que
 * se conseguia era tener dos fuentes para el mismo hecho. Una transferencia de diciembre registrada
 * en enero iba a una particion que no existe, y una DJ de 2026 anulada en 2027 quedaba archivada en
 * 2026 con fecha de 2027, donde la bitacora no la encuentra.
 *
 * <p>Sin el campo, el uso equivocado <b>no se puede escribir</b>, y por eso no hace falta ninguna
 * guarda. La fecha de negocio, donde importa, va dentro de {@code datosNuevos}: es un dato del
 * acto, no la fecha del asiento en la bitacora.
 *
 * @param tabla tabla afectada
 * @param clave clave de la fila afectada, en texto
 * @param operacion que clase de acto es
 * @param observacion por que se hizo, escrito por quien lo hizo
 * @param datosAnteriores estado previo en JSON, si la operacion lo tenia
 * @param datosNuevos estado resultante en JSON, si lo hay
 */
public record RegistroDeAuditoria(
        String tabla,
        String clave,
        Operacion operacion,
        Observacion observacion,
        @Nullable String datosAnteriores,
        @Nullable String datosNuevos) {

    private static final int TABLA_MAXIMO = 60;
    private static final int CLAVE_MAXIMO = 120;

    public RegistroDeAuditoria {
        Objects.requireNonNull(tabla, "Hay que decir sobre que tabla fue");
        Objects.requireNonNull(clave, "Hay que decir sobre que fila fue");
        Objects.requireNonNull(operacion, "Hay que decir que clase de acto fue");
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda (regla 10, ADR-0008, RNF-052)");
        tabla = tabla.strip();
        clave = clave.strip();
        if (tabla.isEmpty() || tabla.length() > TABLA_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de tabla va de 1 a "
                            + TABLA_MAXIMO
                            + " caracteres: '"
                            + tabla
                            + "'");
        }
        if (clave.isEmpty() || clave.length() > CLAVE_MAXIMO) {
            throw new IllegalArgumentException(
                    "La clave va de 1 a " + CLAVE_MAXIMO + " caracteres: '" + clave + "'");
        }
    }

    /**
     * El caso corriente: sin el antes y el despues.
     *
     * <p>El nombre se conserva del tiempo en que recibia la fecha, porque la fecha sigue siendo la
     * del acto: solo que ahora la pone {@link AuditoriaJdbc} y no el llamador (#398).
     */
    public static RegistroDeAuditoria enLaFechaDe(
            String tabla, String clave, Operacion operacion, Observacion observacion) {
        return new RegistroDeAuditoria(tabla, clave, operacion, observacion, null, null);
    }

    /** El mismo registro con el antes y el despues. */
    public RegistroDeAuditoria con(@Nullable String datosAnteriores, @Nullable String datosNuevos) {
        return new RegistroDeAuditoria(
                tabla, clave, operacion, observacion, datosAnteriores, datosNuevos);
    }
}
