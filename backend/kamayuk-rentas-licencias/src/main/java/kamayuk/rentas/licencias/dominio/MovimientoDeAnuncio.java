package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que le paso a una autorizacion de anuncio (#51, V45 §2).
 *
 * <p><b>Solo se agrega.</b> V45 le concede a {@code kamayuk_app} nada mas que {@code SELECT} e
 * {@code INSERT} sobre {@code anuncio_movimiento}, y el escaner de fuentes rechaza un {@code UPDATE
 * anuncio_movimiento SET} antes de que llegue a ejecutarse.
 *
 * <h2>El movimiento es donde vive la garantia de «un cargo, una vez»</h2>
 *
 * <p>{@link #referenciaCargo} es <b>la misma cadena</b> que viaja al libro como {@code
 * referencia_externa}, y {@code anuncio_movimiento_cargo_uq} la declara unica. De ahi sale el
 * primer criterio de aceptacion de #51: la segunda peticion que intentara devengar la tasa del
 * mismo anuncio y el mismo ejercicio revienta al insertar este movimiento, <b>antes</b> de pedirle
 * nada a {@code cuentacorriente} y dentro de la misma transaccion.
 *
 * <p>Que la unicidad este aqui y no en {@code cuenta_corriente_asiento} no es comodidad: alli
 * {@code referencia_externa} <b>no</b> es unica por diseño —#42 asienta varias costas del mismo
 * expediente con la misma referencia— y ademas el libro es de otro contexto. La unicidad se declara
 * donde el hecho ocurre.
 *
 * <h2>{@link #tasa} se copia, no se recalcula</h2>
 *
 * <p>El importe queda escrito en el mismo acto en que se asento, igual que {@code valor_movimiento}
 * copia su exigibilidad (V28 §2). Dentro de dos anios la ordenanza puede ser otra, y esta fila
 * tiene que decir lo que se cobro y no lo que se cobraria hoy (regla 9, RNF-075). Su fecha es
 * {@link #fecha}, que es la fecha valor con la que el cargo entro en el libro.
 *
 * @param id nulo mientras no se haya guardado
 * @param anuncioId la autorizacion sobre la que se actua
 * @param tipo que le paso
 * @param fecha el dia del acto; entra como argumento, no del reloj (regla 6). Es tambien la fecha
 *     valor del cargo, cuando el acto devenga
 * @param ejercicio el ejercicio al que se imputa la tasa; nulo cuando el acto no devenga. En la
 *     renovacion es el que renueva, no el de {@code fecha}: {@link #ejercicioQueRenueva} (#417)
 * @param referenciaCargo la referencia con la que el cargo entro en el libro; nula cuando el acto
 *     no devenga
 * @param tasa el importe asentado; nulo cuando el acto no devenga
 * @param vigenciaHasta hasta cuando queda vigente el anuncio tras este acto; nulo en el cese y el
 *     retiro, que no mueven la vigencia sino que la terminan
 * @param motivo por que se cesa o se retira; obligatorio en esos dos y ausente en los otros dos,
 *     tal como exige {@code anuncio_movimiento_motivo_ck}
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro; sale del origen de la sesion, nunca de la peticion
 * @param observacion por que se registro (regla 10, RNF-052)
 */
public record MovimientoDeAnuncio(
        @Nullable Long id,
        long anuncioId,
        TipoDeMovimientoDeAnuncio tipo,
        LocalDate fecha,
        @Nullable Ejercicio ejercicio,
        @Nullable String referenciaCargo,
        @Nullable Dinero tasa,
        @Nullable LocalDate vigenciaHasta,
        @Nullable String motivo,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** El ancho de {@code anuncio_movimiento.referencia_cargo varchar(40)}. */
    public static final int REFERENCIA_MAXIMA = 40;

    public MovimientoDeAnuncio {
        Objects.requireNonNull(tipo, "Un movimiento sin tipo no dice nada");
        Objects.requireNonNull(fecha, "El movimiento lleva la fecha del acto (regla 6)");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        if (motivo != null) {
            motivo = motivo.strip();
            if (motivo.isEmpty()) {
                motivo = null;
            }
        }
        if (tipo.exigeMotivo() != (motivo != null)) {
            throw new IllegalArgumentException(
                    "Un cese y un retiro se motivan, y una autorizacion y una renovacion no: su"
                            + " motivo es la solicitud del administrado, que ya esta en el"
                            + " expediente");
        }

        boolean devengado = referenciaCargo != null && tasa != null && ejercicio != null;
        boolean algoDelDevengo = referenciaCargo != null || tasa != null || ejercicio != null;
        if (tipo.devenga() != devengado || (!tipo.devenga() && algoDelDevengo)) {
            throw new IllegalArgumentException(
                    "El acto "
                            + tipo
                            + (tipo.devenga() ? " devenga tasa" : " no devenga tasa")
                            + ", y las tres columnas del devengo -ejercicio, referencia e"
                            + " importe- van las tres o ninguna: media explicacion de un cargo es"
                            + " un cargo que nadie sabe de donde salio");
        }
        if (referenciaCargo != null) {
            if (referenciaCargo.length() > REFERENCIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La referencia '"
                                + referenciaCargo
                                + "' excede los "
                                + REFERENCIA_MAXIMA
                                + " caracteres de anuncio_movimiento.referencia_cargo");
            }
            Dinero importe = tasa;
            if (importe == null || importe.valor().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Una tasa de cero o negativa no es una tasa. Una clase que la ordenanza no"
                                + " tarifa se deja SIN parametro, que es distinto de tarifarla en"
                                + " cero");
            }
        }
        if (!tipo.devenga() && vigenciaHasta != null) {
            throw new IllegalArgumentException(
                    "El cese y el retiro no mueven la vigencia del anuncio: la terminan");
        }
    }

    /**
     * La referencia con la que el cargo de ese anuncio y ese ejercicio entra en el libro.
     *
     * <p><b>Lleva el ejercicio dentro, y ahi esta todo el diseño.</b> Es la cadena que {@code
     * anuncio_movimiento_cargo_uq} declara unica, asi que decidir que entra en ella es decidir
     * cuantas veces puede cobrarse la tasa: con el ejercicio, un anuncio devenga una vez por año y
     * la renovacion del año siguiente pasa; sin el, la primera renovacion seria imposible.
     *
     * <p>Se compone del <b>numero</b> de la autorizacion y no de su identificador, al reves que
     * {@code RegistrarPapeleta}: alli el numero se puede corregir despues ({@code
     * CambiarNumeroDePapeleta}) y por eso la referencia usa la clave estable de la fila; aqui el
     * numero <b>no se puede cambiar</b> —V45 le revoca el UPDATE al anuncio—, y usarlo hace que un
     * asiento del libro se pueda leer sin cruzar tablas.
     */
    public static String referenciaDelCargo(String numeroDeAutorizacion, Ejercicio ejercicio) {
        Objects.requireNonNull(numeroDeAutorizacion, "La referencia lleva el numero del anuncio");
        Objects.requireNonNull(ejercicio, "La referencia lleva el ejercicio que devenga");
        return "ANUNCIO-" + numeroDeAutorizacion.strip() + "-" + ejercicio.valor();
    }

    /**
     * El ejercicio que una renovacion devenga: el que <b>renueva</b>, no el del dia del acto
     * (#417).
     *
     * <p>Hasta #417 {@code RenovarAnuncio} tomaba {@code Ejercicio.de(fecha)}, y con eso renovar en
     * diciembre un anuncio autorizado ese mismo año componia la referencia de la autorizacion y
     * salia 409 —la unica salida era dejarlo caer en VENCIDO—, y lo que se renovaba en diciembre
     * para el año siguiente se asentaba en el año del acto y con su tarifa. Nadie lo veia porque
     * todas las pruebas renovaban en enero, donde los dos ejercicios coinciden.
     *
     * <h2>La regla</h2>
     *
     * <ul>
     *   <li>El ejercicio de {@code vigenciaHasta}, que es hasta donde llega la prorroga.
     *   <li>Solo si la renovacion no trae plazo, el ejercicio del acto, como antes.
     *   <li>Y una prorroga que abarque <b>mas de un ejercicio</b> no se cobra: {@link
     *       ProrrogaDeVariosEjercicios}. Se cuenta desde el dia siguiente a la vigencia actual si
     *       el anuncio sigue vigente el dia del acto, y desde el acto si ya vencio o nunca tuvo
     *       plazo. Devengar uno por año, o uno por varios, es una decision de negocio que no esta
     *       tomada.
     * </ul>
     *
     * <p>Es una funcion pura (regla 6): las tres fechas entran como argumento.
     *
     * @param vigenciaActual hasta cuando rige el anuncio el dia del acto, antes de renovarlo; nula
     *     si no tiene plazo
     * @param vigenciaHasta hasta cuando lo prorroga la renovacion; nula si no trae plazo. Se supone
     *     no anterior a {@code fecha}: eso lo rechaza antes {@code RenovarAnuncio}
     * @param fecha el dia de la renovacion
     * @throws ProrrogaDeVariosEjercicios si la prorroga cubre mas de un ejercicio
     */
    public static Ejercicio ejercicioQueRenueva(
            @Nullable LocalDate vigenciaActual,
            @Nullable LocalDate vigenciaHasta,
            LocalDate fecha) {
        Objects.requireNonNull(fecha, "La renovacion lleva la fecha del acto (regla 6)");
        if (vigenciaHasta == null) {
            return Ejercicio.de(fecha);
        }
        LocalDate desde =
                vigenciaActual != null && !vigenciaActual.isBefore(fecha)
                        ? vigenciaActual.plusDays(1)
                        : fecha;
        Ejercicio renovado = Ejercicio.de(vigenciaHasta);
        if (Ejercicio.de(desde).compareTo(renovado) < 0) {
            throw new ProrrogaDeVariosEjercicios(desde, vigenciaHasta);
        }
        return renovado;
    }

    /** El acto que da vida a la autorizacion, con el cargo por la tasa del ejercicio. */
    public static MovimientoDeAnuncio autorizacion(
            long anuncioId,
            LocalDate fecha,
            Ejercicio ejercicio,
            String referenciaCargo,
            Dinero tasa,
            @Nullable LocalDate vigenciaHasta,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeAnuncio(
                null,
                anuncioId,
                TipoDeMovimientoDeAnuncio.AUTORIZACION,
                fecha,
                ejercicio,
                referenciaCargo,
                tasa,
                vigenciaHasta,
                null,
                registradoEn,
                null,
                observacion);
    }

    /** La prorroga por otro ejercicio, con su cargo. */
    public static MovimientoDeAnuncio renovacion(
            long anuncioId,
            LocalDate fecha,
            Ejercicio ejercicio,
            String referenciaCargo,
            Dinero tasa,
            @Nullable LocalDate vigenciaHasta,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeAnuncio(
                null,
                anuncioId,
                TipoDeMovimientoDeAnuncio.RENOVACION,
                fecha,
                ejercicio,
                referenciaCargo,
                tasa,
                vigenciaHasta,
                null,
                registradoEn,
                null,
                observacion);
    }

    /** El acto que detiene la deuda futura sin tocar la pasada. */
    public static MovimientoDeAnuncio cese(
            long anuncioId,
            LocalDate fecha,
            String motivo,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeAnuncio(
                null,
                anuncioId,
                TipoDeMovimientoDeAnuncio.CESE,
                fecha,
                null,
                null,
                null,
                null,
                motivo,
                registradoEn,
                null,
                observacion);
    }

    /** La constancia de que el elemento ya no esta en la calle. */
    public static MovimientoDeAnuncio retiro(
            long anuncioId,
            LocalDate fecha,
            String motivo,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeAnuncio(
                null,
                anuncioId,
                TipoDeMovimientoDeAnuncio.RETIRO,
                fecha,
                null,
                null,
                null,
                null,
                motivo,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("El movimiento todavia no se ha guardado");
        }
        return guardado;
    }

    /**
     * La prorroga cubre mas de un ejercicio, y una renovacion devenga uno solo (#417).
     *
     * <p>No es que la peticion este mal escrita: es que cobrar una tasa por dos años, o dos tasas
     * en un acto, es una decision de negocio que no esta tomada. Hasta que se tome no se cobra ni
     * lo uno ni lo otro, y se renueva ejercicio a ejercicio.
     */
    public static final class ProrrogaDeVariosEjercicios extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ProrrogaDeVariosEjercicios(LocalDate desde, LocalDate hasta) {
            super(
                    "La prorroga del "
                            + desde
                            + " al "
                            + hasta
                            + " abarca los ejercicios "
                            + desde.getYear()
                            + " a "
                            + hasta.getYear()
                            + ", y una renovacion devenga uno solo: cobrar uno por varios años, o"
                            + " varios en un acto, no esta decidido. Se renueva ejercicio a"
                            + " ejercicio (#417)");
        }
    }
}
