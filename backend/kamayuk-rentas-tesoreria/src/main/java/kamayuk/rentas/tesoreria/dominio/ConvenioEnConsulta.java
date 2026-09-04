package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del listado de convenios (RF-084, pantalla {@code consulta_convenios}).
 *
 * <p>Trae lo que la grilla pinta y nada mas: el convenio entero —con su deuda acogida y su
 * cronograma— se pide por su numero cuando alguien abre uno. Es el mismo criterio con que {@code
 * ValorEnConsulta} existe al lado de {@code Valor}: una pagina de veinte filas no puede costar
 * veinte lecturas de detalle.
 *
 * <p><b>Cada cifra con su fecha</b> (regla 9, RNF-075). {@link #fechaCorte} dice a que fecha esta
 * {@link #deudaAcogida}, y {@link #saldoA} a que fecha esta {@link #saldo}. No son la misma: la
 * deuda acogida se congelo el dia del convenio y el saldo se responde hoy.
 *
 * @param numero el numero impreso
 * @param contribuyenteId el titular
 * @param codigoContribuyente su codigo, para la columna «Contribuyente»
 * @param fecha el dia en que se registro
 * @param fechaCorte a que fecha esta la deuda acogida
 * @param deudaAcogida lo que se fracciono, congelado
 * @param cuotas cuantas cuotas tiene el cronograma, sin contar la inicial
 * @param pagadas cuantas se han cobrado; hoy solo puede ser la inicial (ver {@code
 *     ConsultaDeConvenios})
 * @param vencidas cuantas han vencido sin cobrarse a la fecha de la consulta
 * @param saldo lo que queda por cobrar del cronograma
 * @param saldoA la fecha a la que se respondio {@code saldo}
 * @param estado en que situacion esta, derivado de sus movimientos
 * @param motivoDelCierre por que se cerro, si esta cerrado
 */
public record ConvenioEnConsulta(
        NumeroDeConvenio numero,
        long contribuyenteId,
        String codigoContribuyente,
        LocalDate fecha,
        LocalDate fechaCorte,
        Dinero deudaAcogida,
        int cuotas,
        int pagadas,
        int vencidas,
        Dinero saldo,
        LocalDate saldoA,
        EstadoDeConvenio estado,
        @Nullable String motivoDelCierre) {

    public ConvenioEnConsulta {
        Objects.requireNonNull(numero, "La fila necesita su numero de convenio");
        Objects.requireNonNull(codigoContribuyente, "La fila necesita su contribuyente");
        Objects.requireNonNull(fecha, "La fila necesita la fecha del convenio");
        Objects.requireNonNull(
                fechaCorte, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
        Objects.requireNonNull(deudaAcogida, "La fila necesita lo acogido");
        Objects.requireNonNull(saldo, "La fila necesita su saldo");
        Objects.requireNonNull(saldoA, "El saldo indica a que fecha se respondio (regla 9)");
        Objects.requireNonNull(estado, "La fila necesita su estado");
        if (cuotas < 0 || pagadas < 0 || vencidas < 0) {
            throw new IllegalArgumentException(
                    "Las cuentas de cuotas no son negativas: "
                            + cuotas
                            + "/"
                            + pagadas
                            + "/"
                            + vencidas);
        }
    }
}
