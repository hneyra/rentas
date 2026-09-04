package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una linea de la liquidacion de costas: <b>un acto, un arancel</b> (V35, #42, RF-104).
 *
 * <h2>La costa se devenga por acto, y por eso el acto esta aqui</h2>
 *
 * <p>Lo que el arancel de costas tarifa no es «el expediente» sino cada actuacion del
 * procedimiento: la resolucion que lo inicia, la que ordena la medida cautelar, el acta de embargo,
 * la tasacion. {@link #actoId} es esa actuacion, y {@code costa_acto_uq} (V35) impide que se
 * liquide dos veces: no un {@code if}, porque dos peticiones simultaneas pasan las dos por
 * cualquier comprobacion escrita en Java y el obligado acabaria pagando dos veces la costa de la
 * misma REC.
 *
 * <h2>El arancel se copia con su procedencia</h2>
 *
 * <p>{@link #arancelFuente} y {@link #arancelConjuntoId} dicen de donde salio la cifra: la llave
 * del parametro sellado y el conjunto concreto que la contenia. No es adorno: es lo que permite
 * explicar dentro de diez anios por que esta costa vale lo que vale, sin volver a resolver «el
 * arancel vigente» —que para entonces sera otro— (ARQ-09 §3, regla 5).
 *
 * <p><b>Ninguna cifra normativa se construye aqui.</b> {@link #monto} llega ya leido del conjunto
 * sellado; este tipo no sabe cuanto vale una REC-1 y no tiene ningun valor por omision. El arancel
 * de costas es de ordenanza local y vive en D-02c (#193).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param liquidacionId la liquidacion a la que pertenece
 * @param expedienteId el expediente cuyo procedimiento la devenga
 * @param actoId el acto tarifado; uno solo, y una sola vez
 * @param actoTipo el tipo del acto, copiado para que la fila se explique sin cruzar tablas
 * @param concepto la glosa que sale impresa
 * @param tributo la obligacion del libro a la que se imputa
 * @param monto lo que el arancel dice para ese acto; siempre positivo
 * @param fecha el dia de la liquidacion
 * @param arancelFuente la llave del parametro y el documento que lo sustenta
 * @param arancelConjuntoId el conjunto sellado del que salio
 */
public record CostaLiquidada(
        @Nullable Long id,
        long liquidacionId,
        long expedienteId,
        long actoId,
        TipoDeActoCoactivo actoTipo,
        String concepto,
        String tributo,
        Dinero monto,
        LocalDate fecha,
        String arancelFuente,
        long arancelConjuntoId) {

    /** {@code costa_procesal.concepto varchar(160)} (V3). */
    public static final int CONCEPTO_MAXIMO = 160;

    /** {@code costa_procesal.arancel_fuente varchar(200)} (V3). */
    public static final int FUENTE_MAXIMA = 200;

    /** {@code costa_procesal.tributo varchar(20)} (V35). */
    public static final int TRIBUTO_MAXIMO = 20;

    public CostaLiquidada {
        if (actoId <= 0) {
            throw new IllegalArgumentException(
                    "Una costa procesal tarifa un acto concreto del procedimiento: sin el no hay"
                            + " nada que liquidar (art. 20 de la Ley 26979)");
        }
        if (expedienteId <= 0) {
            throw new IllegalArgumentException("Una costa procesal es de un expediente concreto");
        }
        Objects.requireNonNull(actoTipo, "La costa dice que acto tarifa");
        Objects.requireNonNull(fecha, "La costa es de un dia concreto (regla 6)");
        Objects.requireNonNull(monto, "La costa necesita su importe");
        if (!monto.esPositivo()) {
            throw new IllegalArgumentException(
                    "Una costa de cero no es una costa: o el acto devenga arancel o no se liquida"
                            + " (costa_monto_ck)");
        }
        concepto = exigido(concepto, CONCEPTO_MAXIMO, "La glosa de la costa");
        tributo =
                exigido(tributo, TRIBUTO_MAXIMO, "El tributo de la costa")
                        .toUpperCase(java.util.Locale.ROOT);
        arancelFuente = exigido(arancelFuente, FUENTE_MAXIMA, "La fuente del arancel");
        if (arancelConjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "La costa dice de que conjunto sellado salio su arancel: sin eso, revisarla"
                            + " dentro de dos anios resolveria «el vigente» y podria dar otra"
                            + " cifra (ARQ-09 §3)");
        }
    }

    /** Una linea todavia sin guardar; la liquidacion le pone su identificador al registrarse. */
    public static CostaLiquidada nueva(
            long expedienteId,
            long actoId,
            TipoDeActoCoactivo actoTipo,
            String concepto,
            String tributo,
            Dinero monto,
            LocalDate fecha,
            String arancelFuente,
            long arancelConjuntoId) {
        return new CostaLiquidada(
                null,
                0L,
                expedienteId,
                actoId,
                actoTipo,
                concepto,
                tributo,
                monto,
                fecha,
                arancelFuente,
                arancelConjuntoId);
    }

    /** La misma linea, ya sabiendo de que liquidacion es. */
    public CostaLiquidada deLaLiquidacion(long liquidacion) {
        return new CostaLiquidada(
                id,
                liquidacion,
                expedienteId,
                actoId,
                actoTipo,
                concepto,
                tributo,
                monto,
                fecha,
                arancelFuente,
                arancelConjuntoId);
    }

    private static String exigido(String valor, int maximo, String que) {
        String limpio = Objects.requireNonNull(valor, que + " es obligatoria").strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(que + " es obligatoria");
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
