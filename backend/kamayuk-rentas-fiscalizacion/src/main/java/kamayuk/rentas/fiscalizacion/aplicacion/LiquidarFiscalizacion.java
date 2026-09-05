package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.PlantillaDeNumeroDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.nucleo.DeclaracionDelEjercicio;
import kamayuk.rentas.nucleo.DeclaracionesDelEjercicio;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liquida un acta de fiscalización: el consolidado de lo hallado frente a lo declarado, ejercicio
 * por ejercicio (#49, RF-053).
 *
 * <h2>Qué escribe, y sobre todo qué no</h2>
 *
 * <p>Escribe en {@code liquidacion_fiscalizacion}, {@code liquidacion_detalle} y {@code
 * liquidacion_movimiento}, y en ninguna tabla más. <b>No toca {@code catastro} ni {@code
 * rentas}</b> —AC 4 de #49—: la comparación se hace sobre las copias que el acta ya guarda y sobre
 * lo que los puertos públicos {@link LectorDeCaracteristicas} y {@link DeclaracionesDelEjercicio}
 * <i>leen</i>. Los dos son de solo lectura, y ése es el punto: no hay ningún camino desde aquí que
 * escriba en el padrón.
 *
 * <p>Tampoco genera cargos. Que la diferencia liquidada se convierta en deuda es la
 * <b>transferencia a rentas</b> —RF-054, <b>#52</b>—, que decide además qué versión de la ficha
 * sustituye a cuál. El enganche está nombrado y no implementado a propósito: hacerlo aquí sería
 * tomar por su cuenta la decisión arquitectónica que ese issue existe para tomar.
 *
 * <h2>Cada línea fija su conjunto sellado (AC 1)</h2>
 *
 * <p>Para cada ejercicio del periodo se resuelve el conjunto <b>sellado</b> de ese ejercicio con
 * {@link LectorDeParametros#conjuntoVigenteEn} y se <b>copia</b> en la línea. A partir de ahí el
 * conjunto de la línea no se vuelve a resolver: sellar otra versión mañana no altera esta
 * liquidación.
 *
 * <p>Un ejercicio sin conjunto sellado detiene la liquidación con {@link
 * LectorDeParametros.EjercicioSinSellar}, nombrándolo. No hay valor por omisión: liquidar contra un
 * conjunto abierto produciría una cifra que mañana es otra, y el contribuyente ya tendría el papel.
 *
 * <h2>Lo declarado se lee a la fecha en que se declaró</h2>
 *
 * <p>El área y el uso que constan declarados salen de la ficha catastral vigente <b>a la fecha de
 * presentación de la declaración jurada</b>, no de la ficha de hoy (RNF-075). Comparar lo hallado
 * contra la ficha actual acusaría de subvaluación a quien declaró correctamente sobre lo que
 * entonces existía —y es el mismo motivo por el que la propia declaración guarda su {@code
 * fichaCatastralId} desde #28—.
 *
 * <h2>El uso hallado sale del acta, no de quien liquida (#599)</h2>
 *
 * <p>Hasta que {@code acta_fiscalizacion} tuvo su columna {@code uso_hallado} (V76), el uso
 * observado llegaba como <b>argumento de este método</b>: lo tecleaba quien liquidaba, no quien
 * visitó. Ahora se lee del acta, y con eso el hecho tiene un solo sitio donde vive. Corregirlo
 * sigue siendo <b>reliquidar</b> —{@link ReliquidarFiscalizacion.CorreccionDeLinea} ya lo admitía—,
 * que es otra versión que referencia a la anterior y explica la diferencia: mantener además el
 * argumento habría dejado dos verdades sobre lo mismo, que es lo que #397 y #481 evitaron derivando
 * en vez de guardar dos veces.
 *
 * <h2>Ni un importe</h2>
 *
 * <p>Las líneas salen con {@code baseDeclarada}, {@code baseHallada}, {@code insolutoOmitido} y
 * {@code multaTributaria} en {@code null}: son D-02a y D-02c (#198). Lo que sí sale con valor es la
 * comparación estructural. Qué llaves faltan para poder valorizar lo dice {@link
 * InsumosNormativosDeLaLiquidacion}, y lo dice fallando con su nombre.
 */
@Service
public class LiquidarFiscalizacion {

    private static final String TABLA_AUDITADA = "liquidacion_fiscalizacion";

    private static final String MOTIVO_DE_APERTURA = "Liquidacion emitida";

    private final ActaFiscalizacionRepository actas;
    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;
    private final LectorDeParametros parametros;
    private final LectorDeCaracteristicas caracteristicas;
    private final LectorDeFichas fichas;
    private final DeclaracionesDelEjercicio declaraciones;
    private final Auditoria auditoria;
    private final Clock reloj;

    public LiquidarFiscalizacion(
            ActaFiscalizacionRepository actas,
            LiquidacionRepository liquidaciones,
            MovimientoDeLiquidacionRepository movimientos,
            LectorDeParametros parametros,
            LectorDeCaracteristicas caracteristicas,
            LectorDeFichas fichas,
            DeclaracionesDelEjercicio declaraciones,
            Auditoria auditoria,
            Clock reloj) {
        this.actas = actas;
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
        this.parametros = parametros;
        this.caracteristicas = caracteristicas;
        this.fichas = fichas;
        this.declaraciones = declaraciones;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite la primera liquidación de un acta.
     *
     * @param actaId el acta ya cerrada en campo
     * @param desde primer ejercicio del periodo fiscalizado
     * @param hasta último ejercicio del periodo fiscalizado
     * @param tipo cómo se determinó lo hallado
     * @param motivoDeterminante por qué se fiscalizó
     * @param fecha el día de la liquidación
     * @param observacion por qué se registra (regla 10)
     */
    @Transactional
    public Liquidacion liquidar(
            long actaId,
            Ejercicio desde,
            Ejercicio hasta,
            TipoDeFiscalizacion tipo,
            String motivoDeterminante,
            LocalDate fecha,
            Observacion observacion) {

        ActaFiscalizacion acta =
                actas.findById(actaId).orElseThrow(() -> new ActaInexistente(actaId));
        liquidaciones
                .ultimaVersionDeActa(actaId)
                .ifPresent(
                        existente -> {
                            throw new ActaYaLiquidada(existente.numero());
                        });

        List<LineaDeLiquidacion> lineas = contrastar(acta, desde, hasta);

        // El correlativo se pide UNA vez: `siguienteCorrelativo` incrementa, asi que llamarlo
        // dos veces -una para el numero impreso y otra para la columna- dejaria la liquidacion
        // numerada LIQ-2026-000007 con el correlativo 8, y el hueco no se recupera.
        Ejercicio deLaNumeracion = Ejercicio.de(fecha);
        long correlativo = liquidaciones.siguienteCorrelativo(deLaNumeracion);

        Liquidacion nueva =
                Liquidacion.primera(
                        PlantillaDeNumeroDeLiquidacion.POR_OMISION.componer(
                                deLaNumeracion, correlativo),
                        deLaNumeracion,
                        correlativo,
                        actaId,
                        desde,
                        hasta,
                        tipo,
                        motivoDeterminante,
                        fecha,
                        observacion);

        return guardar(nueva, lineas, observacion);
    }

    /**
     * Guarda la cabecera, su detalle y su apertura, y la audita.
     *
     * <p>Compartido con {@link ReliquidarFiscalizacion} para que las dos escrituras no puedan
     * divergir: una reliquidación que se guardara sin su apertura nacería sin estado derivable.
     */
    Liquidacion guardar(
            Liquidacion nueva, List<LineaDeLiquidacion> lineas, Observacion observacion) {
        Liquidacion guardada = liquidaciones.insertar(nueva, lineas);
        movimientos.insertar(
                MovimientoDeLiquidacion.apertura(
                        guardada.identificador(),
                        guardada.fecha(),
                        MOTIVO_DE_APERTURA,
                        observacion));
        auditar(guardada, observacion);
        return guardada;
    }

    /** El siguiente correlativo del ejercicio de esa fecha. Lo comparte la reliquidación. */
    long siguienteCorrelativoPara(Ejercicio ejercicio) {
        return liquidaciones.siguienteCorrelativo(ejercicio);
    }

    /**
     * El contraste, ejercicio por ejercicio.
     *
     * <p>Las declaraciones se piden por lote aunque aquí el lote sea de un predio: es el mismo
     * camino que recorre la detección de omisos con páginas de veinte, y tener dos no serviría de
     * nada salvo para que un día difieran.
     */
    private List<LineaDeLiquidacion> contrastar(
            ActaFiscalizacion acta, Ejercicio desde, Ejercicio hasta) {

        List<LineaDeLiquidacion> lineas = new ArrayList<>();
        for (Ejercicio ejercicio = desde;
                ejercicio.compareTo(hasta) <= 0;
                ejercicio = ejercicio.siguiente()) {

            long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();

            if (!acta.esPredial()) {
                lineas.add(
                        LineaDeLiquidacion.vehicularSinCifras(
                                ejercicio,
                                conjuntoId,
                                Objects.requireNonNull(acta.vehiculoId()),
                                condicionVehicular(acta)));
                continue;
            }

            long predioId = Objects.requireNonNull(acta.predioId());
            Optional<DeclaracionDelEjercicio> declarada =
                    Optional.ofNullable(
                            declaraciones.dePredios(Set.of(predioId), ejercicio).get(predioId));

            ComparacionHalladoDeclarado.LoDeclarado loDeclarado = loDeclarado(declarada, predioId);
            ComparacionHalladoDeclarado.LoHallado loHallado =
                    acta.hallazgo() == Hallazgo.NO_UBICADO
                            ? ComparacionHalladoDeclarado.LoHallado.noUbicado()
                            : ComparacionHalladoDeclarado.LoHallado.de(
                                    acta.areaHallada(), acta.usoHallado());

            lineas.add(
                    LineaDeLiquidacion.predialSinCifras(
                            ejercicio,
                            conjuntoId,
                            predioId,
                            ComparacionHalladoDeclarado.condicion(loDeclarado, loHallado),
                            loDeclarado.area(),
                            loHallado.area(),
                            loDeclarado.uso(),
                            loHallado.uso()));
        }
        return lineas;
    }

    /**
     * Lo que consta declarado, leído de la ficha vigente <b>a la fecha en que se declaró</b>.
     *
     * <p>Sin declaración no hay área ni uso declarados, y eso es lo que hace omiso a alguien. Con
     * declaración, la fecha de la lectura es la de presentación y no la de hoy: es la lectura de la
     * reproducibilidad (RNF-075).
     */
    private ComparacionHalladoDeclarado.LoDeclarado loDeclarado(
            Optional<DeclaracionDelEjercicio> declarada, long predioId) {
        if (declarada.isEmpty()) {
            return ComparacionHalladoDeclarado.LoDeclarado.nada();
        }
        DeclaracionDelEjercicio dj = declarada.get();
        // El area sale de la VERSION de ficha que la declaracion referencia, no de la vigente:
        // eso es lo que el contribuyente declaro. El uso, de la ficha que regia el dia de la
        // presentacion, que es la misma version salvo que el catastro la haya cerrado ese dia.
        Long fichaId = dj.fichaCatastralId();
        AreaM2 area = fichaId == null ? null : fichas.areaDeLaVersion(fichaId).orElse(null);
        String uso =
                caracteristicas
                        .de(predioId, dj.fechaPresentacion())
                        .map(CaracteristicasDelPredio::uso)
                        .orElse(null);
        return new ComparacionHalladoDeclarado.LoDeclarado(true, dj.fueraDePlazo(), area, uso);
    }

    /**
     * La condición de un acta vehicular.
     *
     * <p>Un vehículo no tiene área ni uso que contrastar: lo que hay es el hallazgo que el
     * fiscalizador anotó, y se traslada tal cual. Inventar aquí una comparación sobre el valor
     * referencial sería entrar en D-02a.
     */
    private static CondicionFiscalizada condicionVehicular(ActaFiscalizacion acta) {
        Hallazgo hallazgo = acta.hallazgo();
        if (hallazgo == null) {
            // Hasta #481 esta rama devolvia CONFORME, y era el defecto que D-16 nombraba: un acta
            // sin hallazgo -que el endpoint admitia con 201- se liquidaba como si la visita no
            // hubiera encontrado nada. `RegistrarActaFiscalizacion` ya no deja registrar ninguna;
            // las historicas, si las hay, fallan aqui en vez de mentir.
            throw new ActaSinHallazgo(acta);
        }
        return switch (hallazgo) {
            case CONFORME -> CondicionFiscalizada.CONFORME;
            case OMISO -> CondicionFiscalizada.OMISO;
            case SUBVALUADOR -> CondicionFiscalizada.SUBVALUADOR;
            case NO_UBICADO -> CondicionFiscalizada.NO_UBICADO;
            // Inalcanzable por construccion, y se deja dicho en vez de mapeado: `USO_DISTINTO`
            // exige el uso observado y un acta vehicular no puede consignarlo -las dos reglas
            // estan en el compacto de `ActaFiscalizacion` y otra vez en la base (V76)-. Mapearlo
            // a CondicionFiscalizada.USO_DISTINTO afirmaria que un vehiculo tiene un uso distinto
            // del declarado, y ningun vehiculo declara uso.
            case USO_DISTINTO -> throw new HallazgoQueNoCabeEnUnActaVehicular(acta);
        };
    }

    private void auditar(Liquidacion guardada, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));
    }

    private static String descripcion(Liquidacion liquidacion) {
        return "{\"numero\":\""
                + liquidacion.numero()
                + "\",\"actaId\":"
                + liquidacion.actaId()
                + ",\"version\":"
                + liquidacion.version()
                + ",\"periodo\":\""
                + liquidacion.ejercicioDesde()
                + "-"
                + liquidacion.ejercicioHasta()
                + "\",\"tipo\":\""
                + liquidacion.tipo()
                + "\"}";
    }

    /** No hay ninguna acta de fiscalizacion con ese identificador, o es de otra municipalidad. */
    public static final class ActaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaInexistente(long id) {
            super("No hay ninguna acta de fiscalizacion con identificador " + id);
        }
    }

    /**
     * El acta no dice qué encontró la visita, y sin eso no se puede liquidar.
     *
     * <p>Sólo puede alcanzarla un acta anterior a #481: desde ahí {@code
     * RegistrarActaFiscalizacion} exige el hallazgo. Antes, el nulo se leía como {@code CONFORME} y
     * liquidaba en regla un vehículo que nadie inspeccionó (D-16).
     */
    public static final class ActaSinHallazgo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaSinHallazgo(ActaFiscalizacion acta) {
            super(
                    "El acta "
                            + acta.id()
                            + " no anota ningun hallazgo, y sin el no se puede decir que encontro"
                            + " la visita: leer el nulo como CONFORME seria declararla en regla");
        }
    }

    /**
     * El acta es de un vehiculo y anota un hallazgo que solo cabe en un predio.
     *
     * <p>No la puede alcanzar ningun acta registrada por el sistema: {@link ActaFiscalizacion}
     * rechaza {@code USO_DISTINTO} sin uso observado y rechaza el uso observado en un acta
     * vehicular, y {@code acta_fisc_uso_distinto_ck} lo repite en la base (V76). Existe para que la
     * rama del {@code switch} diga lo que pasa en vez de traducir el hallazgo a una condicion que
     * ningun vehiculo puede tener.
     */
    public static final class HallazgoQueNoCabeEnUnActaVehicular extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        HallazgoQueNoCabeEnUnActaVehicular(ActaFiscalizacion acta) {
            super(
                    "El acta "
                            + acta.id()
                            + " es de un vehiculo y anota USO_DISTINTO: un vehiculo no declara uso,"
                            + " asi que no hay un uso declarado del que difiera");
        }
    }

    /** El acta ya tiene liquidacion: lo que corresponde es reliquidar, no liquidar otra vez. */
    public static final class ActaYaLiquidada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaYaLiquidada(String numero) {
            super(
                    "El acta ya esta liquidada con "
                            + numero
                            + ": corregirla es reliquidar -otra version que la referencia-, no"
                            + " emitir una segunda liquidacion original");
        }
    }
}
