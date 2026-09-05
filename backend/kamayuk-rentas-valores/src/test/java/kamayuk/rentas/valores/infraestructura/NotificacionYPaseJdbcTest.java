package kamayuk.rentas.valores.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.ValoresSinNotificar;
import kamayuk.rentas.valores.aplicacion.ValoresSinNotificarValores;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import kamayuk.rentas.valores.dominio.SituacionDelValor;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #39 — Notificacion, pase a coactiva y prescripcion contra PostgreSQL de verdad (V28), conectado
 * como {@code kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li>Que el pase a coactiva sea idempotente <b>bajo concurrencia real</b>. Un doble que consulta
 *       antes de insertar pasa la prueba y falla en produccion: dos peticiones simultaneas pasan
 *       las dos por el {@code if}. Aqui se lanzan diez hilos a la vez.
 *   <li>Que {@code kamayuk_app} <b>no tenga</b> el privilegio de actualizar una notificacion ni un
 *       movimiento. No es una convencion: es un {@code REVOKE} de V28, y se comprueba intentandolo.
 *   <li>Que la restriccion de exigibilidad de la base rechace una diligencia no hallada con fecha
 *       de exigibilidad, aunque alguien la escriba por SQL directo.
 * </ul>
 */
@DisplayName("#39 — Notificacion, pase y prescripcion contra PostgreSQL")
class NotificacionYPaseJdbcTest {

    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 4, 3);
    private static final LocalDate EXIGIBLE = LocalDate.of(2026, 5, 5);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long conjuntoId;
    private static TransactionTemplate transaccion;
    private static ValorRepositoryJdbc valores;
    private static NotificacionRepositoryJdbc notificaciones;
    private static MovimientoDeValorRepositoryJdbc movimientos;
    private static PrescripcionRepositoryJdbc prescripciones;
    private static DriverManagerDataSource pool;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230301", "Municipalidad de notificaciones");
        conjuntoId = crearConjuntoSellado(municipalidad);

        pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        valores = new ValorRepositoryJdbc(jdbc);
        notificaciones = new NotificacionRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeValorRepositoryJdbc(jdbc);
        prescripciones = new PrescripcionRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("ventanilla.valores", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("La notificacion y su reintento")
    class DeLaNotificacion {

        @Test
        @DisplayName("dos diligencias del mismo valor conviven: el reintento no borra la anterior")
        void elReintentoNoBorraLaAnterior() {
            Valor valor = emitir("N-0001", "OP-2026-N00001");

            enTransaccion(
                    () -> notificaciones.insertar(noHallada(valor.id(), "OP-2026-N00001/1", 1)));
            Notificacion segunda =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-N00001/2", 2)));

            List<Notificacion> todas = enTransaccion(() -> notificaciones.deValor(valor.id()));

            assertThat(todas).hasSize(2);
            assertThat(todas.get(0).resultado()).isEqualTo(ResultadoDeNotificacion.NO_UBICADO);
            assertThat(todas.get(0).exigibleDesde()).isNull();
            assertThat(todas.get(1).id()).isEqualTo(segunda.id());
            assertThat(enTransaccion(() -> notificaciones.intentosDe(valor.id()))).isEqualTo(2);
        }

        @Test
        @DisplayName("queSurtioEfecto devuelve la primera que lo hizo, no la ultima")
        void devuelveLaPrimeraQueSurtioEfecto() {
            Valor valor = emitir("N-0002", "OP-2026-N00002");

            Notificacion primera =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-N00002/1", 1)));
            enTransaccion(
                    () -> notificaciones.insertar(notificada(valor.id(), "OP-2026-N00002/2", 2)));

            assertThat(
                            enTransaccion(() -> notificaciones.queSurtioEfecto(valor.id()))
                                    .orElseThrow()
                                    .id())
                    .isEqualTo(primera.id());
        }

        @Test
        @DisplayName("el mismo intento dos veces lo rechaza la base, no la aplicacion")
        void elMismoIntentoDosVecesSeRechaza() {
            Valor valor = emitir("N-0003", "OP-2026-N00003");

            enTransaccion(
                    () -> notificaciones.insertar(noHallada(valor.id(), "OP-2026-N00003/1", 1)));

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    notificaciones.insertar(
                                                            noHallada(
                                                                    valor.id(),
                                                                    "OP-2026-N00003/1-bis",
                                                                    1))))
                    .hasMessageContaining("notificacion_intento_uq");
        }

        @Test
        @DisplayName(
                "una diligencia no hallada con exigibilidad la rechaza la base por SQL directo")
        void laBaseRechazaLaExigibilidadSinEfecto() {
            Valor valor = emitir("N-0004", "OP-2026-N00004");

            // Escrito por SQL directo, saltandose el dominio: es la comprobacion de que la
            // restriccion existe en la base y no solo en el constructor del record.
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            insertarNotificacionCruda(
                                                    valor.id(),
                                                    "OP-2026-N00004/9",
                                                    9,
                                                    "NO_UBICADO",
                                                    EXIGIBLE)))
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("kamayuk_app no puede actualizar una notificacion: el privilegio no existe")
        void noSePuedeActualizarUnaNotificacion() {
            Valor valor = emitir("N-0005", "OP-2026-N00005");
            Notificacion guardada =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            noHallada(valor.id(), "OP-2026-N00005/1", 1)));

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE notificacion SET resultado ="
                                                            + " 'NOTIFICADO' WHERE id = "
                                                            + guardada.id())))
                    .isEqualTo("42501");
        }
    }

    @Nested
    @DisplayName("El pase a coactiva")
    class DelPase {

        @Test
        @DisplayName("diez pases simultaneos del mismo valor producen UN movimiento, no diez")
        void diezPasesSimultaneosProducenUno() throws Exception {
            Valor valor = emitir("P-0001", "OP-2026-P00001");
            Notificacion notificacion =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-P00001/1", 1)));

            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Long>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                tareas.add(
                        () -> {
                            // El origen y el contexto de tenant son ThreadLocal: cada hilo del
                            // pool empieza sin ellos, igual que empezaria una peticion.
                            OrigenContext.fijar(new Origen("ventanilla.valores", null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            return enTransaccion(
                                            () ->
                                                    movimientos.registrarPase(
                                                            pase(valor.id(), notificacion.id())))
                                    .id();
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            try {
                List<Future<Long>> futuros = new ArrayList<>();
                for (Callable<Long> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                List<Long> ids = new ArrayList<>();
                for (Future<Long> futuro : futuros) {
                    ids.add(futuro.get(30, TimeUnit.SECONDS));
                }
                // Todos devuelven el mismo movimiento: el indice unico parcial serializa en el
                // motor lo que ningun `if` de Java podria serializar.
                assertThat(ids).containsOnly(ids.get(0));
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(enTransaccion(() -> movimientos.deValor(valor.id()))).hasSize(1);
        }

        @Test
        @DisplayName("el pase copia de que diligencia salio y desde cuando era exigible")
        void elPaseCopiaSuSustento() {
            Valor valor = emitir("P-0002", "OP-2026-P00002");
            Notificacion notificacion =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-P00002/1", 1)));

            MovimientoDeValor guardado =
                    enTransaccion(
                            () -> movimientos.registrarPase(pase(valor.id(), notificacion.id())));

            assertThat(guardado.notificacionId()).isEqualTo(notificacion.id());
            assertThat(guardado.exigibleDesde()).isEqualTo(EXIGIBLE);
            assertThat(guardado.usuarioRegistro()).isEqualTo("ventanilla.valores");
        }

        @Test
        @DisplayName("la base rechaza un pase anterior a la exigibilidad")
        void laBaseRechazaUnPaseAnticipado() {
            Valor valor = emitir("P-0003", "OP-2026-P00003");
            Notificacion notificacion =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-P00003/1", 1)));

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO valor_movimiento"
                                                            + " (municipalidad_id, valor_id, tipo,"
                                                            + " fecha, notificacion_id, exigible_desde,"
                                                            + " usuario_registro, observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + valor.id()
                                                            + ", 'PCO', DATE '2026-04-10', "
                                                            + notificacion.id()
                                                            + ", DATE '2026-05-05', 'x', 'y')")))
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("kamayuk_app no puede borrar un movimiento: el privilegio no existe")
        void noSePuedeBorrarUnMovimiento() {
            Valor valor = emitir("P-0004", "OP-2026-P00004");
            Notificacion notificacion =
                    enTransaccion(
                            () ->
                                    notificaciones.insertar(
                                            notificada(valor.id(), "OP-2026-P00004/1", 1)));
            MovimientoDeValor guardado =
                    enTransaccion(
                            () -> movimientos.registrarPase(pase(valor.id(), notificacion.id())));

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM valor_movimiento WHERE id = "
                                                            + guardado.id())))
                    .isEqualTo("42501");
        }
    }

    @Nested
    @DisplayName("La prescripcion")
    class DeLaPrescripcion {

        @Test
        @DisplayName("se guarda con su computo y sus hechos, y se relee identica")
        void seGuardaConSuComputoYSusHechos() {
            long contribuyente = crearContribuyente("PR-0001", "50300011");
            HechoDelComputo interrupcion =
                    HechoDelComputo.interrupcion(
                            "pago parcial de la deuda", LocalDate.of(2022, 5, 5));
            HechoDelComputo suspension =
                    HechoDelComputo.suspension(
                            "procedimiento contencioso tributario",
                            LocalDate.of(2023, 1, 1),
                            LocalDate.of(2023, 6, 1));

            Prescripcion guardada =
                    enTransaccion(
                            () ->
                                    prescripciones.insertar(
                                            new Prescripcion(
                                                    null,
                                                    contribuyente,
                                                    "PREDIAL",
                                                    new Ejercicio(2018),
                                                    new Ejercicio(2019),
                                                    LocalDate.of(2026, 6, 1),
                                                    CausalDePrescripcion.DECLARACION_PRESENTADA,
                                                    Plazo.de("4 ANIOS"),
                                                    conjuntoId,
                                                    ResultadoDeLaSolicitud.PROCEDE_EN_PARTE,
                                                    "RES-2026-001",
                                                    List.of(
                                                            computo(2018, true),
                                                            computo(2019, false)),
                                                    List.of(interrupcion, suspension),
                                                    null,
                                                    Observacion.de("Se resuelve la solicitud"))));

            assertThat(guardada.id()).isNotNull();
            Prescripcion releida =
                    enTransaccion(() -> prescripciones.porId(guardada.id()).orElseThrow());

            assertThat(releida.ejercicios()).hasSize(2);
            assertThat(releida.ejercicios().get(0).prescrita()).isTrue();
            assertThat(releida.ejercicios().get(1).prescrita()).isFalse();
            assertThat(releida.hechos()).containsExactly(interrupcion, suspension);
            assertThat(releida.plazo()).isEqualTo(Plazo.de("4 ANIOS"));
            assertThat(releida.usuarioRegistro()).isEqualTo("ventanilla.valores");
        }

        @Test
        @DisplayName("marcar PRESCRITO no toca el libro de asientos ni el desglose congelado")
        void marcarNoTocaElLibroNiElDesglose() {
            Valor valor = emitir("PR-0002", "OP-2026-PR0002");

            Valor prescrito =
                    enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.PRESCRITO));

            assertThat(prescrito.estado()).isEqualTo(EstadoDeValor.PRESCRITO);
            assertThat(prescrito.total()).isEqualTo(valor.total());
            assertThat(enTransaccion(() -> valores.detalleDe(valor.id()))).hasSize(1);
            // El valor sigue existiendo: prescribir no borro nada.
            assertThat(enTransaccion(() -> valores.porId(valor.id()))).isPresent();
        }

        @Test
        @DisplayName("cobrablesDe no devuelve lo ya prescrito, pagado ni anulado")
        void cobrablesDeFiltraPorEstado() {
            Valor valor = emitir("PR-0003", "OP-2026-PR0003");

            assertThat(
                            enTransaccion(
                                    () ->
                                            valores.cobrablesDe(
                                                    valor.contribuyenteId(),
                                                    "predial",
                                                    new Ejercicio(2026))))
                    .extracting(Valor::numero)
                    .contains("OP-2026-PR0003");

            enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.PRESCRITO));

            assertThat(
                            enTransaccion(
                                    () ->
                                            valores.cobrablesDe(
                                                    valor.contribuyenteId(),
                                                    "PREDIAL",
                                                    new Ejercicio(2026))))
                    .isEmpty();
        }

        @Test
        @DisplayName("kamayuk_app no puede borrar una prescripcion: el privilegio no existe")
        void noSePuedeBorrarUnaPrescripcion() {
            assertThat(estadoSqlDelFallo(() -> ejecutarComoApp("DELETE FROM prescripcion")))
                    .isEqualTo("42501");
        }
    }

    /**
     * La grilla de {@code consulta_valores} (RF-041, #25), contra la base.
     *
     * <p>Lo que solo se puede verificar aqui: que la columna «Estado» que se pinta y el filtro que
     * trae la fila digan lo mismo. Son <b>dos escrituras de la misma regla</b> —{@code
     * SituacionDelValor#de} en Java, {@code condicionDe} en SQL—, y si divergen la pantalla muestra
     * filas cuyo estado no coincide con el filtro que las trajo. Ningun doble puede detectarlo,
     * porque en un doble solo hay una de las dos.
     */
    @Nested
    @DisplayName("La grilla de consulta_valores")
    class LaGrillaDeConsultaValores {

        /** Despues de {@code EXIGIBLE} (5 de mayo): el plazo ya vencio. */
        private static final LocalDate DESPUES_DEL_PLAZO = LocalDate.of(2026, 5, 20);

        /** Antes de {@code EXIGIBLE}: el plazo todavia corre. */
        private static final LocalDate DENTRO_DEL_PLAZO = LocalDate.of(2026, 4, 10);

        @Test
        @DisplayName("la fila trae el tributo y los ejercicios del detalle, agregados por la base")
        void laFilaTraeElDetalleAgregado() {
            Valor valor = emitir("CV-0001", "OP-2026-CV0001");

            ValorEnConsulta fila = unaFila(valor.numero(), DESPUES_DEL_PLAZO);

            assertThat(fila.tributos()).isEqualTo("PREDIAL");
            assertThat(fila.ejercicioDesde()).isEqualTo(2026);
            assertThat(fila.periodo())
                    .as("un solo ejercicio se pinta como el ano, no como «2026 — 2026»")
                    .isEqualTo("2026");
        }

        @Test
        @DisplayName("sin notificar, la situacion es EMITIDO y no hay fechas que mostrar")
        void sinNotificarEsEmitido() {
            Valor valor = emitir("CV-0002", "OP-2026-CV0002");

            ValorEnConsulta fila = unaFila(valor.numero(), DESPUES_DEL_PLAZO);

            assertThat(fila.situacion()).isEqualTo(SituacionDelValor.EMITIDO);
            assertThat(fila.notificadoEl()).isNull();
            assertThat(fila.exigibleDesde())
                    .as("la pantalla pinta un guion, que no es una fecha")
                    .isNull();
        }

        @Test
        @DisplayName("notificado: la misma fila es NOTIFICADO dentro del plazo y EXIGIBLE despues")
        void laMismaFilaCambiaConLaFecha() {
            Valor valor = emitir("CV-0003", "OP-2026-CV0003");
            notificarConAcuse(valor, "OP-2026-CV0003/1");
            enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.NOTIFICADO));

            ValorEnConsulta dentro = unaFila(valor.numero(), DENTRO_DEL_PLAZO);
            ValorEnConsulta despues = unaFila(valor.numero(), DESPUES_DEL_PLAZO);

            assertThat(dentro.situacion()).isEqualTo(SituacionDelValor.NOTIFICADO);
            assertThat(despues.situacion())
                    .as(
                            "ninguna fila cambio entre las dos consultas: lo que cambio es el dia"
                                    + " desde el que se mira (regla 9)")
                    .isEqualTo(SituacionDelValor.EXIGIBLE);
            assertThat(despues.notificadoEl()).isEqualTo(DILIGENCIA);
            assertThat(despues.exigibleDesde()).isEqualTo(EXIGIBLE);
        }

        @Test
        @DisplayName("la diligencia que cuenta es la primera que surtio efecto, no la ultima")
        void laDiligenciaQueCuentaEsLaPrimera() {
            Valor valor = emitir("CV-0004", "OP-2026-CV0004");
            enTransaccion(
                    () -> notificaciones.insertar(noHallada(valor.id(), "OP-2026-CV0004/1", 1)));
            enTransaccion(
                    () -> notificaciones.insertar(notificada(valor.id(), "OP-2026-CV0004/2", 2)));
            enTransaccion(
                    () -> notificaciones.insertar(notificada(valor.id(), "OP-2026-CV0004/3", 3)));

            ValorEnConsulta fila = unaFila(valor.numero(), DESPUES_DEL_PLAZO);

            assertThat(fila.exigibleDesde())
                    .as(
                            "el intento 1 no surtio efecto y no aporta fecha; entre el 2 y el 3, el"
                                    + " plazo empezo con el 2")
                    .isEqualTo(EXIGIBLE);
        }

        @Test
        @DisplayName(
                "con el pase registrado, la situacion es COACTIVA aunque el plazo hubiera"
                        + " vencido")
        void conPaseEsCoactiva() {
            Valor valor = emitir("CV-0005", "OP-2026-CV0005");
            Notificacion diligencia = notificarConAcuse(valor, "OP-2026-CV0005/1");
            enTransaccion(() -> movimientos.registrarPase(pase(valor.id(), diligencia.id())));

            ValorEnConsulta fila = unaFila(valor.numero(), DESPUES_DEL_PLAZO);

            assertThat(fila.enCoactiva()).isTrue();
            assertThat(fila.situacion())
                    .as(
                            "la pantalla distingue lo que se puede cobrar de lo que ya se esta cobrando")
                    .isEqualTo(SituacionDelValor.COACTIVA);
        }

        @Test
        @DisplayName("el filtro por situacion trae exactamente las filas que la pintan igual")
        void elFiltroCoincideConLaColumna() {
            Valor emitido = emitir("CV-0011", "OP-2026-CV0011");
            Valor notificado = emitir("CV-0012", "OP-2026-CV0012");
            notificarConAcuse(notificado, "OP-2026-CV0012/1");
            enTransaccion(() -> valores.cambiarEstado(notificado.id(), EstadoDeValor.NOTIFICADO));
            Valor enCoactiva = emitir("CV-0013", "OP-2026-CV0013");
            Notificacion suya = notificarConAcuse(enCoactiva, "OP-2026-CV0013/1");
            enTransaccion(() -> movimientos.registrarPase(pase(enCoactiva.id(), suya.id())));
            Valor anulado = emitir("CV-0014", "OP-2026-CV0014");
            enTransaccion(() -> valores.cambiarEstado(anulado.id(), EstadoDeValor.ANULADO));

            for (SituacionDelValor situacion : SituacionDelValor.values()) {
                List<ValorEnConsulta> filas = filtrar(situacion, DESPUES_DEL_PLAZO);
                assertThat(filas)
                        .as(
                                "la condicion SQL y SituacionDelValor#de son dos escrituras de la"
                                        + " misma regla: si divergen, la fila muestra un estado que"
                                        + " no es el del filtro que la trajo — situacion "
                                        + situacion)
                        .allSatisfy(fila -> assertThat(fila.situacion()).isEqualTo(situacion));
            }

            assertThat(numerosDe(filtrar(SituacionDelValor.EXIGIBLE, DESPUES_DEL_PLAZO)))
                    .contains(notificado.numero())
                    .doesNotContain(emitido.numero(), enCoactiva.numero(), anulado.numero());
            assertThat(numerosDe(filtrar(SituacionDelValor.NOTIFICADO, DENTRO_DEL_PLAZO)))
                    .as("dentro del plazo el mismo valor cae en el otro cajon")
                    .contains(notificado.numero());
            assertThat(numerosDe(filtrar(SituacionDelValor.COACTIVA, DESPUES_DEL_PLAZO)))
                    .contains(enCoactiva.numero());
            assertThat(numerosDe(filtrar(SituacionDelValor.ANULADO, DESPUES_DEL_PLAZO)))
                    .contains(anulado.numero());
        }

        @Test
        @DisplayName("el filtro por situacion se aplica en SQL: el total no cuenta lo que descarta")
        void elFiltroSeAplicaEnSql() {
            Valor emitido = emitir("CV-0021", "OP-2026-CV0021");
            Valor anulado = emitirPara(emitido.contribuyenteId(), "OP-2026-CV0022");
            enTransaccion(() -> valores.cambiarEstado(anulado.id(), EstadoDeValor.ANULADO));

            Pagina<ValorEnConsulta> soloAnulados =
                    enTransaccion(
                            () ->
                                    valores.consultar(
                                            new CriterioDeConsultaDeValores(
                                                    null,
                                                    anulado.contribuyenteId(),
                                                    null,
                                                    null,
                                                    SituacionDelValor.ANULADO,
                                                    DESPUES_DEL_PLAZO),
                                            unaPagina()));

            assertThat(soloAnulados.totalElementos())
                    .as(
                            "filtrar en Java las filas que la base ya devolvio daria un total sin"
                                    + " filtrar —«1 de 47» sobre 47— y paginas incompletas")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(
                "filtrar por numero y por tipo acota; ordenar por una columna no declarada es"
                        + " 422")
        void filtrarPorNumeroYTipo() {
            Valor valor = emitir("CV-0031", "OP-2026-CV0031");

            assertThat(
                            numerosDe(
                                    enTransaccion(
                                                    () ->
                                                            valores.consultar(
                                                                    new CriterioDeConsultaDeValores(
                                                                            valor.numero(),
                                                                            null,
                                                                            TipoValor.ORDEN_DE_PAGO,
                                                                            2026,
                                                                            null,
                                                                            DESPUES_DEL_PLAZO),
                                                                    unaPagina()))
                                            .contenido()))
                    .containsExactly(valor.numero());

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    valores.consultar(
                                                            CriterioDeConsultaDeValores.a(
                                                                    DESPUES_DEL_PLAZO),
                                                            new Paginacion(
                                                                    0,
                                                                    20,
                                                                    "observacion",
                                                                    Paginacion.Direccion
                                                                            .ASCENDENTE))))
                    .as("el nombre de columna no se puede parametrizar en un ORDER BY")
                    .isInstanceOf(OrdenSeguro.OrdenNoAdmitido.class);
        }

        // --------------------------------------------------------------

        private ValorEnConsulta unaFila(String numero, LocalDate fecha) {
            return enTransaccion(
                            () ->
                                    valores.consultar(
                                            new CriterioDeConsultaDeValores(
                                                    numero, null, null, null, null, fecha),
                                            unaPagina()))
                    .contenido()
                    .get(0);
        }

        private List<ValorEnConsulta> filtrar(SituacionDelValor situacion, LocalDate fecha) {
            return enTransaccion(
                            () ->
                                    valores.consultar(
                                            new CriterioDeConsultaDeValores(
                                                    null, null, null, null, situacion, fecha),
                                            unaPagina()))
                    .contenido();
        }

        private List<String> numerosDe(List<ValorEnConsulta> filas) {
            return filas.stream().map(fila -> fila.valor().numero()).toList();
        }

        private Notificacion notificarConAcuse(Valor valor, String numero) {
            return enTransaccion(() -> notificaciones.insertar(notificada(valor.id(), numero, 1)));
        }

        private Paginacion unaPagina() {
            return new Paginacion(0, 200, "numero", Paginacion.Direccion.ASCENDENTE);
        }
    }

    @Nested
    @DisplayName("#549 — El frente de Valores: emitidos y sin notificar")
    class ElFrenteDeValores {

        /** El dia al que se mira la situacion. Posterior a la emision y a la diligencia. */
        private static final LocalDate AL_CORTE = LocalDate.of(2026, 5, 20);

        private final ValoresSinNotificar puerto = new ValoresSinNotificarValores(valores);

        @Test
        @DisplayName("AC 2.5 — emitir dos valores sube el recuento del frente en dos")
        void emitirDosSubeElRecuentoEnDos() {
            // Delta y no total: esta clase emite valores en muchas pruebas, y un total
            // absoluto dependeria del orden de ejecucion (#397).
            long antes = cuantosSinNotificar();

            emitir("FR-0001", "OP-2026-FR0001");
            emitir("FR-0002", "OP-2026-FR0002");

            assertThat(cuantosSinNotificar()).isEqualTo(antes + 2);
        }

        @Test
        @DisplayName("AC 2.4 — el recuento es el mismo total que la consulta de valores anuncia")
        void elRecuentoEsElMismoTotalQueLaConsulta() {
            emitir("FR-0003", "OP-2026-FR0003");

            long deLaConsulta =
                    enTransaccion(
                                    () ->
                                            valores.consultar(
                                                    new CriterioDeConsultaDeValores(
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            SituacionDelValor.EMITIDO,
                                                            AL_CORTE),
                                                    new Paginacion(
                                                            0,
                                                            1,
                                                            "numero",
                                                            Paginacion.Direccion.ASCENDENTE)))
                            .totalElementos();

            assertThat(cuantosSinNotificar())
                    .as(
                            "la condicion de «emitido» es una expresion sobre tres tablas, no una"
                                    + " columna: dos copias divergirian y la del panel se lee"
                                    + " primero")
                    .isEqualTo(deLaConsulta);
        }

        @Test
        @DisplayName("un valor ya notificado deja de estar en el frente")
        void unValorYaNotificadoDejaDeEstar() {
            Valor valor = emitir("FR-0004", "OP-2026-FR0004");
            long conEl = cuantosSinNotificar();

            enTransaccion(() -> notificaciones.insertar(notificada(valor.id(), "NOT-FR-0004", 1)));

            assertThat(cuantosSinNotificar())
                    .as("el frente son los que ESPERAN la diligencia, no los que ya la tienen")
                    .isEqualTo(conEl - 1);
        }

        @Test
        @DisplayName("y la fecha decide: antes de la diligencia ese mismo valor SI estaba")
        void laFechaDecide() {
            // La situacion de un valor se mira a una fecha (regla 9). El mismo valor
            // notificado el 3 de abril estaba «emitido» el 1 de abril, y esta prueba es lo
            // unico que impide que el frente se pida con «ahora» en vez de con la fecha de
            // la peticion sin que ninguna cifra parezca mal.
            Valor valor = emitir("FR-0005", "OP-2026-FR0005");
            enTransaccion(() -> notificaciones.insertar(notificada(valor.id(), "NOT-FR-0005", 1)));

            long antesDeLaDiligencia =
                    enTransaccion(() -> puerto.cuantosA(DILIGENCIA.minusDays(1)));
            long alCorte = cuantosSinNotificar();

            assertThat(antesDeLaDiligencia).isGreaterThan(alCorte);
        }

        private long cuantosSinNotificar() {
            return enTransaccion(() -> puerto.cuantosA(AL_CORTE));
        }
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    /**
     * El contexto se fija <b>antes</b> de abrir la transaccion, no dentro: {@code
     * TenantTransactionManager} lo lee al comenzarla para emitir el {@code SET LOCAL}. Fijarlo
     * dentro del bloque llega tarde, y el sintoma es «unrecognized configuration parameter
     * app.municipalidad_id» — que no se parece en nada a su causa.
     */
    private static <T> T enTransaccion(java.util.function.Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                    return accion.get();
                });
    }

    private static Valor emitir(String codigoContribuyente, String numero) {
        return emitirPara(
                crearContribuyente(codigoContribuyente, dniDe(codigoContribuyente)), numero);
    }

    /** El mismo valor, para un contribuyente que ya existe: dos valores del mismo titular. */
    private static Valor emitirPara(long contribuyente, String numero) {
        return enTransaccion(
                () ->
                        valores.insertar(
                                new Valor(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        numero,
                                        new Ejercicio(2026),
                                        contribuyente,
                                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                        Dinero.de("500.00"),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        EMISION,
                                        EstadoDeValor.EMITIDO,
                                        EMISION,
                                        null,
                                        Observacion.de("Se emite para la prueba")),
                                List.of(
                                        ValorDetalle.nuevo(
                                                "PREDIAL",
                                                new Ejercicio(2026),
                                                null,
                                                null,
                                                null,
                                                null,
                                                Dinero.de("500.00"),
                                                Dinero.CERO,
                                                Dinero.CERO,
                                                Dinero.CERO))));
    }

    private static Notificacion noHallada(long valorId, String numero, int intento) {
        return new Notificacion(
                null,
                valorId,
                numero,
                intento,
                DILIGENCIA,
                ModalidadDeNotificacion.PERSONAL,
                ResultadoDeNotificacion.NO_UBICADO,
                "J. RUIZ PALACIOS",
                "CALLE VIEJA 100",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Observacion.de("No se ubico el domicilio"));
    }

    private static Notificacion notificada(long valorId, String numero, int intento) {
        return new Notificacion(
                null,
                valorId,
                numero,
                intento,
                DILIGENCIA,
                ModalidadDeNotificacion.PERSONAL,
                ResultadoDeNotificacion.NOTIFICADO,
                "J. RUIZ PALACIOS",
                "CALLE VIEJA 100",
                "TITULAR, PRUEBA",
                "DNI 12345678",
                "TITULAR",
                "CARGO-1",
                EXIGIBLE,
                conjuntoId,
                null,
                Observacion.de("Se diligencio para la prueba"));
    }

    private static MovimientoDeValor pase(long valorId, long notificacionId) {
        return new MovimientoDeValor(
                null,
                valorId,
                TipoDeMovimiento.PCO,
                LocalDate.of(2026, 6, 1),
                notificacionId,
                EXIGIBLE,
                null,
                Observacion.de("Se pasa a coactiva para la prueba"));
    }

    private static ComputoDeEjercicio computo(int ejercicio, boolean prescrita) {
        return new ComputoDeEjercicio(
                null,
                new Ejercicio(ejercicio),
                LocalDate.of(ejercicio + 1, 1, 1),
                LocalDate.of(ejercicio + 1, 1, 1),
                LocalDate.of(ejercicio + 5, 1, 1),
                prescrita);
    }

    private static String dniDe(String codigo) {
        return "5030" + Math.abs(codigo.hashCode() % 10000 + 10000);
    }

    private static void insertarNotificacionCruda(
            long valorId, String numero, int intento, String resultado, LocalDate exigibleDesde)
            throws SQLException {
        ejecutarComoApp(
                "INSERT INTO notificacion (municipalidad_id, objeto, objeto_id, numero, intento,"
                        + " fecha_notificacion, modalidad, resultado, notificador, direccion,"
                        + " exigible_desde, conjunto_id, usuario_registro, observacion) VALUES ("
                        + municipalidad
                        + ", 'VALOR', "
                        + valorId
                        + ", '"
                        + numero
                        + "', "
                        + intento
                        + ", DATE '2026-04-03', 'PERSONAL', '"
                        + resultado
                        + "', 'x', 'y', DATE '"
                        + exigibleDesde
                        + "', "
                        + conjuntoId
                        + ", 'x', 'z')");
    }

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** El SQLSTATE del fallo, que es lo que distingue "no tiene privilegio" de "no cumple". */
    private static String estadoSqlDelFallo(SentenciaQueFalla sentencia) {
        try {
            sentencia.ejecutar();
        } catch (SQLException fallo) {
            return fallo.getSQLState();
        }
        return "no fallo";
    }

    @FunctionalInterface
    private interface SentenciaQueFalla {
        void ejecutar() throws SQLException;
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearConjuntoSellado(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version,"
                                    + " estado, fecha_sellado, usuario_sellado)"
                                    + " VALUES (?, 2026, 1, 'SELLADO', now(), 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearContribuyente(String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(
                    "No se pudo crear el contribuyente de prueba", excepcion);
        }
    }
}
