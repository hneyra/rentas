package kamayuk.rentas.nucleo.dominio.espectaculos;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;

/**
 * El impuesto de espectáculos públicos no deportivos: {@code ingreso declarado × alícuota del tipo}
 * (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, arts. 54 a 59; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5). La alícuota depende del tipo de espectáculo —igual
 * que el arancel de {@code RT001ValorDeTerreno} depende de la vía— y llega ya resuelta por quien
 * invoca.
 *
 * <p><b>Redondea al cierre, en {@link #PUNTO_DE_REDONDEO}</b> (#378), por el mismo motivo que
 * {@code ImpuestoVehicular} e {@code ImpuestoDeAlcabala}: ADR-0018 de {@code normativa} cerro D-03.
 * Hasta #378 devolvia el producto crudo: con 12 345,67 al 10 % el 201 y la auditoria decian {@code
 * 1234.567} y {@code determinacion.monto_determinado} guardaba {@code 1234.57}, porque la columna
 * {@code dinero} coacciona al guardar. Y el modo lo decide el conjunto sellado, no la columna:
 * sobre 12 345,65, {@code HALF_UP} da 1 234,57 y {@code HALF_EVEN} 1 234,56.
 */
public final class ImpuestoDeEspectaculo {

    /**
     * Donde cierra esta regla. Quien invoca pide su politica al conjunto sellado con este punto.
     */
    public static final PuntoDeRedondeo PUNTO_DE_REDONDEO = PuntoDeRedondeo.IMPUESTO_ESPECTACULO;

    private ImpuestoDeEspectaculo() {}

    /**
     * El impuesto: {@code ingreso × alícuota}, redondeado con la politica de {@link
     * #PUNTO_DE_REDONDEO}.
     */
    public static Dinero calcular(
            Dinero ingresoDeclarado, Alicuota alicuota, PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(ingresoDeclarado, "El calculo necesita el ingreso declarado");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota del tipo de espectaculo");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (#378)");
        return ingresoDeclarado
                .por(alicuota.valor().divide(BigDecimal.valueOf(100)))
                .redondeadoCon(redondeo);
    }
}
