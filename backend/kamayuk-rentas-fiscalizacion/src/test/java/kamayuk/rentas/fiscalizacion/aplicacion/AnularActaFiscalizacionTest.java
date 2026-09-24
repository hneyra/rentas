package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.DeclaracionesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.PadronDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ParametrosDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.nucleo.DeclaracionDelEjercicio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #214 — la única transición del acta, y lo que no deja anular.
 *
 * <p>Sin base de datos: aquí se fija la <b>orquestación</b> —qué se comprueba antes de mover el
 * estado y qué queda auditado—. Que la columna se mueva de verdad, que las tres consultas cambien
 * de respuesta y que el privilegio no deje tocar nada más lo mide {@code
 * ActaFiscalizacionRepositoryJdbcTest} contra PostgreSQL.
 */
@DisplayName("#214 — Anular un acta de fiscalizacion")
class AnularActaFiscalizacionTest {

    private static final Observacion OBSERVACION =
            Observacion.de("La visita se anula: el predio no era ese");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final LocalDate VISITA = LocalDate.of(2026, 3, 1);
    private static final Ejercicio E2025 = new Ejercicio(2025);
    private static final long PREDIO = 20L;
    private static final long CONTRIBUYENTE = 10L;
    private static final long FICHA = 900L;
    private static final long CONJUNTO_2025 = 42L;

    private ActasEnMemoria actas;
    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ResolucionesEnMemoria resoluciones;
    private List<RegistroDeAuditoria> auditados;
    private AnularActaFiscalizacion anular;
    private LiquidarFiscalizacion liquidar;
    private long actaId;

    @BeforeEach
    void armar() {
        actas = new ActasEnMemoria();
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        resoluciones = new ResolucionesEnMemoria();
        auditados = new ArrayList<>();

        PadronDeMentira catastro =
                new PadronDeMentira()
                        .conFicha(FICHA, AreaM2.de("120.00"))
                        .conCaracteristicas(PREDIO, "CASA_HABITACION", AreaM2.de("300.00"), FICHA);
        DeclaracionesDeMentira rentas =
                new DeclaracionesDeMentira()
                        .con(
                                PREDIO,
                                new DeclaracionDelEjercicio(
                                        1L,
                                        "DJ-0001",
                                        E2025,
                                        CONTRIBUYENTE,
                                        LocalDate.of(2025, 2, 20),
                                        false,
                                        FICHA));

        liquidar =
                new LiquidarFiscalizacion(
                        actas,
                        liquidaciones,
                        movimientos,
                        new ParametrosDeMentira().sellar(2025, CONJUNTO_2025, 1),
                        catastro,
                        catastro,
                        rentas,
                        registro -> {},
                        Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        anular =
                new AnularActaFiscalizacion(
                        actas,
                        liquidaciones,
                        movimientos,
                        resoluciones,
                        registro -> auditados.add(registro));

        actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                FICHA,
                                VISITA,
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                "ampliacion no declarada",
                                Observacion.de("siembra")));
    }

    @Test
    @DisplayName("un acta sin liquidacion se anula, y el acto queda en la auditoria")
    void unActaSinLiquidacionSeAnula() {
        ActaFiscalizacion anulada = anular.anular(actaId, HOY, OBSERVACION).acta();

        assertThat(anulada.estado()).isEqualTo(EstadoDeActa.ANULADA);
        assertThat(actas.findById(actaId).orElseThrow().estado())
                .as("la anulacion se guarda: no es un objeto que se queda en la mano")
                .isEqualTo(EstadoDeActa.ANULADA);

        assertThat(auditados).hasSize(1);
        RegistroDeAuditoria registro = auditados.get(0);
        assertThat(registro.operacion()).isEqualTo(Operacion.MODIFICACION);
        assertThat(registro.tabla()).isEqualTo("acta_fiscalizacion");
        assertThat(registro.observacion()).isEqualTo(OBSERVACION);
        assertThat(registro.datosAnteriores())
                .as("regla 10 y RNF-052: la auditoria dice de que estado venia")
                .contains("ABIERTA");
        assertThat(registro.datosNuevos()).contains("ANULADA");
    }

    @Test
    @DisplayName("una anulada no revive: anularla otra vez es una transicion ilegal")
    void unaAnuladaNoRevive() {
        anular.anular(actaId, HOY, OBSERVACION);

        assertThatThrownBy(() -> anular.anular(actaId, HOY, OBSERVACION))
                .isInstanceOf(ActaFiscalizacion.TransicionIlegal.class)
                .hasMessageContaining("no revive");
    }

    @Test
    @DisplayName("un acta que sostiene una liquidacion VIVA no se anula: primero la liquidacion")
    void unActaConLiquidacionVivaNoSeAnula() {
        liquidar.liquidar(
                actaId,
                E2025,
                E2025,
                TipoDeFiscalizacion.CIERTA,
                "Subvaluacion detectada",
                HOY,
                OBSERVACION);

        assertThatThrownBy(() -> anular.anular(actaId, HOY, OBSERVACION))
                .as(
                        "anularla dejaria la liquidacion —y lo que de ella salio— sostenida por una"
                                + " visita que no vale")
                .isInstanceOf(AnularActaFiscalizacion.ActaConLiquidacionViva.class)
                .hasMessageContaining("primero se anula la liquidacion");

        assertThat(actas.findById(actaId).orElseThrow().estado())
                .as("y no se escribio nada: la comprobacion va ANTES de mover la columna")
                .isEqualTo(EstadoDeActa.ABIERTA);
    }

    @Test
    @DisplayName("y con la liquidacion ya anulada si se anula: es el orden de la regla 4")
    void conLaLiquidacionAnuladaSiSeAnula() {
        Liquidacion emitida =
                liquidar.liquidar(
                        actaId,
                        E2025,
                        E2025,
                        TipoDeFiscalizacion.CIERTA,
                        "Subvaluacion detectada",
                        HOY,
                        OBSERVACION);
        movimientos.insertar(
                MovimientoDeLiquidacion.cambioDeEstado(
                        emitida.identificador(),
                        EstadoDeLiquidacion.ANULADA,
                        HOY,
                        "Se deja sin efecto",
                        OBSERVACION));

        ActaFiscalizacion anulada = anular.anular(actaId, HOY, OBSERVACION).acta();

        assertThat(anulada.estado()).isEqualTo(EstadoDeActa.ANULADA);
    }

    @Test
    @DisplayName("#338 — con la liquidacion anulada pero su RDF en pie, la visita NO se anula: 409")
    void conLaLiquidacionAnuladaYSuResolucionEnPieNoSeAnula() {
        Liquidacion emitida =
                liquidar.liquidar(
                        actaId,
                        E2025,
                        E2025,
                        TipoDeFiscalizacion.CIERTA,
                        "Subvaluacion detectada",
                        HOY,
                        OBSERVACION);
        // La liquidacion se transfirio —su resolucion de determinacion existe— y DESPUES llego a
        // ANULADA, que es lo que hasta #338 admitia CambiarEstadoDeLaLiquidacion. Se escribe el
        // movimiento directo porque ese camino ya no existe: lo que se prueba es que una que ya
        // quedo asi no habilite anular su visita.
        resoluciones.registrar(
                ResolucionDeDeterminacion.predial(
                        "RDF-2026-000004",
                        1L,
                        emitida.identificador(),
                        CONTRIBUYENTE,
                        PREDIO,
                        FICHA,
                        FICHA + 1,
                        HOY,
                        "INFORME 12-2026",
                        "Subvaluacion detectada",
                        "TUO LTM art. 14",
                        OBSERVACION));
        movimientos.insertar(
                MovimientoDeLiquidacion.cambioDeEstado(
                        emitida.identificador(),
                        EstadoDeLiquidacion.ANULADA,
                        HOY,
                        "Se deja sin efecto",
                        OBSERVACION));

        assertThatThrownBy(() -> anular.anular(actaId, HOY, OBSERVACION))
                .as(
                        "anular la liquidacion no toca la resolucion: la visita seguiria"
                                + " sosteniendo un acto vigente")
                .isInstanceOf(AnularActaFiscalizacion.ActaConResolucionEnPie.class)
                .hasMessageContaining("RDF-2026-000004")
                .hasMessageContaining(emitida.numero());

        assertThat(actas.findById(actaId).orElseThrow().estado())
                .as("y no se escribio nada")
                .isEqualTo(EstadoDeActa.ABIERTA);
        assertThat(auditados).isEmpty();
    }

    @Test
    @DisplayName("un acta que no existe no se anula en silencio")
    void unActaQueNoExiste() {
        assertThatThrownBy(() -> anular.anular(9999L, HOY, OBSERVACION))
                .isInstanceOf(LiquidarFiscalizacion.ActaInexistente.class);
    }
}
