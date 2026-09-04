package kamayuk.rentas.rentas.dominio.beneficios;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * El desglose de una obligacion que entra en la simulacion, con su fecha de corte ya resuelta por
 * quien la trajo.
 *
 * <p>Es la proyeccion de {@code cuentacorriente.ObligacionPublica} a lo que la simulacion necesita:
 * cuatro cifras. No se usa aquel tipo directamente para que esta regla siga siendo una <b>funcion
 * pura</b> del dominio de {@code rentas} (regla 6, regla 7): sin base de datos, sin reloj y sin
 * conocer al contexto del que salieron los importes.
 *
 * @param insoluto el tributo determinado
 * @param reajuste el ajuste de cuotas por el indice
 * @param interes el interes moratorio
 * @param gasto los gastos administrativos y de cobranza
 */
public record DesgloseAcogido(Dinero insoluto, Dinero reajuste, Dinero interes, Dinero gasto) {

    public DesgloseAcogido {
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
    }

    /** La suma de las cuatro partes, nunca una quinta cifra guardada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }

    /** La parte sobre la que la ordenanza dice que se aplica el descuento. */
    public Dinero parte(BaseDelBeneficio base) {
        Objects.requireNonNull(base, "Sin base no hay sobre que aplicar el descuento");
        return switch (base) {
            case TOTAL -> total();
            case INSOLUTO -> insoluto;
            case REAJUSTE_E_INTERES -> reajuste.mas(interes);
        };
    }
}
