package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaConLoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja sin efecto una visita: la <b>única</b> transición que este sistema escribe sobre un acta
 * (#214).
 *
 * <h2>Por qué hacía falta, y por qué no hacían falta las otras tres</h2>
 *
 * <p>{@code EstadoDeActa} declaraba cinco valores y este sistema escribía uno, así que {@code
 * ANULADA} era inalcanzable y las tres consultas que la miran —{@code prediosConActaEnElPrograma},
 * {@code prediosConActaEnElEjercicio} y {@code unidadesConActaViva}— filtraban por un valor que
 * ninguna fila podía tener. La exclusión de #481 —«no vuelvas a sortear un predio ya fiscalizado,
 * salvo que su acta se anulara»— era incondicional: la salvedad no existía.
 *
 * <p>Las otras tres —liquidada, reliquidada, transferida— <b>se derivan</b> y no se escriben:
 * {@link kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa} explica por qué, y V19 lo dejó escrito
 * en el {@code CHECK} y en el privilegio.
 *
 * <h2>Un acta con liquidación viva no se anula</h2>
 *
 * <p>Y esto es lo que hace que la derivación sea <b>de carga</b> y no prosa: anular una visita cuyo
 * contraste ya se liquidó dejaría una liquidación —y quizá una resolución y unos cargos en la
 * cuenta corriente— sostenida por una visita que no vale, con el papel ya en manos del
 * contribuyente. La regla 4 dice cómo se sale de ahí: primero se anula la liquidación ({@code
 * CambiarEstadoDeLaLiquidacion}), que es un movimiento de su historial, y después la visita.
 *
 * <h2>Y una liquidación transferida no se anula (#338)</h2>
 *
 * <p>Ese orden sólo vale para una liquidación <b>sin resolución de determinación</b>. Hasta #338
 * este javadoc daba por cerrada cualquier liquidación ANULADA, y la premisa era falsa: anularla no
 * toca la resolución —{@code resolucion_determinacion} no admite {@code UPDATE} ni tiene estado— ni
 * los cargos que asentó. Así se llegaba a una RDF vigente y descargable sostenida por una
 * liquidación anulada y por una visita anulada.
 *
 * <p>Desde #338 {@code CambiarEstadoDeLaLiquidacion} no deja anular una liquidación con RDF, y aquí
 * se comprueba lo mismo ({@link ActaConResolucionEnPie}): sin esta segunda comprobación, una
 * liquidación que llegó a ANULADA con su RDF antes de #338 seguiría habilitando que se anule su
 * visita. Con resolución, el orden es otro: primero se deja sin efecto la RDF —un acto que todavía
 * no existe—, después la liquidación y al final la visita.
 *
 * <p>Se comprueba sobre la <b>última</b> versión, que es la que está en pie: una reliquidación
 * anterior anulada no revive nada.
 *
 * <h2>No borra nada</h2>
 *
 * <p>Regla 4: el acta no se borra ni se edita. Sigue leyéndose entera —quién fue, qué día, qué
 * midió—, y lo único que cambia es que deja de contar. Corregir lo que se midió es <b>otra
 * visita</b>: otra versión sobre la misma unidad, que {@code acta_fisc_version_uq} admite.
 */
@Service
public class AnularActaFiscalizacion {

    private static final String TABLA_AUDITADA = "acta_fiscalizacion";

    private final ActaFiscalizacionRepository actas;
    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;
    private final ResolucionDeDeterminacionRepository resoluciones;
    private final Auditoria auditoria;

    public AnularActaFiscalizacion(
            ActaFiscalizacionRepository actas,
            LiquidacionRepository liquidaciones,
            MovimientoDeLiquidacionRepository movimientos,
            ResolucionDeDeterminacionRepository resoluciones,
            Auditoria auditoria) {
        this.actas = actas;
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
        this.resoluciones = resoluciones;
        this.auditoria = auditoria;
    }

    /**
     * Anula el acta.
     *
     * @param actaId cuál se anula
     * @param fecha el día del acto, no el de su registro (regla 9)
     * @param observacion por qué se anula (regla 10, RNF-052)
     * @throws LiquidarFiscalizacion.ActaInexistente si no hay ninguna con ese identificador
     * @throws ActaConLiquidacionViva si su contraste ya se liquidó y la liquidación sigue en pie
     * @throws ActaConResolucionEnPie si la liquidación está anulada pero su RDF sigue vigente
     * @throws ActaFiscalizacion.TransicionIlegal si ya estaba anulada
     */
    @Transactional
    public ActaConLoDeclarado anular(long actaId, LocalDate fecha, Observacion observacion) {
        ActaFiscalizacion antes =
                actas.findById(actaId)
                        .orElseThrow(() -> new LiquidarFiscalizacion.ActaInexistente(actaId));

        exigirQueNadaLaSostenga(actaId);

        ActaFiscalizacion anulada = actas.anular(actaId);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                TABLA_AUDITADA,
                                String.valueOf(actaId),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(antes), descripcion(anulada)));

        return conLoDeclarado(anulada);
    }

    // ------------------------------------------------------------------

    private void exigirQueNadaLaSostenga(long actaId) {
        Liquidacion ultima = liquidaciones.ultimaVersionDeActa(actaId).orElse(null);
        if (ultima == null) {
            return;
        }
        EstadoDeLiquidacion estado =
                EstadoDeLiquidacion.delHistorial(movimientos.deLiquidacion(ultima.identificador()));
        if (!estado.estaCerrada()) {
            throw new ActaConLiquidacionViva(actaId, ultima.numero(), estado);
        }
        resoluciones
                .deLiquidacion(ultima.identificador())
                .ifPresent(
                        resolucion -> {
                            throw new ActaConResolucionEnPie(
                                    actaId, ultima.numero(), resolucion.numero());
                        });
    }

    /**
     * El acta con su lado declarado, igual que la devuelve el {@code POST} que la registra (#191).
     *
     * <p>Devolver el acta desnuda publicaria {@code areaDeclarada} y {@code usoDeclarado} en nulo
     * <b>siempre</b>, con el mismo contrato que la lectura de al lado y sin que nada lo dijera —el
     * modo de fallo que #194 midio—.
     */
    private ActaConLoDeclarado conLoDeclarado(ActaFiscalizacion acta) {
        Long fichaId = acta.fichaId();
        return fichaId == null
                ? ActaConLoDeclarado.sinLadoDeclarado(acta)
                : ActaConLoDeclarado.de(
                        acta, actas.loDeclaradoPorFicha(java.util.Set.of(fichaId)).get(fichaId));
    }

    private static String descripcion(ActaFiscalizacion acta) {
        return "{\"estado\":\"" + acta.estado() + "\"}";
    }

    /**
     * El acta tiene una liquidacion que sigue en pie: anularla dejaria esa liquidacion —y lo que de
     * ella salio— sostenida por una visita que no vale.
     */
    public static final class ActaConLiquidacionViva extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaConLiquidacionViva(long actaId, String numero, EstadoDeLiquidacion estado) {
            super(
                    "El acta "
                            + actaId
                            + " sostiene la liquidacion "
                            + numero
                            + ", que esta "
                            + estado.etiqueta()
                            + ": primero se anula la liquidacion y despues la visita, o quedaria"
                            + " determinada de oficio una diferencia que ya no sostiene nadie");
        }
    }

    /**
     * La liquidacion de la visita esta anulada, pero su resolucion de determinacion sigue vigente
     * (#338): anular la visita dejaria esa RDF sostenida por nada.
     */
    public static final class ActaConResolucionEnPie extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaConResolucionEnPie(long actaId, String liquidacion, String resolucion) {
            super(
                    "El acta "
                            + actaId
                            + " sostiene la liquidacion "
                            + liquidacion
                            + ", anulada pero transferida con la resolucion de determinacion "
                            + resolucion
                            + ": anular la liquidacion no dejo sin efecto esa resolucion, y hasta"
                            + " que se deje no se anula la visita que la sustenta");
        }
    }
}
