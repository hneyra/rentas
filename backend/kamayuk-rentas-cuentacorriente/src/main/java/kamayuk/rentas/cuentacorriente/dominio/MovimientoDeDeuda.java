package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un alta (nota de abono) o una baja (nota de cargo) de deuda, con su desglose (RF-043, RF-044).
 *
 * <p>Con ADR-0006 «dejan de ser un informe y pasan a ser el mecanismo: un asiento mas» (#24). Este
 * objeto es la <b>peticion</b> —lo que alguien pide dar de alta o de baja—, y {@link #enAsientos}
 * la traduce a los asientos que la representan. No hay ningun {@code UPDATE} de deuda existente en
 * ninguna parte del camino: un alta son cargos, una baja son abonos, y el libro solo crece.
 *
 * <p>El desglose son las mismas cuatro partes que devuelve {@link CalculoDeDeuda}: insoluto,
 * reajuste, interes y gasto. Cada una que venga con importe produce <b>su</b> asiento, con su
 * concepto; las que vengan en cero no producen ninguno —un asiento de cero no dice nada y el
 * dominio ni siquiera lo admite, porque {@link Asiento} exige monto positivo—.
 *
 * @param sentido si incorpora deuda o la extingue
 * @param clave la obligacion afectada
 * @param insoluto parte del movimiento que va contra el tributo
 * @param reajuste parte que va contra el reajuste
 * @param interes parte que va contra el interes
 * @param gasto parte que va contra los gastos
 * @param fase la etapa de cobranza en la que queda
 * @param fechaValor la fecha con efecto tributario
 * @param documentoOrigen el sustento: la resolucion, el expediente o el informe que lo aprueba
 * @param referenciaExterna como entra la referencia de otro contexto, si la hay
 * @param causal por que se da de baja, con vocabulario cerrado (#684). Obligatoria en una baja y
 *     prohibida en un alta: ver el constructor
 */
public record MovimientoDeDeuda(
        SentidoDelMovimiento sentido,
        ClaveDeSaldo clave,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto,
        Fase fase,
        LocalDate fechaValor,
        String documentoOrigen,
        @Nullable String referenciaExterna,
        @Nullable CausalDeBaja causal) {

    /**
     * La forma anterior a #684, sin causal. <b>Sirve para un alta y no para una baja</b>: el
     * constructor canonico rechaza una baja sin causal, asi que esta sobrecarga existe para los
     * contextos que generan cargos por su cuenta —licencias, anuncios, tesoreria, coactiva— y que
     * solo dan de alta.
     */
    public MovimientoDeDeuda(
            SentidoDelMovimiento sentido,
            ClaveDeSaldo clave,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto,
            Fase fase,
            LocalDate fechaValor,
            String documentoOrigen,
            @Nullable String referenciaExterna) {
        this(
                sentido,
                clave,
                insoluto,
                reajuste,
                interes,
                gasto,
                fase,
                fechaValor,
                documentoOrigen,
                referenciaExterna,
                null);
    }

    public MovimientoDeDeuda {
        Objects.requireNonNull(sentido, "Un movimiento de deuda es un alta o una baja");
        Objects.requireNonNull(clave, "El movimiento afecta a una obligacion concreta");
        // #553: aqui, y no en ClaveDeSaldo, porque la clave tambien identifica una obligacion
        // que se LEE —el repositorio la construye al mapear una fila, y los criterios de
        // consulta la usan para buscar—, y las filas escritas con otra grafia antes de que el
        // vocabulario existiera no se pueden corregir (V7, regla 4): hay que poder seguir
        // consultandolas. Un MovimientoDeDeuda, en cambio, es siempre una PETICION de escritura.
        // Ponerlo aqui es ademas lo que hace que el alta conteste 422 y no 500: el controlador
        // construye este objeto dentro del try que traduce IllegalArgumentException.
        TributoDelLibro.de(clave.tributo());
        Objects.requireNonNull(
                insoluto, "El desglose lleva sus cuatro partes, en cero si no aplica");
        Objects.requireNonNull(
                reajuste, "El desglose lleva sus cuatro partes, en cero si no aplica");
        Objects.requireNonNull(
                interes, "El desglose lleva sus cuatro partes, en cero si no aplica");
        Objects.requireNonNull(gasto, "El desglose lleva sus cuatro partes, en cero si no aplica");
        Objects.requireNonNull(fase, "El movimiento necesita su fase de cobranza");
        Objects.requireNonNull(fechaValor, "El movimiento necesita su fecha valor");
        Objects.requireNonNull(
                documentoOrigen, "Sin sustento documental no se registra (RF-043, RF-044)");
        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sin sustento documental no se registra: un alta o una baja de deuda sin la"
                            + " resolucion que la aprueba no se puede defender ante nadie");
        }
        if (insoluto.esNegativo()
                || reajuste.esNegativo()
                || interes.esNegativo()
                || gasto.esNegativo()) {
            throw new IllegalArgumentException(
                    "Ninguna parte del desglose va en negativo: el sentido lo pone el tipo de"
                            + " movimiento, no el importe (ADR-0006)");
        }
        if (insoluto.esCero() && reajuste.esCero() && interes.esCero() && gasto.esCero()) {
            throw new IllegalArgumentException(
                    "Un movimiento de deuda sin ningun importe no mueve nada: al menos una de las"
                            + " cuatro partes tiene que traer cifra");
        }
        // La causal es del acto de dar de baja, y de ninguno mas (#684). Aqui SI se puede exigir
        // en las dos direcciones —al reves que en `Asiento`, que ademas reconstruye las filas que
        // vienen de la base—: esto es una PETICION, y una baja que no dice por que es la que este
        // issue existe para impedir. Hasta #684 la causal viajaba dentro del texto de la
        // observacion, que es del usuario (regla 10) y no se puede filtrar ni contar.
        if (sentido == SentidoDelMovimiento.BAJA && causal == null) {
            throw new IllegalArgumentException(
                    "Una baja de deuda declara su causal: es el sustento juridico del acto y no"
                            + " un comentario. Las que hay son "
                            + CausalDeBaja.admitidas());
        }
        if (sentido == SentidoDelMovimiento.ALTA && causal != null) {
            throw new IllegalArgumentException(
                    "Un alta de deuda no tiene causal: el desplegable «Causal» es el de la baja,"
                            + " y lo que sustenta el alta es su documento de origen");
        }
    }

    /**
     * El mismo movimiento, cuota a cuota: uno por cada periodo del rango (#538).
     *
     * <p>Un rango de cuotas <b>no es una obligacion</b>, son {@code n}: {@link ClaveDeSaldo}
     * compara el {@code periodo} por igualdad, asi que «cuotas 1 a 4 del predial 2026» son cuatro
     * filas distintas de {@code saldo_proyectado} y cuatro cuentas distintas que cobrar. Aqui se
     * expande, y el resto del camino sigue tratando una obligacion cada vez.
     *
     * <p><b>El desglose se repite entero en cada cuota, no se reparte entre ellas.</b> Repartir
     * exigiria decidir donde cae el centimo que no cabe —{@code 100,00} entre tres son {@code
     * 33,33} tres veces y sobra uno—, y eso es una politica de redondeo (D-03), no una division: el
     * proyecto ya la tiene decidida para el cronograma de un convenio ({@code Cronograma}, #35) y
     * resolverla exige un conjunto sellado que hoy no existe. Copiar no decide nada: cada centimo
     * que sale es un centimo que entro.
     *
     * <p>Lo que si queda pendiente es que la <b>pantalla</b> lo diga, porque el rotulo del manual
     * es «Insoluto (S/)» a secas junto a «Cuota desde»/«Cuota hasta» y no dice de cual de las dos
     * cosas habla; ver el javadoc de {@code MovimientosDeDeudaController}.
     *
     * @param cuotas las cuotas que el acto abarca; {@link RangoDeCuotas#ANUAL} deja el movimiento
     *     como esta
     */
    public List<MovimientoDeDeuda> enCadaCuota(RangoDeCuotas cuotas) {
        Objects.requireNonNull(cuotas, "El acto abarca al menos una cuota");
        List<MovimientoDeDeuda> unoPorCuota = new ArrayList<>(cuotas.cuantas());
        for (int periodo : cuotas.periodos()) {
            unoPorCuota.add(
                    new MovimientoDeDeuda(
                            sentido,
                            new ClaveDeSaldo(
                                    clave.contribuyenteId(),
                                    clave.tributo(),
                                    clave.ejercicio(),
                                    periodo,
                                    clave.predioId(),
                                    clave.vehiculoId()),
                            insoluto,
                            reajuste,
                            interes,
                            gasto,
                            fase,
                            fechaValor,
                            documentoOrigen,
                            referenciaExterna,
                            causal));
        }
        return List.copyOf(unoPorCuota);
    }

    /** La suma de las cuatro partes: lo que este movimiento incorpora o extingue en total. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }

    /**
     * Los asientos que representan este movimiento: uno por cada parte con importe.
     *
     * <p>Un alta son {@code CARGO} —incorpora deuda— y una baja son {@code ABONO} —la extingue—.
     * Los conceptos son los del desglose, no {@code ANULACION} ni {@code CONDONACION}: el concepto
     * dice <b>contra que</b> se imputa, y el motivo por que. Quien lea el estado de cuenta tiene
     * que poder ver que una baja de S/ 100 quito S/ 80 de insoluto y S/ 20 de interes.
     *
     * <p><b>Y por eso cada asiento nace con su {@link ActoDelLibro}</b> (#601). Ese «por que» no
     * cabia en ninguna columna: un abono de baja y un abono de cobranza son los dos {@code ABONO}
     * de concepto {@code INSOLUTO}, de modo que «lo cargado» del panel se quedaba con el cargo de
     * un alta que ya no debe nada y lo recaudado contaba la baja como dinero que entro. Cambiar el
     * concepto no era una salida: {@code CalculoDeDeuda#netear} y {@code ProyeccionDelSaldo} netean
     * <b>por concepto</b>, asi que una baja escrita como {@code ANULACION} dejaria de restar del
     * insoluto y la deuda no bajaria.
     */
    public List<Asiento> enAsientos() {
        return enAsientos(false);
    }

    /**
     * Los asientos del movimiento, declarando si la unidad es de un <b>titular anterior</b> (#653).
     *
     * <p>La declaracion no se deriva de nada: la hace quien registra el movimiento, y por eso entra
     * como argumento hasta aqui en vez de mirarse en el padron. Un alta sobre la unidad de otro es
     * legitima cuando la deuda es de un ejercicio anterior a la transferencia —el arbitrio de 2024
     * se le cobra a quien era titular en 2024—, y lo unico que separa ese acto del error de teclear
     * el predio equivocado es que alguien lo diga. Sin esta columna la fila del libro y su fila de
     * auditoria quedaban <b>identicas</b> a las de un alta sobre la unidad propia.
     */
    public List<Asiento> enAsientos(boolean unidadDeTitularAnterior) {
        // Sin unidad no hay nada que declarar. La peticion puede traer la marca y ninguna unidad
        // —el cuerpo declara las dos cosas por separado—, y grabarla entonces afirmaria de una
        // obligacion sin predio ni vehiculo que «su unidad es de otro», que no significa nada. Es
        // ademas lo que hace de `asiento_titular_anterior_ck` (V71) una invariante de verdad y no
        // una restriccion que el propio sistema puede violar.
        boolean declarada =
                unidadDeTitularAnterior && (clave.predioId() != null || clave.vehiculoId() != null);
        TipoAsiento tipo =
                sentido == SentidoDelMovimiento.ALTA ? TipoAsiento.CARGO : TipoAsiento.ABONO;
        ActoDelLibro acto =
                sentido == SentidoDelMovimiento.ALTA
                        ? ActoDelLibro.ALTA_DEUDA
                        : ActoDelLibro.BAJA_DEUDA;
        List<Asiento> asientos = new ArrayList<>();
        agregarSiTraeImporte(asientos, Concepto.INSOLUTO, insoluto, tipo, acto, declarada);
        agregarSiTraeImporte(asientos, Concepto.REAJUSTE, reajuste, tipo, acto, declarada);
        agregarSiTraeImporte(asientos, Concepto.INTERES, interes, tipo, acto, declarada);
        agregarSiTraeImporte(asientos, Concepto.GASTO, gasto, tipo, acto, declarada);
        return List.copyOf(asientos);
    }

    private void agregarSiTraeImporte(
            List<Asiento> asientos,
            Concepto concepto,
            Dinero monto,
            TipoAsiento tipo,
            ActoDelLibro acto,
            boolean unidadDeTitularAnterior) {
        if (monto.esCero()) {
            return;
        }
        asientos.add(
                Asiento.nuevoDelActo(
                        clave.ejercicio(),
                        clave.contribuyenteId(),
                        clave.tributo(),
                        concepto,
                        tipo,
                        fase,
                        clave.periodo(),
                        clave.predioId(),
                        clave.vehiculoId(),
                        referenciaExterna,
                        monto,
                        fechaValor,
                        documentoOrigen,
                        acto,
                        unidadDeTitularAnterior,
                        causal));
    }
}
