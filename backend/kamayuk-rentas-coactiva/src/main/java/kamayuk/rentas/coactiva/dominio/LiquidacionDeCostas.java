package kamayuk.rentas.coactiva.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La liquidacion de costas y gastos de un expediente coactivo (V35, #42, RF-104).
 *
 * <h2>La costa NO es un campo del expediente</h2>
 *
 * <p>Es lo central de #42 y conviene decirlo donde se lea: {@code expediente_coactivo} no tiene —ni
 * va a tener— una columna {@code costas}. Si la tuviera habria dos verdades sobre cuanto se debe,
 * el libro y esa columna, y la que se cobrase en ventanilla seria la del libro. Lo que esta
 * liquidacion hace es <b>asentar un cargo</b> de concepto {@code GASTO} en fase {@code COACTIVA}
 * por el puerto publico de {@code cuentacorriente}; {@code DeudaDelExpediente.costas} se relee de
 * ahi a la fecha que se pida (regla 9, RNF-075).
 *
 * <p>Esta clase guarda el <b>acto administrativo</b> —que actos se liquidaron, con que arancel y de
 * que conjunto sellado salio—, nunca el saldo.
 *
 * <h2>Sin columna de estado</h2>
 *
 * <p>Como el recibo (V30), el convenio (V31), el turno (V32), el expediente (V33) y el acto (V34):
 * la tabla no admite {@code UPDATE}, asi que una columna {@code estado} diria {@code ACTIVA} para
 * siempre. Que una liquidacion este cancelada se <b>deriva</b> del libro —su obligacion ya no debe
 * nada a la fecha—, y lo deriva {@link EstadoDeLaLiquidacion}.
 *
 * <h2>El total es la suma de sus lineas, y aqui se comprueba</h2>
 *
 * <p>La base no puede: un {@code CHECK} no suma filas de otra tabla. Lo comprueba el constructor,
 * que es el unico camino por el que una liquidacion llega al repositorio. Sin eso, una cabecera con
 * un total mayor que sus lineas asentaria en el libro un cargo que nada explica.
 *
 * @param id nulo mientras no se ha guardado
 * @param numero el numero impreso, {@code LC-2026-000123}
 * @param ejercicio el ejercicio de la liquidacion; decide la particion del asiento
 * @param correlativo el correlativo dentro del ejercicio
 * @param expedienteId el expediente cuyo procedimiento se liquida
 * @param contribuyenteId el obligado; es media clave de la obligacion que el cargo crea
 * @param tributo la obligacion del libro a la que se imputa; la otra media
 * @param fecha el dia de la liquidacion (regla 6: entra como argumento)
 * @param conjuntoId el conjunto sellado del que salieron los aranceles (ARQ-09 §3)
 * @param total la suma de las lineas, congelada
 * @param costas las lineas, una por acto liquidado
 * @param registradoEn el instante del registro; sale del reloj inyectado
 * @param usuarioRegistro quien la registro; lo pone el repositorio desde el origen de la peticion
 * @param observacion por que se liquida (regla 10, RNF-052)
 */
public record LiquidacionDeCostas(
        @Nullable Long id,
        String numero,
        Ejercicio ejercicio,
        long correlativo,
        long expedienteId,
        long contribuyenteId,
        String tributo,
        LocalDate fecha,
        long conjuntoId,
        Dinero total,
        List<CostaLiquidada> costas,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /**
     * El tributo al que se imputan las costas del procedimiento.
     *
     * <p>Es el codigo {@code 00101 — COSTAS PROCESALES} del desplegable de la pantalla {@code
     * costas_procesales}. <b>No lleva ninguna cifra</b>: es el nombre de una obligacion del libro,
     * no un importe, y por eso no lo caza la regla 5.
     *
     * <p>La otra opcion del desplegable —{@code 00102 — GASTOS DE EJECUCION}— queda deliberadamente
     * fuera de #42, y no por descuido: un gasto de ejecucion es un desembolso efectivo —la
     * publicacion, el notario, el depositario— que no tiene arancel por acto que lo tarife, asi que
     * liquidarlo por este camino exigiria que el importe lo tecleara quien liquida. Eso es
     * exactamente lo que esta clase existe para impedir. Cuando #193 cierre D-02c con el arancel
     * aprobado se vera si los gastos tienen tarifa propia o si entran por otra puerta.
     */
    public static final String TRIBUTO = TributoDelLibro.COSTAS_PROCESALES.texto();

    /** {@code liquidacion_costas.numero varchar(20)} (V35). */
    public static final int NUMERO_MAXIMO = 20;

    /** El prefijo del numero impreso. */
    private static final String PREFIJO = "LC";

    /** Los ceros del correlativo: {@code LC-2026-000123}. */
    private static final String FORMATO = PREFIJO + "-%d-%06d";

    public LiquidacionDeCostas {
        Objects.requireNonNull(ejercicio, "La liquidacion necesita su ejercicio");
        Objects.requireNonNull(fecha, "La liquidacion es de un dia concreto (regla 6)");
        Objects.requireNonNull(registradoEn, "La liquidacion dice cuando se registro");
        Objects.requireNonNull(total, "La liquidacion necesita su total");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (expedienteId <= 0) {
            throw new IllegalArgumentException("La liquidacion es de un expediente concreto");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("La liquidacion es de un obligado concreto");
        }
        if (correlativo <= 0) {
            throw new IllegalArgumentException("El correlativo de la liquidacion es positivo");
        }
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "La liquidacion dice de que conjunto sellado salieron sus aranceles (ARQ-09"
                            + " §3)");
        }
        numero = Objects.requireNonNull(numero, "La liquidacion necesita su numero").strip();
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero de la liquidacion va de 1 a " + NUMERO_MAXIMO + " caracteres");
        }
        tributo =
                Objects.requireNonNull(tributo, "La liquidacion necesita su tributo")
                        .strip()
                        .toUpperCase(Locale.ROOT);
        if (tributo.isEmpty() || tributo.length() > CostaLiquidada.TRIBUTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tributo va de 1 a " + CostaLiquidada.TRIBUTO_MAXIMO + " caracteres");
        }
        costas = List.copyOf(costas);
        if (costas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una liquidacion sin lineas no liquida nada: si ningun acto del expediente"
                            + " devenga arancel, no hay costas que cobrar");
        }
        Dinero sumadas = Dinero.CERO;
        for (CostaLiquidada costa : costas) {
            sumadas = sumadas.mas(costa.monto());
        }
        if (sumadas.valor().compareTo(total.valor()) != 0) {
            throw new IllegalArgumentException(
                    "El total de la liquidacion ("
                            + total.valor().toPlainString()
                            + ") no es la suma de sus lineas ("
                            + sumadas.valor().toPlainString()
                            + "): el cargo que se asienta en el libro tiene que estar explicado"
                            + " linea a linea");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /**
     * El numero impreso de una liquidacion.
     *
     * <p>Se compone aqui y no en un tipo aparte —a diferencia de {@code NumeroDeExpediente}— porque
     * este numero <b>nunca se analiza al reves</b>: no hay ninguna pantalla que deduzca el
     * ejercicio de un texto tecleado, y por tanto no hay ninguna plantilla que decidir (D-09).
     * Cuando alguien necesite leerlo, este es el sitio donde ponerlo.
     */
    public static String numeroDe(Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(ejercicio, "El numero lleva el ejercicio dentro");
        if (correlativo <= 0) {
            throw new IllegalArgumentException("El correlativo es positivo: " + correlativo);
        }
        return String.format(Locale.ROOT, FORMATO, ejercicio.valor(), correlativo);
    }

    /** Una liquidacion todavia sin guardar, con su numero ya reservado. */
    public static LiquidacionDeCostas nueva(
            Ejercicio ejercicio,
            long correlativo,
            long expedienteId,
            long contribuyenteId,
            LocalDate fecha,
            long conjuntoId,
            List<CostaLiquidada> costas,
            Instant registradoEn,
            Observacion observacion) {
        Dinero total = Dinero.CERO;
        for (CostaLiquidada costa : costas) {
            total = total.mas(costa.monto());
        }
        return new LiquidacionDeCostas(
                null,
                numeroDe(ejercicio, correlativo),
                ejercicio,
                correlativo,
                expedienteId,
                contribuyenteId,
                TRIBUTO,
                fecha,
                conjuntoId,
                total,
                costas,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "La liquidacion todavia no se ha guardado");
    }

    /** Los actos que esta liquidacion tarifa. */
    public List<Long> actos() {
        return costas.stream().map(CostaLiquidada::actoId).toList();
    }
}
