package kamayuk.rentas.sanciones.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una línea de un resumen de papeletas: cuántas hay de ese grupo y por cuánto (#53, RF-073).
 *
 * <h2>Aquí no hay ninguna cifra de recaudación, y es deliberado</h2>
 *
 * <p>Todos los importes de esta línea son <b>los del acta</b> —{@code papeleta.importe_a_pagar},
 * congelado al registrar la papeleta—, agrupados por el estado en que está cada una. {@link
 * #importeDeLasPagadas} es «cuánto sumaban las actas de las papeletas que constan como pagadas», y
 * <b>no</b> «cuánto se cobró»: no cuenta los intereses cobrados, cuenta entero un pago parcial y
 * sigue contando un recibo anulado.
 *
 * <p>Lo recaudado sale del libro, por {@code cuentacorriente.RecaudacionDelLibro}, y es la suma
 * exacta de los abonos vivos (AC 3 de #53). Sumar estas columnas para escribir «recaudado» daría
 * una cifra <b>parecida y distinta</b>, que es la peor clase de cifra: la que nadie comprueba
 * porque se parece a la buena. Los nombres de este record existen para que esa confusión no se
 * pueda escribir sin darse cuenta.
 *
 * @param clave el valor por el que se agrupó: el estado, el código, las dos letras, el mes o el año
 * @param descripcion su descripción, cuando el agrupador la tiene —el código la trae—; nula si no
 * @param ano el año de la línea, cuando el agrupador lo determina —{@code ANO} y {@code MES}—; nulo
 *     si no. Agrupar por estado, por código o por iniciales mezcla años dentro de un grupo, y
 *     publicar ahí «el año» sería elegir uno: una cifra plausible y falsa (#398)
 * @param cantidad cuántas papeletas hay en el grupo
 * @param importe la suma de sus importes de acta
 * @param pagadas cuántas constan pagadas
 * @param importeDeLasPagadas la suma de <b>sus actas</b>, no lo cobrado
 * @param pendientes cuántas siguen debiéndose: ni pagadas, ni anuladas, ni prescritas
 * @param importeDeLasPendientes la suma de sus actas
 * @param enCoactiva cuántas de las pendientes están en cobranza coactiva
 * @param importeEnCoactiva la suma de sus actas
 */
public record LineaDelResumen(
        String clave,
        @Nullable String descripcion,
        @Nullable Integer ano,
        long cantidad,
        Dinero importe,
        long pagadas,
        Dinero importeDeLasPagadas,
        long pendientes,
        Dinero importeDeLasPendientes,
        long enCoactiva,
        Dinero importeEnCoactiva) {

    public LineaDelResumen {
        Objects.requireNonNull(clave, "La linea del resumen necesita su clave");
        Objects.requireNonNull(importe, "La linea del resumen necesita su importe");
        Objects.requireNonNull(importeDeLasPagadas, "La linea necesita el importe de las pagadas");
        Objects.requireNonNull(
                importeDeLasPendientes, "La linea necesita el importe de las pendientes");
        Objects.requireNonNull(importeEnCoactiva, "La linea necesita el importe en coactiva");
        if (cantidad < 0 || pagadas < 0 || pendientes < 0 || enCoactiva < 0) {
            throw new IllegalArgumentException("Ninguna cuenta de un resumen puede ser negativa");
        }
        if (pagadas + pendientes > cantidad) {
            throw new IllegalArgumentException(
                    "Las pagadas y las pendientes no pueden sumar mas que el total del grupo: "
                            + pagadas
                            + " + "
                            + pendientes
                            + " > "
                            + cantidad);
        }
        if (enCoactiva > pendientes) {
            throw new IllegalArgumentException(
                    "Una papeleta en coactiva sigue debiendose: no puede haber mas en coactiva ("
                            + enCoactiva
                            + ") que pendientes ("
                            + pendientes
                            + ")");
        }
    }
}
