package kamayuk.rentas.nucleo.dominio.espectaculos;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;

/**
 * El impuesto de espectáculos públicos no deportivos: {@code ingreso declarado × alícuota del tipo}
 * (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, arts. 54 a 59; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5). La alícuota depende del tipo de espectáculo —igual
 * que el arancel de {@code RT001ValorDeTerreno} depende de la vía— y llega ya resuelta por quien
 * invoca.
 *
 * <p><b>No redondea</b>, por el mismo motivo que {@code ImpuestoVehicular} e {@code
 * ImpuestoDeAlcabala}: D-03c no ha identificado todavía un punto de redondeo para este tributo.
 */
public final class ImpuestoDeEspectaculo {

    private ImpuestoDeEspectaculo() {}

    public static Dinero calcular(Dinero ingresoDeclarado, Alicuota alicuota) {
        Objects.requireNonNull(ingresoDeclarado, "El calculo necesita el ingreso declarado");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota del tipo de espectaculo");
        return ingresoDeclarado.por(alicuota.valor().divide(BigDecimal.valueOf(100)));
    }
}
