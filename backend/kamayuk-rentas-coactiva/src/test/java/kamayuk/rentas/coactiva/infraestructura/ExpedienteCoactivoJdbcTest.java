package kamayuk.rentas.coactiva.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.coactiva.ExpedientesSinRec;
import kamayuk.rentas.coactiva.aplicacion.CambiarDireccionReferencial;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ExpedientesSinRecCoactiva;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.MotivoDeRechazo;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.coactiva.dominio.TipoDeMovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ValorRechazado;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import kamayuk.rentas.valores.aplicacion.ValoresEnCoactivaValores;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.infraestructura.MovimientoDeValorRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.NotificacionRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #40 — El expediente coactivo contra PostgreSQL de verdad (V33), conectado como {@code
 * kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El ciclo entero desde donde #39 lo dejo.</b> Deuda asentada en el libro, valor emitido,
 *       notificado con acuse, plazo vencido, pase a coactiva, importacion, expediente numerado, su
 *       deuda actualizada a una fecha, cambio de estado y cambio de direccion referencial. Contra
 *       dobles esto solo probaria que los dobles se acuerdan de lo que les dijeron.
 *   <li><b>Que un valor no pueda estar en dos expedientes, bajo concurrencia real.</b> Un doble que
 *       consulta antes de insertar pasa la prueba y falla en produccion: diez peticiones
 *       simultaneas pasan las diez por el {@code if}. Aqui se lanzan diez hilos a la vez.
 *   <li><b>Que {@code kamayuk_app} no tenga el privilegio de actualizar el expediente, sus valores
 *       ni su historial.</b> No es una convencion: es un {@code REVOKE} de V33, y se comprueba
 *       intentandolo por SQL directo.
 *   <li><b>Que el estado derivado en SQL y el derivado en Java digan lo mismo.</b> Son dos
 *       escrituras de la misma regla —{@code ESTADO_DERIVADO} en el repositorio y {@code
 *       EstadoDelExpediente#delHistorial} en el dominio—, y si divergen la grilla muestra filas
 *       cuyo estado no coincide con el filtro que las trajo. En un doble solo hay una de las dos.
 *   <li><b>Que RLS aisle el expediente</b>: desde otra municipalidad no existe.
 * </ul>
 */
@DisplayName("#40 — El expediente coactivo contra PostgreSQL")
class ExpedienteCoactivoJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 4, 3);
    private static final LocalDate EXIGIBLE = LocalDate.of(2026, 5, 5);
    private static final LocalDate PASE = LocalDate.of(2026, 6, 1);
    private static final LocalDate IMPORTACION = LocalDate.of(2026, 6, 15);
    private static final LocalDate FECHA_DEL_CARGO = LocalDate.of(2026, 1, 2);
    private static final Dinero PREDIAL = Dinero.de("500.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long conjuntoId;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static ValorRepositoryJdbc valores;
    private static NotificacionRepositoryJdbc notificaciones;
    private static MovimientoDeValorRepositoryJdbc movimientosDeValor;
    private static ExpedienteRepositoryJdbc expedientes;
    private static LiquidacionDeCostasRepositoryJdbc costas;
    private static MovimientoDelExpedienteRepositoryJdbc movimientos;
    private static RegistrarAsiento registrarAsiento;

    private static ImportarValoresACoactiva importar;
    private static CambiarEstadoDelExpediente cambiarEstado;
    private static CambiarDireccionReferencial cambiarDireccion;
    private static ConsultaDeExpedientes consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230401", "Municipalidad del expediente coactivo");
        otraMunicipalidad = crearMunicipalidad("240403", "Municipalidad vecina de #40");
        conjuntoId = crearConjuntoSellado(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        valores = new ValorRepositoryJdbc(jdbc);
        notificaciones = new NotificacionRepositoryJdbc(jdbc);
        movimientosDeValor = new MovimientoDeValorRepositoryJdbc(jdbc);
        expedientes = new ExpedienteRepositoryJdbc(jdbc);
        costas = new LiquidacionDeCostasRepositoryJdbc(jdbc);
        movimientos = new MovimientoDelExpedienteRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        ConsultaDeDeudaPublica deuda =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));

        ValoresEnCoactiva puerto =
                envolver(new ValoresEnCoactivaValores(valores, movimientosDeValor));

        importar =
                envolver(
                        new ImportarValoresACoactiva(
                                expedientes, movimientos, puerto, auditoria, RELOJ));
        cambiarEstado =
                envolver(
                        new CambiarEstadoDelExpediente(expedientes, movimientos, auditoria, RELOJ));
        cambiarDireccion =
                envolver(
                        new CambiarDireccionReferencial(
                                expedientes, movimientos, auditoria, RELOJ));
        consulta =
                envolver(
                        new ConsultaDeExpedientes(expedientes, movimientos, puerto, deuda, costas));
    }

    @AfterAll
    static void cerrarBase() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("El ciclo: del pase de #39 al expediente con su historial")
    class ElCiclo {

        @Test
        @DisplayName(
                "valor emitido, notificado, exigible, pasado, importado: expediente con numero,"
                        + " valores y deuda a la fecha")
        void elCicloCompleto() {
            long contribuyente = contribuyenteConDeuda("C-0001");
            Valor valor = emitir(contribuyente, "OP-2026-C00001");
            pasarACoactiva(valor);

            InformeDeImportacion informe = importarTodo(contribuyente, "R. MENDOZA CRUZ");

            assertThat(informe.abrioExpediente()).isTrue();
            assertThat(informe.rechazados()).isEmpty();
            assertThat(informe.importados()).hasSize(1);
            assertThat(informe.expedienteAbierto().numero())
                    .as("la plantilla de D-09 compone el numero; el correlativo lo da la base")
                    .startsWith("EXP-2026-");
            assertThat(informe.expedienteAbierto().ejercicio()).isEqualTo(EJERCICIO);
            assertThat(informe.expedienteAbierto().fechaApertura()).isEqualTo(IMPORTACION);
            assertThat(informe.expedienteAbierto().usuarioRegistro())
                    .as("el usuario sale del origen de la peticion, no de un argumento")
                    .isEqualTo("ejecutor.coactivo");

            ConsultaDeExpedientes.FichaDelExpediente ficha =
                    ficha(informe.expedienteAbierto().numero());

            assertThat(ficha.estado())
                    .as("nace INICIADO: todavia no hay REC")
                    .isEqualTo(EstadoDelExpediente.INICIADO);
            assertThat(ficha.valores()).hasSize(1);
            assertThat(ficha.valores().get(0).valorId()).isEqualTo(valor.id());
            assertThat(ficha.valores().get(0).fechaImportacion())
                    .as("la fecha sale del argumento, no del DEFAULT current_date que V33 retiro")
                    .isEqualTo(IMPORTACION);
            assertThat(ficha.historial())
                    .singleElement()
                    .satisfies(
                            m ->
                                    assertThat(m.tipo())
                                            .isEqualTo(TipoDeMovimientoDelExpediente.APERTURA));

            assertThat(ficha.deuda().insoluto())
                    .as("la deuda sale del libro a la fecha, no de los importes congelados")
                    .isEqualTo(PREDIAL);
            assertThat(ficha.deuda().actualizadaA())
                    .as("toda cifra dice a que dia esta (regla 9, RNF-075)")
                    .isEqualTo(IMPORTACION);
            assertThat(ficha.deuda().costas())
                    .as("las costas son #42: el sumando existe y el importe no se inventa")
                    .isEqualTo(Dinero.CERO);
            assertThat(ficha.deuda().total()).isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("importar responde el ACO que #39 dejo anunciado, sin tocar el pase")
        void importarRespondeElAco() {
            long contribuyente = contribuyenteConDeuda("C-0002");
            Valor valor = emitir(contribuyente, "OP-2026-C00002");
            pasarACoactiva(valor);

            importarTodo(contribuyente, "R. MENDOZA CRUZ");

            List<MovimientoDeValor> delValor =
                    enTransaccion(() -> movimientosDeValor.deValor(valor.id()));

            assertThat(delValor)
                    .extracting(MovimientoDeValor::tipo)
                    .as("PCO lo escribio #39; ACO es la respuesta de coactiva, y la escribe #40")
                    .containsExactly(TipoDeMovimiento.PCO, TipoDeMovimiento.ACO);
            assertThat(delValor.get(1).exigibleDesde())
                    .as("la exigibilidad se copia del pase, no se vuelve a resolver")
                    .isEqualTo(EXIGIBLE);
        }

        @Test
        @DisplayName("el cambio de estado agrega al historial, y el estado se deriva de ahi")
        void elCambioDeEstadoAgrega() {
            long contribuyente = contribuyenteConDeuda("C-0003");
            Valor valor = emitir(contribuyente, "OP-2026-C00003");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();

            enTransaccion(
                    () ->
                            cambiarEstado.cambiar(
                                    numero,
                                    EstadoDelExpediente.REC1_EMITIDA,
                                    IMPORTACION,
                                    "se emite la REC 01",
                                    IMPORTACION,
                                    "REC-2026-0001",
                                    PORQUE));
            enTransaccion(
                    () ->
                            cambiarEstado.cambiar(
                                    numero,
                                    EstadoDelExpediente.MEDIDA_CAUTELAR,
                                    IMPORTACION.plusDays(10),
                                    "embargo en forma de retencion",
                                    null,
                                    null,
                                    PORQUE));

            ConsultaDeExpedientes.FichaDelExpediente ficha = ficha(numero);

            assertThat(ficha.estado()).isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(ficha.historial()).hasSize(3);
            assertThat(ficha.historial().get(1).documentoNumero()).isEqualTo("REC-2026-0001");
            assertThat(ficha.historial().get(1).motivo()).isEqualTo("se emite la REC 01");
            assertThat(ficha.historial())
                    .as("cada linea del historial dice quien la registro y por que (regla 10)")
                    .allSatisfy(
                            m -> {
                                assertThat(m.usuarioRegistro()).isEqualTo("ejecutor.coactivo");
                                assertThat(m.observacion().texto()).isNotBlank();
                            });
        }

        @Test
        @DisplayName(
                "el cambio de direccion deja traza y conserva la de apertura, sin mover el estado")
        void elCambioDeDireccionDejaTraza() {
            long contribuyente = contribuyenteConDeuda("C-0004");
            Valor valor = emitir(contribuyente, "OP-2026-C00004");
            pasarACoactiva(valor);
            String numero =
                    enTransaccion(
                                    () ->
                                            importar.importar(
                                                    new ImportarValoresACoactiva.Peticion(
                                                            contribuyente,
                                                            List.of(),
                                                            "R. MENDOZA CRUZ",
                                                            null,
                                                            "Cobranza coactiva",
                                                            "AV. ORIGINAL 100"),
                                                    IMPORTACION,
                                                    PlantillaDeNumeroDeExpediente.POR_OMISION,
                                                    PORQUE))
                            .expedienteAbierto()
                            .numero();

            enTransaccion(
                    () ->
                            cambiarDireccion.cambiar(
                                    numero,
                                    "JR. NUEVO 250 - URB. SANTA ROSA",
                                    IMPORTACION.plusDays(3),
                                    "no ubicado en el domicilio fiscal",
                                    PORQUE));

            ConsultaDeExpedientes.FichaDelExpediente ficha = ficha(numero);

            assertThat(ficha.direccionReferencialVigente())
                    .isEqualTo("JR. NUEVO 250 - URB. SANTA ROSA");
            assertThat(ficha.expediente().direccionReferencial())
                    .as(
                            "la de apertura se conserva: es la que explica a donde fueron las"
                                    + " primeras notificaciones")
                    .isEqualTo("AV. ORIGINAL 100");
            assertThat(ficha.estado())
                    .as("cambiar donde se notifica no mueve el procedimiento")
                    .isEqualTo(EstadoDelExpediente.INICIADO);
            assertThat(ficha.historial().get(1).motivo())
                    .isEqualTo("no ubicado en el domicilio fiscal");
        }

        @Test
        @DisplayName("la misma direccion dos veces se rechaza: no es un cambio")
        void laMismaDireccionSeRechaza() {
            long contribuyente = contribuyenteConDeuda("C-0005");
            Valor valor = emitir(contribuyente, "OP-2026-C00005");
            pasarACoactiva(valor);
            String numero =
                    enTransaccion(
                                    () ->
                                            importar.importar(
                                                    new ImportarValoresACoactiva.Peticion(
                                                            contribuyente,
                                                            List.of(),
                                                            "R. MENDOZA CRUZ",
                                                            null,
                                                            null,
                                                            "AV. ORIGINAL 100"),
                                                    IMPORTACION,
                                                    PlantillaDeNumeroDeExpediente.POR_OMISION,
                                                    PORQUE))
                            .expedienteAbierto()
                            .numero();

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    cambiarDireccion.cambiar(
                                                            numero,
                                                            "av. original 100",
                                                            IMPORTACION,
                                                            "por si acaso",
                                                            PORQUE)))
                    .isInstanceOf(CambiarDireccionReferencial.MismaDireccion.class);
        }

        @Test
        @DisplayName("un expediente concluido no admite mas actos")
        void elConcluidoNoAdmiteMas() {
            long contribuyente = contribuyenteConDeuda("C-0006");
            Valor valor = emitir(contribuyente, "OP-2026-C00006");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();

            enTransaccion(
                    () ->
                            cambiarEstado.cambiar(
                                    numero,
                                    EstadoDelExpediente.CONCLUIDO,
                                    IMPORTACION,
                                    "pago total de la deuda",
                                    null,
                                    null,
                                    PORQUE));

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    cambiarEstado.cambiar(
                                                            numero,
                                                            EstadoDelExpediente.MEDIDA_CAUTELAR,
                                                            IMPORTACION,
                                                            "se traba medida",
                                                            null,
                                                            null,
                                                            PORQUE)))
                    .isInstanceOf(CambiarEstadoDelExpediente.ExpedienteConcluido.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Lo que no entra, y por que")
    class Rechazos {

        @Test
        @DisplayName("un valor sin notificar se rechaza: el expediente seria nulo")
        void sinNotificar() {
            long contribuyente = contribuyenteConDeuda("R-0001");
            emitir(contribuyente, "OP-2026-R00001");

            InformeDeImportacion informe = importarTodo(contribuyente, "R. MENDOZA CRUZ");

            assertThat(informe.abrioExpediente())
                    .as("sin ningun valor admitido no se abre expediente ni se gasta correlativo")
                    .isFalse();
            assertThat(informe.rechazados())
                    .singleElement()
                    .extracting(ValorRechazado::motivo)
                    .isEqualTo(MotivoDeRechazo.SIN_NOTIFICAR);
        }

        @Test
        @DisplayName("notificado pero con el plazo corriendo: PLAZO_VIGENTE a esa fecha")
        void conElPlazoCorriendo() {
            long contribuyente = contribuyenteConDeuda("R-0002");
            Valor valor = emitir(contribuyente, "OP-2026-R00002");
            notificar(valor, "OP-2026-R00002/1");
            enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.NOTIFICADO));

            // Dentro del plazo: la exigibilidad es el 5 de mayo y se mira el 10 de abril.
            InformeDeImportacion informe =
                    enTransaccion(
                            () ->
                                    importar.importar(
                                            peticion(contribuyente, List.of()),
                                            LocalDate.of(2026, 4, 10),
                                            PlantillaDeNumeroDeExpediente.POR_OMISION,
                                            PORQUE));

            assertThat(informe.rechazados())
                    .singleElement()
                    .extracting(ValorRechazado::motivo)
                    .isEqualTo(MotivoDeRechazo.PLAZO_VIGENTE);
        }

        @Test
        @DisplayName("exigible pero sin pase: la importacion empieza donde el pase termina")
        void exigibleSinPase() {
            long contribuyente = contribuyenteConDeuda("R-0003");
            Valor valor = emitir(contribuyente, "OP-2026-R00003");
            notificar(valor, "OP-2026-R00003/1");
            enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.NOTIFICADO));

            InformeDeImportacion informe = importarTodo(contribuyente, "R. MENDOZA CRUZ");

            assertThat(informe.abrioExpediente()).isFalse();
            assertThat(informe.rechazados())
                    .singleElement()
                    .satisfies(
                            rechazo -> {
                                assertThat(rechazo.motivo())
                                        .isEqualTo(MotivoDeRechazo.SIN_PASE_A_COACTIVA);
                                assertThat(rechazo.numero()).isEqualTo("OP-2026-R00003");
                                assertThat(rechazo.descripcion())
                                        .as("el informe se lee: motivo por valor, no «0 de 1»")
                                        .contains("OP-2026-R00003");
                            });
        }

        @Test
        @DisplayName("lo admitido entra aunque lo demas se rechace: fila a fila, no todo o nada")
        void loAdmitidoEntraAunqueElRestoNo() {
            long contribuyente = contribuyenteConDeuda("R-0004");
            Valor bueno = emitir(contribuyente, "OP-2026-R00004");
            pasarACoactiva(bueno);
            emitir(contribuyente, "OP-2026-R00005");

            InformeDeImportacion informe = importarTodo(contribuyente, "R. MENDOZA CRUZ");

            assertThat(informe.importados()).hasSize(1);
            assertThat(informe.rechazados()).hasSize(1);
            assertThat(informe.expedienteAbierto().numero()).isNotBlank();
        }

        @Test
        @DisplayName("un numero de valor que no existe se dice, no se ignora en silencio")
        void numeroInexistente() {
            long contribuyente = contribuyenteConDeuda("R-0006");
            Valor valor = emitir(contribuyente, "OP-2026-R00006");
            pasarACoactiva(valor);

            InformeDeImportacion informe =
                    enTransaccion(
                            () ->
                                    importar.importar(
                                            peticion(
                                                    contribuyente,
                                                    List.of("OP-2026-R00006", "OP-2026-NOEXISTE")),
                                            IMPORTACION,
                                            PlantillaDeNumeroDeExpediente.POR_OMISION,
                                            PORQUE));

            assertThat(informe.importados()).hasSize(1);
            assertThat(informe.rechazados())
                    .singleElement()
                    .satisfies(
                            rechazo -> {
                                assertThat(rechazo.numero()).isEqualTo("OP-2026-NOEXISTE");
                                assertThat(rechazo.motivo()).isEqualTo(MotivoDeRechazo.NO_EXISTE);
                            });
        }

        @Test
        @DisplayName("un valor de otro contribuyente se rechaza diciendolo")
        void deOtroContribuyente() {
            long uno = contribuyenteConDeuda("R-0007");
            long otro = contribuyenteConDeuda("R-0008");
            Valor suyo = emitir(uno, "OP-2026-R00007");
            pasarACoactiva(suyo);
            Valor ajeno = emitir(otro, "OP-2026-R00008");
            pasarACoactiva(ajeno);

            InformeDeImportacion informe =
                    enTransaccion(
                            () ->
                                    importar.importar(
                                            peticion(
                                                    uno,
                                                    List.of("OP-2026-R00007", "OP-2026-R00008")),
                                            IMPORTACION,
                                            PlantillaDeNumeroDeExpediente.POR_OMISION,
                                            PORQUE));

            assertThat(informe.importados()).hasSize(1);
            assertThat(informe.rechazados())
                    .singleElement()
                    .extracting(ValorRechazado::motivo)
                    .as("un expediente agrupa la deuda de un solo obligado")
                    .isEqualTo(MotivoDeRechazo.DE_OTRO_CONTRIBUYENTE);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Un valor, un expediente")
    class SinDuplicados {

        @Test
        @DisplayName("reintentar la importacion no duplica: el segundo intento dice «ya estaba»")
        void reintentarNoDuplica() {
            long contribuyente = contribuyenteConDeuda("D-0001");
            Valor valor = emitir(contribuyente, "OP-2026-D00001");
            pasarACoactiva(valor);

            InformeDeImportacion primera = importarTodo(contribuyente, "R. MENDOZA CRUZ");
            InformeDeImportacion segunda = importarTodo(contribuyente, "R. MENDOZA CRUZ");

            assertThat(primera.abrioExpediente()).isTrue();
            assertThat(segunda.abrioExpediente())
                    .as("el segundo intento no abre un expediente nuevo")
                    .isFalse();
            assertThat(segunda.rechazados())
                    .singleElement()
                    .extracting(ValorRechazado::motivo)
                    .isEqualTo(MotivoDeRechazo.YA_EN_UN_EXPEDIENTE);
            assertThat(cuantosExpedientesDe(contribuyente)).isEqualTo(1);
        }

        /**
         * Se captura {@code RuntimeException} a proposito: la importacion que pierde la carrera
         * puede fallar de mas de una forma —el choque contra el indice unico, o el rechazo de la
         * transaccion al confirmar—, y <b>cualquiera de ellas es correcta</b>. Lo que se mide no es
         * como falla la que pierde, sino que solo una gane y que ninguna deje medio expediente.
         */
        @SuppressWarnings("checkstyle:IllegalCatch")
        @Test
        @DisplayName("diez importaciones simultaneas del mismo valor producen UN expediente")
        void diezSimultaneasProducenUno() throws Exception {
            long contribuyente = contribuyenteConDeuda("D-0002");
            Valor valor = emitir(contribuyente, "OP-2026-D00002");
            pasarACoactiva(valor);

            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                tareas.add(
                        () -> {
                            // El origen y el contexto de tenant son ThreadLocal: cada hilo del
                            // pool empieza sin ellos, igual que empezaria una peticion.
                            OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                return enTransaccion(
                                                () ->
                                                        importar.importar(
                                                                peticion(contribuyente, List.of()),
                                                                IMPORTACION,
                                                                PlantillaDeNumeroDeExpediente
                                                                        .POR_OMISION,
                                                                PORQUE))
                                        .abrioExpediente();
                            } catch (RuntimeException perdio) {
                                // La importacion que pierde la carrera se deshace ENTERA:
                                // correlativo incluido. Que falle es lo correcto; lo que no
                                // puede es dejar medio expediente.
                                return false;
                            }
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            int abrieron = 0;
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(60, TimeUnit.SECONDS))) {
                        abrieron++;
                    }
                }
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(abrieron)
                    .as("la unicidad esta en la base; ningun `if` de Java serializa diez hilos")
                    .isEqualTo(1);
            assertThat(cuantosExpedientesDe(contribuyente)).isEqualTo(1);
            assertThat(cuantasFilasDelValor(valor.id()))
                    .as("expediente_valor_unico_uq: un valor vive en un expediente")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("el correlativo del ejercicio no se repite ni salta")
        void elCorrelativoAvanza() {
            long uno = contribuyenteConDeuda("D-0003");
            Valor suyo = emitir(uno, "OP-2026-D00003");
            pasarACoactiva(suyo);
            long otro = contribuyenteConDeuda("D-0004");
            Valor ajeno = emitir(otro, "OP-2026-D00004");
            pasarACoactiva(ajeno);

            long primero = importarTodo(uno, "R. MENDOZA CRUZ").expedienteAbierto().correlativo();
            long segundo = importarTodo(otro, "R. MENDOZA CRUZ").expedienteAbierto().correlativo();

            assertThat(segundo).isEqualTo(primero + 1);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Lo que la base impide, y no un `if`")
    class Privilegios {

        @Test
        @DisplayName("kamayuk_app no puede actualizar el historial: el privilegio no existe")
        void noSePuedeActualizarElHistorial() {
            long contribuyente = contribuyenteConDeuda("P-0001");
            Valor valor = emitir(contribuyente, "OP-2026-P00001");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();
            MovimientoDelExpediente apertura = ficha(numero).historial().get(0);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE expediente_movimiento SET estado ="
                                                            + " 'CONCLUIDO' WHERE id = "
                                                            + apertura.id())))
                    .as("V33 le revoca el UPDATE: un cambio se corrige con otro movimiento")
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("ni borrarlo, ni tocar el expediente ni sus valores")
        void niBorrarNiTocar() {
            long contribuyente = contribuyenteConDeuda("P-0002");
            Valor valor = emitir(contribuyente, "OP-2026-P00002");
            pasarACoactiva(valor);
            long expedienteId =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ")
                            .expedienteAbierto()
                            .identificador();

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM expediente_movimiento WHERE"
                                                            + " expediente_id = "
                                                            + expedienteId)))
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE expediente_coactivo SET ejecutor ="
                                                            + " 'OTRO' WHERE id = "
                                                            + expedienteId)))
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE expediente_valor SET valor_id = 0"
                                                            + " WHERE expediente_id = "
                                                            + expedienteId)))
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("dos aperturas del mismo expediente las rechaza el indice, no la aplicacion")
        void dosAperturasSeRechazan() {
            long contribuyente = contribuyenteConDeuda("P-0003");
            Valor valor = emitir(contribuyente, "OP-2026-P00003");
            pasarACoactiva(valor);
            long expedienteId =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ")
                            .expedienteAbierto()
                            .identificador();

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    movimientos.registrar(
                                                            MovimientoDelExpediente.apertura(
                                                                    expedienteId,
                                                                    IMPORTACION,
                                                                    "otra apertura",
                                                                    RELOJ.instant(),
                                                                    PORQUE))))
                    .isInstanceOf(
                            kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository
                                    .AperturaDuplicada.class);
        }

        @Test
        @DisplayName("un movimiento de estado con direccion pegada lo rechaza la base")
        void laBaseRechazaLaCargaIncoherente() {
            long contribuyente = contribuyenteConDeuda("P-0004");
            Valor valor = emitir(contribuyente, "OP-2026-P00004");
            pasarACoactiva(valor);
            long expedienteId =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ")
                            .expedienteAbierto()
                            .identificador();

            // Escrito por SQL directo, saltandose el dominio: es la comprobacion de que la
            // restriccion existe en la base y no solo en el constructor del record.
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO expediente_movimiento"
                                                            + " (municipalidad_id, expediente_id,"
                                                            + " tipo, estado, direccion_referencial,"
                                                            + " fecha, motivo, usuario_registro,"
                                                            + " fecha_registro, observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + expedienteId
                                                            + ", 'ESTADO', 'SUSPENDIDO', 'JR. X',"
                                                            + " DATE '2026-06-15', 'm', 'u',"
                                                            + " now(), 'o')")))
                    .isEqualTo("23514");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("La grilla, y el aislamiento")
    class LaGrilla {

        @Test
        @DisplayName("el filtro por estado trae exactamente las filas que lo pintan igual")
        void elFiltroCoincideConLaColumna() {
            long contribuyente = contribuyenteConDeuda("G-0001");
            Valor valor = emitir(contribuyente, "OP-2026-G00001");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();
            enTransaccion(
                    () ->
                            cambiarEstado.cambiar(
                                    numero,
                                    EstadoDelExpediente.SUSPENDIDO,
                                    IMPORTACION,
                                    "reclamacion en tramite",
                                    null,
                                    null,
                                    PORQUE));

            for (EstadoDelExpediente estado : EstadoDelExpediente.values()) {
                List<ConsultaDeExpedientes.ExpedienteConDeuda> filas =
                        filtrarPor(estado).contenido();
                assertThat(filas)
                        .as(
                                "el SQL de ESTADO_DERIVADO y EstadoDelExpediente#delHistorial son"
                                        + " dos escrituras de la misma regla: si divergen, la fila"
                                        + " muestra un estado que no es el del filtro que la trajo —"
                                        + estado)
                        .allSatisfy(fila -> assertThat(fila.fila().estado()).isEqualTo(estado));
            }

            assertThat(numerosDe(filtrarPor(EstadoDelExpediente.SUSPENDIDO))).contains(numero);
            assertThat(numerosDe(filtrarPor(EstadoDelExpediente.INICIADO))).doesNotContain(numero);
        }

        @Test
        @DisplayName("la grilla trae la direccion vigente y cuantos valores agrupa")
        void laGrillaTraeLoDerivado() {
            long contribuyente = contribuyenteConDeuda("G-0002");
            Valor uno = emitir(contribuyente, "OP-2026-G00002");
            pasarACoactiva(uno);
            Valor dos = emitir(contribuyente, "OP-2026-G00003");
            pasarACoactiva(dos);
            String numero =
                    enTransaccion(
                                    () ->
                                            importar.importar(
                                                    new ImportarValoresACoactiva.Peticion(
                                                            contribuyente,
                                                            List.of(),
                                                            "C. ANCAJIMA FLORES",
                                                            "S. PALACIOS NIMA",
                                                            null,
                                                            "AV. PRIMERA 1"),
                                                    IMPORTACION,
                                                    PlantillaDeNumeroDeExpediente.POR_OMISION,
                                                    PORQUE))
                            .expedienteAbierto()
                            .numero();
            enTransaccion(
                    () ->
                            cambiarDireccion.cambiar(
                                    numero, "AV. SEGUNDA 2", IMPORTACION, "mudanza", PORQUE));

            ConsultaDeExpedientes.ExpedienteConDeuda fila =
                    enTransaccion(
                                    () ->
                                            consulta.buscar(
                                                    new CriterioDeExpedientes(
                                                            numero, null, null, null, null),
                                                    IMPORTACION,
                                                    unaPagina()))
                            .contenido()
                            .get(0);

            assertThat(fila.fila().valores()).isEqualTo(2);
            assertThat(fila.fila().direccionReferencialVigente()).isEqualTo("AV. SEGUNDA 2");
            assertThat(fila.deuda().actualizadaA()).isEqualTo(IMPORTACION);
            assertThat(fila.deuda().insoluto())
                    .as(
                            "dos valores sobre la MISMA obligacion no la cuentan dos veces: seria"
                                    + " duplicar la deuda del procedimiento")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("la deuda es la del dia que se pide, no la de hoy (regla 9)")
        void laDeudaEsLaDelDiaQueSePide() {
            long contribuyente = contribuyenteConDeuda("G-0004");
            Valor valor = emitir(contribuyente, "OP-2026-G00004");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();

            DeudaDelExpediente enJunio = ficha(numero).deuda();
            DeudaDelExpediente antesDelCargo =
                    enTransaccion(() -> consulta.porNumero(numero, FECHA_DEL_CARGO.minusDays(1)))
                            .orElseThrow()
                            .deuda();

            assertThat(enJunio.insoluto()).isEqualTo(PREDIAL);
            assertThat(antesDelCargo.insoluto())
                    .as("el dia anterior al cargo no se debia nada, y la cifra lo dice")
                    .isEqualTo(Dinero.CERO);
            assertThat(antesDelCargo.actualizadaA()).isEqualTo(FECHA_DEL_CARGO.minusDays(1));
        }

        @Test
        @DisplayName("el filtro por ejercicio y por ejecutor acota, y el correlativo se reinicia")
        void elFiltroPorEjercicioYEjecutor() {
            long contribuyente = contribuyenteConDeuda("G-0006");
            Valor valor = emitir(contribuyente, "OP-2026-G00006");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "C. ANCAJIMA FLORES").expedienteAbierto().numero();

            assertThat(
                            numerosDe(
                                    enTransaccion(
                                            () ->
                                                    consulta.buscar(
                                                            new CriterioDeExpedientes(
                                                                    null,
                                                                    null,
                                                                    "c. ancajima flores",
                                                                    null,
                                                                    2026),
                                                            IMPORTACION,
                                                            unaPagina()))))
                    .contains(numero);
            assertThat(
                            enTransaccion(
                                            () ->
                                                    consulta.buscar(
                                                            new CriterioDeExpedientes(
                                                                    null, null, null, null, 2025),
                                                            IMPORTACION,
                                                            unaPagina()))
                                    .totalElementos())
                    .as("el correlativo se reinicia con el ejercicio, como el de un valor (V26)")
                    .isZero();
        }

        @Test
        @DisplayName("desde otra municipalidad el expediente no existe: RLS")
        void desdeOtraMunicipalidadNoExiste() {
            long contribuyente = contribuyenteConDeuda("G-0005");
            Valor valor = emitir(contribuyente, "OP-2026-G00005");
            pasarACoactiva(valor);
            String numero =
                    importarTodo(contribuyente, "R. MENDOZA CRUZ").expedienteAbierto().numero();

            assertThat(enTransaccionDe(otraMunicipalidad, () -> expedientes.porNumero(numero)))
                    .as("la politica de V6 filtra por municipalidad, y no es opcional")
                    .isEmpty();
            assertThat(
                            enTransaccionDe(
                                            otraMunicipalidad,
                                            () ->
                                                    expedientes.consultar(
                                                            CriterioDeExpedientes.todos(),
                                                            unaPagina()))
                                    .totalElementos())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("#549 — El frente de Coactiva: expedientes importados sin REC-1")
    class ElFrenteDeCoactiva {

        private final ExpedientesSinRec puerto = new ExpedientesSinRecCoactiva(expedientes);

        @Test
        @DisplayName("AC 2.5 — importar dos expedientes sube el recuento del frente en dos")
        void importarDosSubeElRecuentoEnDos() {
            // Delta y no total: esta clase importa expedientes en muchas pruebas y un total
            // absoluto dependeria del orden de ejecucion (#397).
            long antes = cuantosSinRec();

            abrirExpediente("F-0001");
            abrirExpediente("F-0002");

            assertThat(cuantosSinRec()).isEqualTo(antes + 2);
        }

        @Test
        @DisplayName("AC 2.4 — el recuento es el mismo total que la grilla de expedientes anuncia")
        void elRecuentoEsElMismoTotalQueLaGrilla() {
            abrirExpediente("F-0003");

            assertThat(cuantosSinRec())
                    .as(
                            "el estado del expediente se DERIVA del ultimo movimiento: dos"
                                    + " transcripciones de esa derivacion divergen, y la del panel"
                                    + " se lee primero (#397)")
                    .isEqualTo(filtrarPor(EstadoDelExpediente.INICIADO).totalElementos());
        }

        /** Un expediente recien importado: nace en {@code INICIADO}, sin ningun movimiento. */
        private void abrirExpediente(String sufijo) {
            long contribuyente = contribuyenteConDeuda(sufijo);
            pasarACoactiva(emitir(contribuyente, "OP-2026-" + sufijo));
            importarTodo(contribuyente, "R. MENDOZA CRUZ");
        }

        private long cuantosSinRec() {
            return enTransaccion(puerto::cuantosSinRec1);
        }
    }

    // ==================================================================
    //  Utilidades
    // ==================================================================

    /**
     * El contexto se fija <b>antes</b> de abrir la transaccion, no dentro: {@code
     * TenantTransactionManager} lo lee al comenzarla para emitir el {@code SET LOCAL}. Fijarlo
     * dentro del bloque llega tarde, y el sintoma es «unrecognized configuration parameter
     * app.municipalidad_id» — que no se parece en nada a su causa.
     */
    private static <T> T enTransaccion(Supplier<T> accion) {
        return enTransaccionDe(municipalidad, accion);
    }

    private static <T> T enTransaccionDe(long tenant, Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(tenant));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(tenant));
                    return accion.get();
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static ImportarValoresACoactiva.Peticion peticion(
            long contribuyenteId, List<String> numeros) {
        return new ImportarValoresACoactiva.Peticion(
                contribuyenteId, numeros, "R. MENDOZA CRUZ", null, null, null);
    }

    private static InformeDeImportacion importarTodo(long contribuyenteId, String ejecutor) {
        return enTransaccion(
                () ->
                        importar.importar(
                                new ImportarValoresACoactiva.Peticion(
                                        contribuyenteId, List.of(), ejecutor, null, null, null),
                                IMPORTACION,
                                PlantillaDeNumeroDeExpediente.POR_OMISION,
                                PORQUE));
    }

    private static ConsultaDeExpedientes.FichaDelExpediente ficha(String numero) {
        return enTransaccion(() -> consulta.porNumero(numero, IMPORTACION)).orElseThrow();
    }

    private static Pagina<ConsultaDeExpedientes.ExpedienteConDeuda> filtrarPor(
            EstadoDelExpediente estado) {
        return enTransaccion(
                () ->
                        consulta.buscar(
                                new CriterioDeExpedientes(null, null, null, estado, null),
                                IMPORTACION,
                                unaPagina()));
    }

    private static List<String> numerosDe(Pagina<ConsultaDeExpedientes.ExpedienteConDeuda> pagina) {
        return pagina.contenido().stream().map(fila -> fila.fila().expediente().numero()).toList();
    }

    private static Paginacion unaPagina() {
        return new Paginacion(0, 200, "numero", Paginacion.Direccion.ASCENDENTE);
    }

    /** Un contribuyente con su cargo de predial ya asentado en el libro. */
    private static long contribuyenteConDeuda(String sufijo) {
        long id = crearContribuyente(sufijo);
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        id,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.VALOR,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        FECHA_DEL_CARGO,
                                        "DETERMINACION DE LA PRUEBA"),
                                Observacion.de("Se asienta la deuda de la prueba")));
        return id;
    }

    private static Valor emitir(long contribuyenteId, String numero) {
        return enTransaccion(
                () ->
                        valores.insertar(
                                new Valor(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        numero,
                                        EJERCICIO,
                                        contribuyenteId,
                                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                        PREDIAL,
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
                                                EJERCICIO,
                                                null,
                                                null,
                                                null,
                                                null,
                                                PREDIAL,
                                                Dinero.CERO,
                                                Dinero.CERO,
                                                Dinero.CERO))));
    }

    private static Notificacion notificar(Valor valor, String numero) {
        return enTransaccion(
                () ->
                        notificaciones.insertar(
                                new Notificacion(
                                        null,
                                        valor.id(),
                                        numero,
                                        1,
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
                                        Observacion.de("Se diligencio para la prueba"))));
    }

    /** El camino de #39 entero: notificacion con acuse, estado y pase (PCO). */
    private static void pasarACoactiva(Valor valor) {
        Notificacion diligencia = notificar(valor, valor.numero() + "/1");
        enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.NOTIFICADO));
        enTransaccion(
                () ->
                        movimientosDeValor.registrarPase(
                                new MovimientoDeValor(
                                        null,
                                        valor.id(),
                                        TipoDeMovimiento.PCO,
                                        PASE,
                                        diligencia.id(),
                                        EXIGIBLE,
                                        null,
                                        Observacion.de("Se pasa a coactiva para la prueba"))));
        enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.COACTIVA));
    }

    private static int cuantosExpedientesDe(long contribuyenteId) {
        return enTransaccion(
                        () ->
                                expedientes.consultar(
                                        new CriterioDeExpedientes(
                                                null, contribuyenteId, null, null, null),
                                        unaPagina()))
                .contenido()
                .size();
    }

    private static long cuantasFilasDelValor(long valorId) {
        Long cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM expediente_valor"
                                                        + " WHERE valor_id = :valor")
                                        .param("valor", valorId)
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
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

    private static long crearContribuyente(String sufijo) {
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
                sentencia.setString(2, sufijo);
                sentencia.setString(3, dniDe(sufijo));
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

    private static String dniDe(String codigo) {
        return "4040" + Math.abs(codigo.hashCode() % 10000 + 10000);
    }
}
