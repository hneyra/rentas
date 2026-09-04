package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del libro (ADR-0006, RF-040): un {@code CARGO} o un {@code ABONO}, con su contribuyente,
 * su tributo y el concepto por el que se asienta.
 *
 * <p><b>Inmutable de verdad.</b> No hay ningun metodo que devuelva «el mismo asiento modificado»:
 * una vez construido, lo unico que se puede hacer con el es guardarlo o {@linkplain #reversionDe
 * reversarlo} con otro. Corresponde a que la tabla no admite {@code UPDATE} desde la aplicacion
 * (V7) y a que el escaner de fuentes la tiene en {@code TABLAS_INMUTABLES}.
 *
 * <p>{@code monto} <b>nunca</b> es negativo: el signo lo pone {@link #tipo}. {@code referencia
 * Externa} es como entran papeletas y licencias, sin clave foranea a proposito (ARQ-01 §4 regla 2):
 * este contexto no sabe —ni le hace falta saber— de que contexto viene un asiento.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param ejercicio clave de particion de {@code cuenta_corriente_asiento}
 * @param contribuyenteId titular de la obligacion
 * @param tributo el tributo al que se imputa, tal como lo nombra quien asienta
 * @param concepto por que se asienta
 * @param tipo si aumenta o reduce la deuda
 * @param fase en que etapa de la cobranza esta
 * @param periodo la cuota o el mes, si el tributo se divide; {@code null} si no aplica
 * @param predioId la unidad, si la obligacion es predial
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param referenciaExterna como entra una papeleta o una licencia, sin clave foranea
 * @param monto siempre positivo
 * @param fechaValor fecha a la que se imputa el asiento
 * @param documentoOrigen el documento que lo origina: una liquidacion, un recibo, una resolucion
 * @param asientoReversadoId el asiento que este corrige, si es una reversion
 * @param usuarioId quien lo asento; {@code null} en un asiento que todavia no se guardo, porque lo
 *     pone el repositorio desde el origen de la peticion, no quien construye el objeto
 * @param motivo por que se asienta, mas alla del concepto; obligatorio cuando {@link
 *     Concepto#exigeMotivo()}, y en la practica siempre presente (regla 10)
 * @param acto de que acto nace, cuando el libro lo sabe: un alta o una baja de deuda (#601). {@code
 *     null} no es «se desconoce», es «no nacio de ninguno de los dos» —una emision, una cobranza,
 *     una reversion—; ver {@link ActoDelLibro}
 * @param unidadDeTitularAnterior quien registro el movimiento declaro que la unidad NO es del
 *     contribuyente al que se le carga, porque la deuda es de un ejercicio anterior a la
 *     transferencia (#635, #653). Es una <b>declaracion</b>, no un hecho derivado: lo que separa el
 *     acto legitimo del error es que alguien lo diga, y sin esta columna la fila quedaba
 *     indistinguible de un alta sobre la unidad propia
 * @param causal por que se dio de baja la deuda: el sustento juridico del acto (#684). Solo la
 *     lleva una baja —{@code acto = BAJA_DEUDA}—, y {@code null} es «esta fila no la declaro», que
 *     es lo que ocurre en toda baja anterior a V77 y en todo asiento que no es una baja. No
 *     sustituye al {@code motivo}: ese es la observacion DEL USUARIO (regla 10), y componer la
 *     causal dentro de su texto fue el defecto que #684 cierra; ver {@link CausalDeBaja}
 */
public record Asiento(
        @Nullable Long id,
        Ejercicio ejercicio,
        long contribuyenteId,
        String tributo,
        Concepto concepto,
        TipoAsiento tipo,
        Fase fase,
        @Nullable Integer periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable String referenciaExterna,
        Dinero monto,
        LocalDate fechaValor,
        String documentoOrigen,
        @Nullable Long asientoReversadoId,
        @Nullable String usuarioId,
        @Nullable String motivo,
        @Nullable ActoDelLibro acto,
        boolean unidadDeTitularAnterior,
        @Nullable CausalDeBaja causal) {

    /**
     * La forma anterior a #653, que deja la declaracion en {@code false}.
     *
     * <p>Existe para no tocar los diez sitios que construyen un asiento por motivos que nada tienen
     * que ver con esto —una emision, una cobranza, una reversion, un movimiento de fase—: ninguno
     * de ellos puede declarar nada, porque ninguno recibe la declaracion de quien atiende. {@code
     * false} ahi no es un valor por omision escogido por comodidad: es lo que de verdad ocurrio,
     * <b>nadie lo declaro</b>.
     */
    public Asiento(
            @Nullable Long id,
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable Long asientoReversadoId,
            @Nullable String usuarioId,
            @Nullable String motivo,
            @Nullable ActoDelLibro acto) {
        this(
                id,
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                asientoReversadoId,
                usuarioId,
                motivo,
                acto,
                false,
                null);
    }

    /**
     * La forma anterior a #684, que deja la baja <b>sin causal declarada</b>.
     *
     * <p>Existe por lo mismo que la de arriba: la causal solo la conoce quien registra la baja
     * —quien la elige en el desplegable de RF-044, o quien dicta la resolucion que la ordena—, y
     * ningun otro camino del libro puede declararla. {@code null} ahi no es un valor por omision de
     * conveniencia: es que nadie la declaro.
     */
    public Asiento(
            @Nullable Long id,
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable Long asientoReversadoId,
            @Nullable String usuarioId,
            @Nullable String motivo,
            @Nullable ActoDelLibro acto,
            boolean unidadDeTitularAnterior) {
        this(
                id,
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                asientoReversadoId,
                usuarioId,
                motivo,
                acto,
                unidadDeTitularAnterior,
                null);
    }

    /** El ancho de {@code tributo varchar(20)}. */
    private static final int TRIBUTO_MAXIMO = 20;

    /** El ancho de {@code documento_origen varchar(80)}. */
    private static final int DOCUMENTO_MAXIMO = 80;

    /** El ancho de {@code referencia_externa varchar(40)}. */
    private static final int REFERENCIA_MAXIMA = 40;

    /** El ancho de {@code usuario_id varchar(60)}. */
    private static final int USUARIO_MAXIMO = 60;

    /** El ancho de {@code motivo varchar(500)}: el mismo que {@code Observacion}. */
    private static final int MOTIVO_MAXIMO = 500;

    /** {@code periodo smallint}: de 0 (anual) a 12 (la division mas fina, la mensual). */
    private static final int PERIODO_MAXIMO = 12;

    public Asiento {
        Objects.requireNonNull(ejercicio, "El libro se particiona por ejercicio (V2)");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Un asiento tiene un titular: el identificador de contribuyente debe ser"
                            + " positivo");
        }
        Objects.requireNonNull(tributo, "El asiento necesita saber a que tributo se imputa");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty() || tributo.length() > TRIBUTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tributo va de 1 a " + TRIBUTO_MAXIMO + " caracteres: '" + tributo + "'");
        }
        Objects.requireNonNull(concepto, "El asiento necesita su concepto");
        Objects.requireNonNull(tipo, "El asiento necesita saber si es cargo o abono");
        Objects.requireNonNull(fase, "El asiento necesita su fase de cobranza");
        if (periodo != null && (periodo < 0 || periodo > PERIODO_MAXIMO)) {
            throw new IllegalArgumentException(
                    "Periodo fuera de rango: "
                            + periodo
                            + ". Se admite de 0 (anual) a "
                            + PERIODO_MAXIMO);
        }
        Objects.requireNonNull(monto, "El asiento necesita su monto");
        if (!monto.esPositivo()) {
            throw new IllegalArgumentException(
                    "El monto de un asiento es siempre positivo; el signo lo pone el tipo, no el"
                            + " importe (ADR-0006): "
                            + monto);
        }
        Objects.requireNonNull(fechaValor, "El asiento necesita su fecha valor");
        Objects.requireNonNull(documentoOrigen, "El asiento necesita el documento que lo origina");
        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a "
                            + DOCUMENTO_MAXIMO
                            + " caracteres: '"
                            + documentoOrigen
                            + "'");
        }
        if (referenciaExterna != null) {
            referenciaExterna = referenciaExterna.strip();
            if (referenciaExterna.isEmpty()) {
                referenciaExterna = null;
            } else if (referenciaExterna.length() > REFERENCIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La referencia externa excede " + REFERENCIA_MAXIMA + " caracteres");
            }
        }
        if (usuarioId != null) {
            usuarioId = usuarioId.strip();
            if (usuarioId.isEmpty()) {
                usuarioId = null;
            } else if (usuarioId.length() > USUARIO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El usuario excede " + USUARIO_MAXIMO + " caracteres");
            }
        }
        if (motivo != null) {
            motivo = motivo.strip();
            if (motivo.isEmpty()) {
                motivo = null;
            } else if (motivo.length() > MOTIVO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El motivo excede " + MOTIVO_MAXIMO + " caracteres");
            }
        }
        if (concepto.exigeMotivo() && motivo == null) {
            throw new IllegalArgumentException(
                    "El concepto "
                            + concepto
                            + " altera la deuda sin que medie cobro y exige motivo"
                            + " (asiento_motivo_ck, RNF-052)");
        }
        // Solo una BAJA tiene causal (#684): el alta no la tiene y su pantalla no dibuja el
        // desplegable. Es `asiento_causal_del_acto_ck` (V77) dicho aqui tambien, y en esta
        // direccion nada mas. La contraria —«toda baja lleva causal»— NO se puede exigir aqui,
        // y no por descuido: este constructor tambien RECONSTRUYE las filas que vienen de la
        // base, y las bajas anteriores a V77 llevan `acto = BAJA_DEUDA` con la causal en nulo
        // —no se pueden reparar (V7, regla 4)—, asi que exigirla haria que leer el libro de una
        // instalacion con historia reventara. Quien la exige es `MovimientoDeDeuda`, que es una
        // PETICION y no una lectura, y el CHECK `NOT VALID` de V77, que solo mira lo que entra.
        if (causal != null && acto != ActoDelLibro.BAJA_DEUDA) {
            throw new IllegalArgumentException(
                    "Solo una baja de deuda tiene causal, y este asiento nace del acto "
                            + acto
                            + " (asiento_causal_del_acto_ck, V77)");
        }
    }

    /**
     * Un asiento nuevo, todavia sin guardar. Sin {@code id}, sin {@code usuarioId} —lo pone el
     * repositorio desde el origen de la peticion (V2, ARQ-03 §2)— y sin {@code motivo}: lo asienta
     * el caso de uso, con la {@link kamayuk.rentas.dominio.Observacion} de quien lo registra (regla
     * 10).
     */
    public static Asiento nuevo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen) {
        return nuevoDelActo(
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                null);
    }

    /**
     * Un asiento nuevo que nace sabiendo <b>de que acto</b> viene (#601).
     *
     * <p>Existe porque el libro no podia distinguir el abono de una <b>baja de deuda</b> del abono
     * de una <b>cobranza</b>: los dos son {@code ABONO} de concepto {@code INSOLUTO}, columna a
     * columna el mismo asiento. Sin esa distincion, «lo cargado» del panel —el denominador de todas
     * las barras de avance— se quedaba con el cargo de un alta que ya no debe nada, y lo recaudado
     * contaba como dinero que entro una deuda que se habia extinguido.
     *
     * <p>No sustituye a {@link #nuevo}: lo llena {@link MovimientoDeDeuda#enAsientos}, que es el
     * unico sitio del sistema que sabe si esta escribiendo un alta o una baja. Todos los demas
     * caminos dejan {@code acto} en nulo, y eso significa «no nacio de ninguno de los dos», que es
     * exactamente lo que la consulta necesita saber de ellos.
     */
    public static Asiento nuevoDelActo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable ActoDelLibro acto) {
        return nuevoDelActo(
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                acto,
                false);
    }

    /**
     * El mismo asiento del acto, declarando ademas que la unidad es de un titular anterior (#653).
     *
     * <p>Lo llena {@link MovimientoDeDeuda#enAsientos(boolean)}, que es el unico sitio que recibe
     * la declaracion de quien registra el movimiento. Componer la constancia dentro del texto de la
     * observacion seria la otra salida, y se descarto: la observacion la exige la regla 10 para que
     * sea <b>del usuario</b>, y anadirle texto del sistema la convierte en otra cosa —es la misma
     * mutacion que #488 midio y rechazo—.
     */
    public static Asiento nuevoDelActo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable ActoDelLibro acto,
            boolean unidadDeTitularAnterior) {
        return nuevoDelActo(
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                acto,
                unidadDeTitularAnterior,
                null);
    }

    /**
     * El asiento del acto que ademas declara <b>por que</b> se da de baja (#684).
     *
     * <p>Lo llenan los dos unicos sitios que escriben una baja de deuda: {@link
     * MovimientoDeDeuda#enAsientos(boolean)} —la pantalla de RF-044, donde la causal se elige de un
     * desplegable cerrado— y {@code ExtincionDeDeudaCuentaCorriente}, donde la declara quien dicta
     * la resolucion de gerencia. Ningun otro camino del libro puede declararla, y por eso los demas
     * siguen llamando a las sobrecargas de arriba.
     *
     * <p>La causal <b>no</b> sustituye al motivo: ese es la observacion del usuario (regla 10) y lo
     * pone {@code RegistrarAsiento}. Son el sustento y el relato, y este metodo existe justamente
     * para que la primera deje de viajar dentro del segundo.
     */
    public static Asiento nuevoDelActo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable ActoDelLibro acto,
            boolean unidadDeTitularAnterior,
            @Nullable CausalDeBaja causal) {
        return new Asiento(
                null,
                ejercicio,
                contribuyenteId,
                delVocabulario(tributo),
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                null,
                null,
                null,
                acto,
                unidadDeTitularAnterior,
                causal);
    }

    /**
     * Un asiento nuevo que nace <b>con</b> su motivo.
     *
     * <p>Existe porque {@link #nuevo} no sirve para los conceptos que {@link Concepto#exigeMotivo}
     * marca —{@code ANULACION}, {@code CONDONACION}, {@code AJUSTE}—: el constructor los rechaza
     * antes de que {@code RegistrarAsiento} llegue a poner la observacion, y el asiento no se puede
     * construir. Se descubrio ejecutando, en la primera prueba que caso la generacion masiva de #53
     * con el {@code MovimientoDeFase} de verdad: hasta entonces todas las pruebas que movian una
     * obligacion a fase VALOR lo hacian con un doble, y el defecto llevaba desde #37 sin aparecer.
     *
     * <p>El motivo que se pase aqui es provisional: {@code RegistrarAsiento#asentar} lo sustituye
     * por el texto de la {@link kamayuk.rentas.dominio.Observacion} del usuario (regla 10). Lo que
     * este metodo permite es <b>construir</b> el asiento, no decidir con que queda auditado.
     */
    public static Asiento nuevoConMotivo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            String motivo) {
        return new Asiento(
                null,
                ejercicio,
                contribuyenteId,
                delVocabulario(tributo),
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                null,
                null,
                motivo,
                null);
    }

    /**
     * Exige que el tributo de un asiento <b>nuevo</b> este en el vocabulario del libro (#553).
     *
     * <p><b>Aqui y no en el constructor canonico</b>, y la diferencia es lo que separa arreglar el
     * defecto de crear uno peor. El constructor lo usan otros dos caminos que <b>tienen</b> que
     * seguir funcionando con una grafia vieja:
     *
     * <ul>
     *   <li>{@code AsientoRepositoryJdbc.mapear}, que reconstruye un asiento <b>leido</b> de la
     *       base. Las filas escritas antes de que el vocabulario existiera no se pueden corregir
     *       —el libro no admite {@code UPDATE} ni {@code DELETE} (V7, regla 4)—, asi que validar al
     *       leer dejaria a esa instalacion sin estado de cuenta, sin panel y sin caja: un defecto
     *       silencioso convertido en una aplicacion que no arranca su pantalla mas usada.
     *   <li>{@link #reversionDe}, que <b>copia</b> el tributo del original. Reversar es el unico
     *       mecanismo de correccion que la regla 4 deja abierto: cerrarlo sobre las filas
     *       equivocadas seria cerrarlo justo donde hace falta. Por eso {@code V74} exceptua del
     *       {@code CHECK} a la fila que reversa otra.
     * </ul>
     *
     * <p>Lo que si pasa por aqui es <b>todo</b> lo que escribe una obligacion nueva: las tres
     * fabricas {@code nuevo*} son la unica puerta, y por ella entran los siete contextos que
     * asientan.
     */
    private static String delVocabulario(String tributo) {
        return TributoDelLibro.de(tributo).texto();
    }

    public boolean esNuevo() {
        return id == null;
    }

    /**
     * El mismo asiento con su {@code motivo} puesto; es lo unico que cambia de un asiento nuevo.
     */
    public Asiento conMotivo(String otroMotivo) {
        return new Asiento(
                id,
                ejercicio,
                contribuyenteId,
                tributo,
                concepto,
                tipo,
                fase,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                asientoReversadoId,
                usuarioId,
                otroMotivo,
                acto,
                unidadDeTitularAnterior,
                causal);
    }

    /**
     * La reversion de un asiento ya guardado: mismo contribuyente, tributo, concepto, fase y monto,
     * con el {@link TipoAsiento} <b>opuesto</b> y {@code asientoReversadoId} apuntando al original.
     *
     * <p>«Un asiento equivocado no se corrige, se reversa» (V2): esta es la unica forma de
     * corregirlo, y deja <b>dos</b> filas en el libro, ninguna modificada. El {@code ejercicio} de
     * la reversion es el de su propia {@code fecha}, no el del original: una reversion de diciembre
     * de 2026 hecha en enero de 2027 cae en la particion 2027, como cualquier asiento nuevo.
     *
     * @param original el asiento que se corrige; tiene que estar ya guardado
     * @param fecha fecha valor de la reversion
     * @param documentoOrigen el documento que sustenta la reversion
     * @param motivo por que se reversa; regla 10 lo exige y aqui, ademas, casi siempre lo exige
     *     tambien {@link Concepto#exigeMotivo()}
     */
    public static Asiento reversionDe(
            Asiento original, LocalDate fecha, String documentoOrigen, String motivo) {
        Long idOriginal =
                Objects.requireNonNull(
                        original.id(), "Solo se reversa un asiento que ya esta en el libro");
        return new Asiento(
                null,
                Ejercicio.de(fecha),
                original.contribuyenteId(),
                original.tributo(),
                original.concepto(),
                original.tipo().opuesto(),
                original.fase(),
                original.periodo(),
                original.predioId(),
                original.vehiculoId(),
                original.referenciaExterna(),
                original.monto(),
                fecha,
                documentoOrigen,
                idOriginal,
                null,
                motivo,
                // El acto se COPIA, como todo lo demas: la reversion de una baja sigue
                // siendo del acto de esa baja. Que no la cuente «lo cargado» no lo decide
                // esta columna sino `asiento_reversado_id IS NULL`, que #56 ya puso.
                original.acto(),
                // Y la declaracion tambien: reversar un alta declarada de titular anterior no
                // deshace la declaracion, la contabiliza al reves.
                original.unidadDeTitularAnterior(),
                // Y la causal, por lo mismo que el acto (#684): la reversion de una baja por
                // prescripcion sigue siendo el rastro de aquella baja por prescripcion. Perderla
                // aqui dejaria la fila que DESHACE el acto fuera del filtro que lo encuentra.
                original.causal());
    }
}
