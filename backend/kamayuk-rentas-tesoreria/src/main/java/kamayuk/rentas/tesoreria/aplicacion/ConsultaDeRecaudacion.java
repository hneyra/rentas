package kamayuk.rentas.tesoreria.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.dominio.ArqueoDelTurno;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.tesoreria.dominio.RecaudacionRepository;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El avance de recaudacion y su distribucion por area y partida (#36, RF-088, RF-089).
 *
 * <h2>Lecturas que no contienden con la ventanilla</h2>
 *
 * <p>Las tres son {@code @Transactional(readOnly = true)} y ninguna pide un bloqueo. Es el punto
 * central de RF-088: el avance se mira <b>mientras el cajero sigue cobrando</b>, y una lectura que
 * tomara el turno con {@code FOR UPDATE} —que es lo que hace la cobranza— pondria la cola de la
 * ventanilla a esperar por un informe. La cifra que devuelven es la del instante en que se leyo, y
 * por eso viaja con su fecha (regla 9, RNF-075).
 *
 * <p>Que existan como caso de uso y no como llamadas sueltas al repositorio viene de lo que la
 * marcha blanca de #26 destapo: {@code GET /catastro/vias} corria sin transaccion, y sin
 * transaccion no hay {@code SET LOCAL}, asi que RLS falla.
 *
 * <h2>Lo que la pantalla pide y no existe como dato</h2>
 *
 * <p>La pantalla de avance dibuja siete columnas y este servicio contesta tres. No es una oleada a
 * medias: las otras cuatro no tienen de donde salir.
 *
 * <ul>
 *   <li><b>Meta</b> y <b>% de meta</b>: no hay ninguna tabla de metas de recaudacion en el esquema.
 *       Una meta es un acto de gestion —se aprueba, se modifica, se compara— y darle como valor «lo
 *       emitido» o una cifra fija produciria un porcentaje de cumplimiento que nadie firmo.
 *   <li><b>Emitido</b> y, con el, <b>saldo</b> y <b>% avance</b>: lo emitido son los <b>cargos</b>
 *       del libro, y este contexto no lee las tablas de {@code cuentacorriente} (ARQ-01 §4). El
 *       enganche esta identificado —haria falta una API publica que agregue cargos por tributo y
 *       rango, hermana de {@link kamayuk.rentas.cuentacorriente.ConciliacionDeCaja}—, y no se
 *       escribe aqui porque «lo emitido» tiene su propia pregunta abierta: si cuenta la
 *       determinacion anual, el valor notificado, o el cargo asentado, y los tres dan cifras
 *       distintas.
 * </ul>
 *
 * <p>Lo que si se contesta, y es lo que la caja necesita hoy: cuanto entro, cuanto se anulo y
 * cuanto queda neto, por tributo, por area y por partida.
 */
@Service
public class ConsultaDeRecaudacion {

    private final RecaudacionRepository recaudacion;
    private final ArqueoDeTurno arqueos;

    public ConsultaDeRecaudacion(RecaudacionRepository recaudacion, ArqueoDeTurno arqueos) {
        this.recaudacion = recaudacion;
        this.arqueos = arqueos;
    }

    /** Lo recaudado por tributo en el rango del criterio (RF-088). */
    @Transactional(readOnly = true)
    public Avance avance(CriterioDeRecaudacion criterio, LocalDate aLaFecha) {
        Objects.requireNonNull(criterio, "La consulta necesita su criterio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        return new Avance(recaudacion.porTributo(criterio), criterio, aLaFecha);
    }

    /**
     * El avance <b>en vivo</b> del turno de un cajero: lo que lleva cobrado y anulado hoy.
     *
     * <p>Es lo que la pantalla de cierre llama «Cuadrar»: el mismo arqueo que el cierre congelara,
     * mirado antes de firmarlo y sin declarar nada todavia —de ahi que las cifras declaradas salgan
     * en cero y la diferencia sea el neto en negativo—.
     *
     * <p><b>Sin bloquear el turno</b>, y ese es todo el punto: se resuelve por {@link
     * RecaudacionRepository#turnoDe} y no por {@code TurnoDeCajaRepository#bloquear}, que es la
     * puerta de la cobranza. Consultar el avance mientras la ventanilla cobra no hace esperar a
     * nadie.
     *
     * @return vacio si ese cajero no ha abierto turno ese dia en esa caja
     */
    @Transactional(readOnly = true)
    public Optional<AvanceDelTurno> delTurno(
            String codigoDeCaja, String cajero, LocalDate fecha, LocalDate aLaFecha) {
        Objects.requireNonNull(codigoDeCaja, "El avance en vivo es de una ventanilla");
        Objects.requireNonNull(cajero, "El avance en vivo es de un cajero");
        Objects.requireNonNull(fecha, "El turno es de un dia concreto (regla 6)");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        return recaudacion
                .turnoDe(codigoDeCaja, cajero, fecha)
                .map(
                        turno ->
                                new AvanceDelTurno(
                                        turno,
                                        arqueos.del(
                                                turno.idGuardado(), java.util.Map.of(), aLaFecha),
                                        aLaFecha));
    }

    /** Lo recaudado por area generadora, partida presupuestal y tributo (RF-089). */
    @Transactional(readOnly = true)
    public Distribucion porPartida(CriterioDeRecaudacion criterio, LocalDate aLaFecha) {
        Objects.requireNonNull(criterio, "La consulta necesita su criterio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        return new Distribucion(recaudacion.porPartida(criterio), criterio, aLaFecha);
    }

    // ------------------------------------------------------------------

    /**
     * El avance por tributo, con su rango y su fecha.
     *
     * @param filas una por tributo con movimiento en el rango
     * @param criterio lo que se pidio; viaja de vuelta para que el papel diga que rango es
     * @param aLaFecha la fecha a la que se leyo (regla 9, RNF-075)
     */
    public record Avance(
            List<RecaudacionDeTributo> filas, CriterioDeRecaudacion criterio, LocalDate aLaFecha) {

        public Avance {
            filas = List.copyOf(filas);
            Objects.requireNonNull(criterio, "El avance dice que rango cubre");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }

        public Dinero totalCobrado() {
            Dinero total = Dinero.CERO;
            for (RecaudacionDeTributo fila : filas) {
                total = total.mas(fila.cobrado());
            }
            return total;
        }

        public Dinero totalAnulado() {
            Dinero total = Dinero.CERO;
            for (RecaudacionDeTributo fila : filas) {
                total = total.mas(fila.anulado());
            }
            return total;
        }

        /**
         * El neto del periodo: la suma de los netos de las filas.
         *
         * <p>Es exactamente {@code totalCobrado - totalAnulado}, y lo es <b>sin redondear</b>:
         * repartir la recaudacion por tributo no divide nada —cada linea de recibo tiene un tributo
         * y su importe va entero a el—, asi que la suma de las partes es el total al centimo. No
         * hay ningun {@link kamayuk.rentas.dominio.PuntoDeRedondeo} que aplicar aqui, y aplicar uno
         * seria D-03 tomada por descuido.
         */
        public Dinero neto() {
            return totalCobrado().menos(totalAnulado());
        }
    }

    /**
     * El avance en vivo de un turno.
     *
     * @param turno la apertura, con su estado derivado
     * @param arqueo lo cobrado y lo anulado hasta ahora, sin nada declarado todavia
     * @param aLaFecha la fecha a la que se leyo (regla 9, RNF-075)
     */
    public record AvanceDelTurno(TurnoDeCaja turno, ArqueoDelTurno arqueo, LocalDate aLaFecha) {

        public AvanceDelTurno {
            Objects.requireNonNull(turno, "El avance en vivo es de un turno");
            Objects.requireNonNull(arqueo, "El avance en vivo trae su arqueo");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }
    }

    /**
     * La distribucion por area y partida, con su rango y su fecha.
     *
     * @param filas una por (area, partida, tributo) con movimiento en el rango
     * @param criterio lo que se pidio
     * @param aLaFecha la fecha a la que se leyo (regla 9, RNF-075)
     */
    public record Distribucion(
            List<RecaudacionDePartida> filas, CriterioDeRecaudacion criterio, LocalDate aLaFecha) {

        public Distribucion {
            filas = List.copyOf(filas);
            Objects.requireNonNull(criterio, "La distribucion dice que rango cubre");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }

        /**
         * El neto del periodo: la suma de los netos de las filas, al centimo.
         *
         * <p>Sin redondeo y sin centimos huerfanos, por lo mismo que en {@link Avance#neto}: la
         * distribucion <b>reparte filas</b>, no prorratea un total. Una linea de recibo pertenece a
         * una sola partida —o a ninguna, si es tributaria— y su importe va entera a ese grupo.
         */
        public Dinero neto() {
            Dinero total = Dinero.CERO;
            for (RecaudacionDePartida fila : filas) {
                total = total.mas(fila.neto());
            }
            return total;
        }

        /**
         * Lo que no se puede imputar a ninguna partida presupuestal.
         *
         * <p>Es todo lo tributario, y se expone en vez de esconderse: quien lea el reporte a la
         * gerencia tiene que ver que la suma de las partidas no es la recaudacion del periodo, y
         * por que. Ver {@link RecaudacionDePartida}.
         */
        public Dinero netoSinPartida() {
            Dinero total = Dinero.CERO;
            for (RecaudacionDePartida fila : filas) {
                if (!fila.tienePartida()) {
                    total = total.mas(fila.neto());
                }
            }
            return total;
        }
    }
}
