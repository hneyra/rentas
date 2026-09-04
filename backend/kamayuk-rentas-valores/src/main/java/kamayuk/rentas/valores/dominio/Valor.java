package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Orden de pago, resolucion de determinacion o resolucion de multa: la deuda formalizada en un
 * documento notificable (#37, RF-090, ARQ-01 §3.9, V3 {@code valor}).
 *
 * <p><b>No crea deuda: la formaliza.</b> La deuda ya existe en {@code cuentacorriente}, asentada en
 * fase ordinaria; emitir un valor lee esa deuda, la congela en {@link ValorDetalle} y mueve su fase
 * a {@code VALOR} (#21). Por eso este tipo no tiene ningun metodo que calcule un importe: {@link
 * #total} es la suma de lo que ya se congelo, no una cifra que este dominio derive.
 *
 * <p><b>Lo que cambia despues es el estado, nunca el importe.</b> {@code valor} admite {@code
 * UPDATE} (V7) para las transiciones de {@link EstadoDeValor} —notificado, pasado a coactiva,
 * pagado, anulado, prescrito, todas de issues posteriores a #37—; lo que este tipo protege es que
 * el importe congelado no dependa de esas transiciones: reimprimir un valor dos anios despues sigue
 * devolviendo el mismo desglose con el que se emitio (AC de #37).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param tipo OP, RD o RM
 * @param numero el correlativo formateado, unico por municipalidad y tipo
 * @param ejercicio el ejercicio de emision; clave de busqueda de la numeracion
 * @param contribuyenteId a quien se emite
 * @param baseLegal la norma que sustenta el tipo de valor, tal como se cita en el documento
 * @param montoInsoluto el tributo determinado, sin reajuste ni interes; nunca negativo
 * @param montoReajuste el ajuste de cuotas por el indice vigente; nunca negativo
 * @param montoInteres el interes moratorio; nunca negativo
 * @param montoGasto los gastos administrativos y de cobranza; nunca negativo
 * @param proyectadoA a que fecha estaban proyectados los importes (RNF-075): nunca "la deuda",
 *     siempre "la deuda a esta fecha"
 * @param estado en que etapa de la cobranza esta; #37 solo produce {@link EstadoDeValor#EMITIDO}
 * @param fechaEmision la fecha en que se emitio
 * @param usuarioRegistro quien lo emitio; {@code null} en un valor que todavia no se guardo, porque
 *     lo pone el repositorio desde el origen de la peticion, no quien construye el objeto
 * @param observacion por que se emite (regla 10)
 */
public record Valor(
        @Nullable Long id,
        TipoValor tipo,
        String numero,
        Ejercicio ejercicio,
        long contribuyenteId,
        String baseLegal,
        Dinero montoInsoluto,
        Dinero montoReajuste,
        Dinero montoInteres,
        Dinero montoGasto,
        LocalDate proyectadoA,
        EstadoDeValor estado,
        LocalDate fechaEmision,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** El ancho de {@code numero varchar(20)}. */
    private static final int NUMERO_MAXIMO = 20;

    /** El ancho de {@code base_legal varchar(200)}. */
    private static final int BASE_LEGAL_MAXIMA = 200;

    public Valor {
        Objects.requireNonNull(tipo, "El valor necesita su tipo: OP, RD o RM");
        Objects.requireNonNull(numero, "El valor necesita su numero correlativo");
        numero = numero.strip();
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        Objects.requireNonNull(ejercicio, "El valor necesita su ejercicio de emision");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Un valor tiene un destinatario: el identificador de contribuyente debe ser"
                            + " positivo");
        }
        Objects.requireNonNull(baseLegal, "El valor necesita la norma que lo sustenta");
        baseLegal = baseLegal.strip();
        if (baseLegal.isEmpty() || baseLegal.length() > BASE_LEGAL_MAXIMA) {
            throw new IllegalArgumentException(
                    "La base legal va de 1 a " + BASE_LEGAL_MAXIMA + " caracteres");
        }
        montoInsoluto = exigirNoNegativo(montoInsoluto, "insoluto");
        montoReajuste = exigirNoNegativo(montoReajuste, "reajuste");
        montoInteres = exigirNoNegativo(montoInteres, "interes");
        montoGasto = exigirNoNegativo(montoGasto, "gasto");
        Objects.requireNonNull(
                proyectadoA, "Toda cifra de deuda indica su fecha de calculo (RNF-075)");
        Objects.requireNonNull(estado, "El valor necesita su estado");
        Objects.requireNonNull(fechaEmision, "El valor necesita su fecha de emision");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(
                observacion, "Toda emision exige la observacion del usuario (regla 10)");
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** La suma de las cuatro partes, nunca una quinta cifra guardada aparte. */
    public Dinero total() {
        return montoInsoluto.mas(montoReajuste).mas(montoInteres).mas(montoGasto);
    }

    /**
     * El desglose de un {@link Valor} nuevo, sumando el detalle que va a congelar.
     *
     * <p>No lo calcula {@link ValorDetalle} por su cuenta: una desviacion entre esto y la suma de
     * las filas tiene que poder detectarse comparando dos numeros, no confiando en que ambos se
     * calcularon igual.
     */
    public static Desglose desgloseDe(List<ValorDetalle> detalle) {
        Dinero insoluto = Dinero.CERO;
        Dinero reajuste = Dinero.CERO;
        Dinero interes = Dinero.CERO;
        Dinero gasto = Dinero.CERO;
        for (ValorDetalle item : detalle) {
            insoluto = insoluto.mas(item.insoluto());
            reajuste = reajuste.mas(item.reajuste());
            interes = interes.mas(item.interes());
            gasto = gasto.mas(item.gasto());
        }
        return new Desglose(insoluto, reajuste, interes, gasto);
    }

    private static Dinero exigirNoNegativo(@Nullable Dinero valor, String nombre) {
        Objects.requireNonNull(valor, "El valor necesita su importe de " + nombre);
        if (valor.esNegativo()) {
            throw new IllegalArgumentException(
                    "El importe de " + nombre + " no puede ser negativo: " + valor);
        }
        return valor;
    }

    /** Las cuatro partes del desglose, sumadas desde un lote de {@link ValorDetalle}. */
    public record Desglose(Dinero insoluto, Dinero reajuste, Dinero interes, Dinero gasto) {

        public Dinero total() {
            return insoluto.mas(reajuste).mas(interes).mas(gasto);
        }
    }
}
