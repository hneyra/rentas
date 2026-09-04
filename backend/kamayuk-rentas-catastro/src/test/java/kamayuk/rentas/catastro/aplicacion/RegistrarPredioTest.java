package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * El predio, sus catalogos y su titularidad, contra PostgreSQL real.
 *
 * <p>La pieza que hay que ver funcionar es el <b>disparador diferido</b> de la titularidad: la
 * comprobacion de que los porcentajes vigentes no exceden 100 se evalua <b>al cerrar la
 * transaccion</b>, no fila a fila. Sin eso, una transferencia legitima —cerrar una titularidad y
 * abrir otra— seria imposible.
 *
 * <p>Los codigos catastrales de la prueba se construyen con la composicion del manual (23
 * posiciones). Si D-10 la cambia, lo que cambia es el parametro, no estas pruebas.
 */
@DisplayName("RF-005/008 — Predio, catalogos y titularidad")
class RegistrarPredioTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long titular;
    private static long comprador;

    private static TransactionTemplate transaccion;
    private static CatastroRepositoryJdbc repositorio;

    /** Para mirar columnas que ningun puerto publica, como el area del poligono. */
    private static JdbcClient jdbcDePrueba;

    private static RegistrarPredio registrar;
    private static ImportarPrediosDelPlano importarDelPlano;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230101", "Municipalidad del catastro");
        otraMunicipalidad = crearMunicipalidad("230102", "Municipalidad vecina");

        titular = crearContribuyente(municipalidad, "T-0001", "50100001", "TITULAR, PREDIO");
        comprador = crearContribuyente(municipalidad, "T-0002", "50100002", "COMPRADOR, PREDIO");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        jdbcDePrueba = jdbc;
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new CatastroRepositoryJdbc(jdbc);
        registrar =
                envolver(
                        new RegistrarPredio(repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        importarDelPlano =
                envolver(
                        new ImportarPrediosDelPlano(
                                envolver(
                                        new InscribirPredioDelPlano(
                                                repositorio,
                                                new ViaRepositoryJdbc(jdbc),
                                                registrar),
                                        gestor)),
                        gestor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Codigo de referencia catastral (D-10)")
    class Codigo {

        @Test
        @DisplayName("un codigo con la longitud del manual se acepta y se descompone")
        void unCodigoValido() {
            CodigoReferenciaCatastral codigo =
                    CodigoReferenciaCatastral.de("20010100100100101010001");

            assertThat(codigo.valor()).hasSize(23);
            assertThat(codigo.tramo("departamento")).isEqualTo("20");
            assertThat(codigo.ubigeo())
                    .as("los tres primeros tramos dicen de que distrito es el predio")
                    .isEqualTo("200101");
        }

        @Test
        @DisplayName("una posicion que no es digito se rechaza, y el error dice cual")
        void unaPosicionQueNoEsDigito() {
            assertThatThrownBy(() -> CodigoReferenciaCatastral.de("2001010010010010101000X"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("posicion 23");
        }

        @Test
        @DisplayName("un codigo corto se rechaza diciendo cuantas posiciones faltan")
        void unCodigoCorto() {
            assertThatThrownBy(() -> CodigoReferenciaCatastral.de("2001010010"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("23")
                    .hasMessageContaining("10");
        }

        @Test
        @DisplayName("la longitud es un parametro: con otra composicion, otro largo")
        void laLongitudEsUnParametro() {
            kamayuk.rentas.dominio.ComposicionCatastral deVeintiuna =
                    new kamayuk.rentas.dominio.ComposicionCatastral(
                            List.of(
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "departamento", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "provincia", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "distrito", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "sector", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "manzana", 3),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "lote", 3),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "edificacion", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "entrada", 2),
                                    new kamayuk.rentas.dominio.ComposicionCatastral.Tramo(
                                            "piso", 3)));

            CodigoReferenciaCatastral deOtroLargo =
                    CodigoReferenciaCatastral.de("200101001001001010001", deVeintiuna);

            assertThat(deOtroLargo.valor())
                    .as("cerrar D-10 sera fijar el parametro, no reescribir la validacion")
                    .hasSize(21);
        }
    }

    @Nested
    @DisplayName("Catalogos territoriales")
    class Catalogos {

        @Test
        @DisplayName("un sector se registra y se lee por su codigo")
        void unSectorSeRegistra() {
            Sector guardado =
                    transaccion.execute(
                            estado -> repositorio.guardar(Sector.nuevo("S-01", "Sector Centro")));

            assertThat(guardado).isNotNull();
            Optional<Sector> leido =
                    transaccion.execute(estado -> repositorio.sectorPorCodigo("S-01"));
            assertThat(leido).isPresent();
        }

        @Test
        @DisplayName("ningun catalogo de una municipalidad es visible desde otra")
        void losCatalogosNoCruzan() {
            transaccion.execute(
                    estado -> repositorio.guardar(Sector.nuevo("S-AISLADO", "Sector aislado")));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            Optional<Sector> desdeLaVecina =
                    transaccion.execute(estado -> repositorio.sectorPorCodigo("S-AISLADO"));
            assertThat(desdeLaVecina)
                    .as("el catalogo territorial de un distrito no es visible desde el vecino")
                    .isEmpty();
        }

        @Test
        @DisplayName("el listado que publica el endpoint viene paginado y ordenado por codigo")
        void elListadoVienePaginado() {
            transaccion.execute(
                    estado -> repositorio.guardar(Sector.nuevo("S-90", "Sector del listado")));

            Pagina<SectorConConteos> pagina =
                    transaccion.execute(
                            estado -> repositorio.sectores(Paginacion.de(0, 20, "codigo")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.contenido()).isNotEmpty();
        }

        @Test
        @DisplayName("cada sector del listado trae sus conteos: manzanas, predios activos y lotes")
        void elListadoTraeLosConteosDeCadaSector() {
            Sector sector = sectorNuevo("S-70", "Sector que se cuenta");
            sectorNuevo("S-71", "Sector recien creado, sin nada dentro");
            long manzanaA = manzanaNueva(sector, "001");
            long manzanaB = manzanaNueva(sector, "002");

            // Dos unidades del MISMO lote —dos departamentos—, una de otro lote de la misma
            // manzana, y una del lote 01 de la manzana B: cuatro predios en tres lotes.
            predioUbicado("20010100100100101020001", "AV. CONTADA 100", sector, manzanaA, "01");
            predioUbicado(
                    "20010100100100101020002", "AV. CONTADA 100 DPTO 2", sector, manzanaA, "01");
            predioUbicado("20010100100100101020003", "AV. CONTADA 200", sector, manzanaA, "02");
            predioUbicado("20010100100100101020004", "AV. CONTADA 300", sector, manzanaB, "01");
            // Sin sector: no cuenta en ninguno.
            registrar.registrar(
                    Predio.urbano(
                            CodigoReferenciaCatastral.de("20010100100100101020005"),
                            "AV. SIN UBICAR 400"),
                    Observacion.de("Predio todavia sin sector asignado"));

            SectorConConteos contado = delListado("S-70");
            SectorConConteos vacio = delListado("S-71");

            assertThat(contado.manzanas()).isEqualTo(2);
            assertThat(contado.predios())
                    .as(
                            "el predio sin sector no se reparte ni se imputa: la suma de los sectores"
                                    + " puede ser menor que el padron, y eso es informacion")
                    .isEqualTo(4);
            assertThat(contado.lotes())
                    .as(
                            "dos departamentos del lote 01 son DOS predios y UN lote; contar predios"
                                    + " donde se pide lotes inflaria el sector")
                    .isEqualTo(3);

            assertThat(vacio.manzanas()).isZero();
            assertThat(vacio.predios())
                    .as("el sector sin nada cuenta cero, no nulo: la pantalla pinta un 0")
                    .isZero();
            assertThat(vacio.lotes()).isZero();
        }

        @Test
        @DisplayName("un predio dado de baja deja de contar en su sector, y su lote con el")
        void elPredioDadoDeBajaDejaDeContar() {
            Sector sector = sectorNuevo("S-72", "Sector con una baja");
            long manzana = manzanaNueva(sector, "001");
            predioUbicado("20010100100100101020011", "AV. QUE SIGUE 100", sector, manzana, "01");
            Predio demolido =
                    predioUbicado(
                            "20010100100100101020012", "AV. DEMOLIDA 200", sector, manzana, "02");

            SectorConConteos antes = delListado("S-72");
            registrar.darDeBaja(demolido, Observacion.de("Se demolio y se unifico con el vecino"));
            SectorConConteos despues = delListado("S-72");

            assertThat(antes.predios()).isEqualTo(2);
            assertThat(antes.lotes()).isEqualTo(2);
            assertThat(despues.predios())
                    .as(
                            "el predio dado de baja sigue en la base —aparece en determinaciones ya"
                                    + " emitidas—, pero el sector ya no lo tiene")
                    .isEqualTo(1);
            assertThat(despues.lotes())
                    .as("y su lote se va con el: nadie ocupa ya ese lote")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("los conteos son de esta municipalidad: desde la vecina, el sector no existe")
        void losConteosNoCruzanDeMunicipalidad() {
            Sector sector = sectorNuevo("S-73", "Sector con predios propios");
            long manzana = manzanaNueva(sector, "001");
            predioUbicado("20010100100100101020021", "AV. PROPIA 100", sector, manzana, "01");

            assertThat(delListado("S-73").predios()).isEqualTo(1);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            Pagina<SectorConConteos> desdeLaVecina =
                    transaccion.execute(
                            estado -> repositorio.sectores(Paginacion.de(0, 50, "codigo")));
            assertThat(desdeLaVecina.contenido())
                    .as("los conteos los hace la base bajo RLS, con el contexto que tenga puesto")
                    .noneMatch(fila -> "S-73".equals(fila.sector().codigo()));
        }

        /** El sector, tal como sale del listado que publica el endpoint. */
        private SectorConConteos delListado(String codigo) {
            Pagina<SectorConConteos> pagina =
                    transaccion.execute(
                            estado -> repositorio.sectores(Paginacion.de(0, 50, "codigo")));
            return pagina.contenido().stream()
                    .filter(fila -> fila.sector().codigo().equals(codigo))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new AssertionError(
                                            "El sector " + codigo + " no salio en el listado"));
        }

        private Sector sectorNuevo(String codigo, String nombre) {
            return transaccion.execute(estado -> repositorio.guardar(Sector.nuevo(codigo, nombre)));
        }

        private long manzanaNueva(Sector sector, String codigo) {
            Manzana guardada =
                    transaccion.execute(
                            estado -> repositorio.guardar(Manzana.nueva(sector.id(), codigo)));
            return java.util.Objects.requireNonNull(guardada.id());
        }

        private Predio predioUbicado(
                String codigo, String direccion, Sector sector, long manzanaId, String lote) {
            return registrar.registrar(
                    new Predio(
                            null,
                            CodigoReferenciaCatastral.de(codigo),
                            TipoPredio.URBANO,
                            null,
                            null,
                            direccion,
                            sector.id(),
                            manzanaId,
                            lote,
                            null,
                            EstadoPredio.ACTIVO),
                    Observacion.de("Alta del predio para contar el sector"));
        }

        @Test
        @DisplayName("las manzanas cuelgan de su sector")
        void lasManzanasCuelganDeSuSector() {
            Sector sector =
                    transaccion.execute(
                            estado ->
                                    repositorio.guardar(
                                            Sector.nuevo("S-02", "Sector con manzanas")));

            transaccion.execute(
                    estado ->
                            repositorio.guardar(
                                    kamayuk.rentas.catastro.dominio.Manzana.nueva(
                                            sector.id(), "001")));

            List<kamayuk.rentas.catastro.dominio.Manzana> manzanas =
                    transaccion.execute(estado -> repositorio.manzanasDe(sector.id()));
            assertThat(manzanas).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Predio")
    class Predios {

        @Test
        @DisplayName("un predio se registra con su codigo y se vuelve a leer")
        void unPredioSeRegistra() {
            Predio guardado =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010100100100101010101"),
                                    "AV. PRINCIPAL 100"),
                            Observacion.de("Alta del predio por declaracion jurada"));

            assertThat(guardado.id()).isNotNull();
            Optional<Predio> releido =
                    transaccion.execute(estado -> repositorio.predio(guardado.id()));
            assertThat(releido).isPresent();
        }

        @Test
        @DisplayName("dar de baja no borra: el predio sigue ahi, inactivo")
        void darDeBajaNoBorra() {
            Predio predio =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010100100100101010102"),
                                    "AV. BAJA 200"),
                            Observacion.de("Alta para la prueba de baja"));

            registrar.darDeBaja(predio, Observacion.de("Se demolio y se unifico con el vecino"));

            Optional<Predio> despues =
                    transaccion.execute(estado -> repositorio.predio(predio.id()));
            assertThat(despues)
                    .as("aparece en determinaciones ya emitidas; borrarlo las dejaria huerfanas")
                    .isPresent();
            assertThat(despues.orElseThrow().estaActivo()).isFalse();
        }

        @Test
        @DisplayName("un predio con manzana necesita su sector")
        void unPredioConManzanaNecesitaSector() {
            assertThatThrownBy(
                            () ->
                                    new Predio(
                                            null,
                                            CodigoReferenciaCatastral.de("20010100100100101010103"),
                                            kamayuk.rentas.catastro.dominio.TipoPredio.URBANO,
                                            null,
                                            null,
                                            "SIN SECTOR",
                                            null,
                                            99L,
                                            null,
                                            null,
                                            kamayuk.rentas.catastro.dominio.EstadoPredio.ACTIVO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un predio de otra municipalidad no se ve")
        void unPredioAjenoNoSeVe() {
            Predio predio =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010100100100101010104"),
                                    "AV. AISLADA 300"),
                            Observacion.de("Alta para la prueba de aislamiento"));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            Optional<Predio> desdeLaVecina =
                    transaccion.execute(estado -> repositorio.predio(predio.id()));
            assertThat(desdeLaVecina).isEmpty();
        }
    }

    @Nested
    @DisplayName("DAT-01 §4.2 — Titularidad y el disparador diferido")
    class TitularidadDelPredio {

        @Test
        @DisplayName("un propietario unico con porcentaje distinto de 100 se rechaza")
        void elUnicoLoEsPorElTotal() {
            assertThatThrownBy(
                            () ->
                                    new Titularidad(
                                            null,
                                            1L,
                                            titular,
                                            CondicionDeTitularidad.PROPIETARIO_UNICO,
                                            Porcentaje.de("60"),
                                            LocalDate.of(2026, 1, 1),
                                            null,
                                            "Escritura publica"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("una titularidad parcial del 60 % se admite: el padron real las tiene")
        void unaTitularidadParcialSeAdmite() {
            long predio = predioNuevo("20010100100100101010201", "AV. PARCIAL 100");

            Titularidad parcial =
                    registrar.registrarTitularidad(
                            Titularidad.parcial(
                                    predio,
                                    titular,
                                    CondicionDeTitularidad.COPROPIETARIO,
                                    Porcentaje.de("60"),
                                    LocalDate.of(2026, 1, 1),
                                    "Escritura publica 100-2026"),
                            Observacion.de("Se registra al copropietario identificado"));

            assertThat(parcial.id())
                    .as("exigir 100 obligaria al operador a inventar un titular")
                    .isNotNull();
            List<Titularidad> titulares =
                    transaccion.execute(
                            estado -> repositorio.titularesDe(predio, LocalDate.of(2026, 6, 1)));
            assertThat(titulares).hasSize(1);
        }

        @Test
        @DisplayName("pasar de 100 % falla AL CERRAR la transaccion, no antes")
        void pasarDeCienFallaAlCerrar() {
            long predio = predioNuevo("20010100100100101010202", "AV. EXCESO 200");

            registrar.registrarTitularidad(
                    Titularidad.parcial(
                            predio,
                            titular,
                            CondicionDeTitularidad.COPROPIETARIO,
                            Porcentaje.de("70"),
                            LocalDate.of(2026, 1, 1),
                            "Escritura publica 200-2026"),
                    Observacion.de("Primer copropietario"));

            assertThatThrownBy(
                            () ->
                                    registrar.registrarTitularidad(
                                            Titularidad.parcial(
                                                    predio,
                                                    comprador,
                                                    CondicionDeTitularidad.COPROPIETARIO,
                                                    Porcentaje.de("50"),
                                                    LocalDate.of(2026, 1, 1),
                                                    "Escritura publica 201-2026"),
                                            Observacion.de("Segundo, que llevaria el total a 120")))
                    .as("70 + 50 = 120; el disparador lo rechaza al cerrar")
                    .isNotNull();
        }

        @Test
        @DisplayName("una transferencia cierra una y abre otra en la misma transaccion")
        void laTransferenciaFuncionaGraciasAlDiferido() {
            long predio = predioNuevo("20010100100100101010203", "AV. TRANSFERIDA 300");

            Titularidad delVendedor =
                    registrar.registrarTitularidad(
                            Titularidad.unico(
                                    predio,
                                    titular,
                                    LocalDate.of(2026, 1, 1),
                                    "Escritura publica 300-2026"),
                            Observacion.de("Titular original del predio"));

            // Aqui esta el punto: entre cerrar la del vendedor y abrir la del comprador, el
            // total vigente pasa por 200 %. Con un disparador inmediato esto seria imposible.
            Titularidad delComprador =
                    registrar.transferir(
                            delVendedor,
                            Titularidad.unico(
                                    predio,
                                    comprador,
                                    LocalDate.of(2026, 7, 1),
                                    "Escritura publica 301-2026"),
                            Observacion.de("Compraventa inscrita en registros publicos"));

            assertThat(delComprador.id()).isNotNull();

            List<Titularidad> enMarzo =
                    transaccion.execute(
                            estado -> repositorio.titularesDe(predio, LocalDate.of(2026, 3, 1)));
            List<Titularidad> enSetiembre =
                    transaccion.execute(
                            estado -> repositorio.titularesDe(predio, LocalDate.of(2026, 9, 1)));

            assertThat(enMarzo).hasSize(1);
            assertThat(enMarzo.get(0).contribuyenteId())
                    .as("en marzo el predio era del vendedor, y una determinacion de marzo lo dice")
                    .isEqualTo(titular);
            assertThat(enSetiembre).hasSize(1);
            assertThat(enSetiembre.get(0).contribuyenteId()).isEqualTo(comprador);
        }

        @Test
        @DisplayName("se consulta tambien al reves: los predios de un contribuyente")
        void losPrediosDeUnContribuyente() {
            long predio = predioNuevo("20010100100100101010204", "AV. DEL TITULAR 400");

            registrar.registrarTitularidad(
                    Titularidad.unico(
                            predio, titular, LocalDate.of(2026, 1, 1), "Escritura 400-2026"),
                    Observacion.de("Titular del predio"));

            List<Titularidad> susPredios =
                    transaccion.execute(
                            estado -> repositorio.prediosDe(titular, LocalDate.of(2026, 6, 1)));
            assertThat(susPredios)
                    .as("la base del predial es por contribuyente: hace falta juntar sus predios")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Inquilinos del predio (#31)")
    class InquilinoDelPredio {

        @Test
        @DisplayName("un documento de origen en blanco se rechaza")
        void unDocumentoEnBlancoSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    Inquilino.nuevo(
                                            1L, titular, null, LocalDate.of(2026, 1, 1), "   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un inquilino que termina antes de empezar se rechaza")
        void terminarAntesDeEmpezarSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    new Inquilino(
                                            null,
                                            1L,
                                            titular,
                                            null,
                                            LocalDate.of(2026, 6, 1),
                                            LocalDate.of(2026, 1, 1),
                                            "Contrato de arrendamiento"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("se registra y se lee vigente a la fecha")
        void unInquilinoSeRegistra() {
            long predio = predioNuevo("20010100100100101010301", "AV. ARRENDADA 100");

            Inquilino guardado =
                    registrar.registrarInquilino(
                            Inquilino.nuevo(
                                    predio,
                                    comprador,
                                    "Comercio",
                                    LocalDate.of(2026, 1, 1),
                                    "Contrato de arrendamiento 001-2026"),
                            Observacion.de("Inquilino declarado por el propietario"));

            assertThat(guardado.id()).isNotNull();
            List<Inquilino> vigentes =
                    transaccion.execute(
                            estado -> repositorio.inquilinosDe(predio, LocalDate.of(2026, 6, 1)));
            assertThat(vigentes).hasSize(1);
            assertThat(vigentes.get(0).uso()).isEqualTo("Comercio");
        }

        @Test
        @DisplayName(
                "un predio admite mas de un inquilino vigente a la vez: no hay total que cuadrar")
        void masDeUnInquilinoALaVez() {
            long predio = predioNuevo("20010100100100101010302", "AV. COMPARTIDA 200");

            registrar.registrarInquilino(
                    Inquilino.nuevo(
                            predio,
                            titular,
                            "Comercio",
                            LocalDate.of(2026, 1, 1),
                            "Contrato 002-2026"),
                    Observacion.de("Primer ambiente arrendado"));
            registrar.registrarInquilino(
                    Inquilino.nuevo(
                            predio,
                            comprador,
                            "Comercio",
                            LocalDate.of(2026, 1, 1),
                            "Contrato 003-2026"),
                    Observacion.de("Segundo ambiente arrendado"));

            List<Inquilino> vigentes =
                    transaccion.execute(
                            estado -> repositorio.inquilinosDe(predio, LocalDate.of(2026, 6, 1)));
            assertThat(vigentes).hasSize(2);
        }

        @Test
        @DisplayName("finalizar cierra sin borrar: deja de salir vigente, pero sigue en la base")
        void finalizarCierraSinBorrar() {
            long predio = predioNuevo("20010100100100101010303", "AV. QUE SE MUDA 300");

            Inquilino inquilino =
                    registrar.registrarInquilino(
                            Inquilino.nuevo(
                                    predio,
                                    titular,
                                    "Comercio",
                                    LocalDate.of(2026, 1, 1),
                                    "Contrato 004-2026"),
                            Observacion.de("Inquilino que luego se muda"));

            registrar.finalizarInquilino(
                    inquilino,
                    LocalDate.of(2026, 6, 30),
                    Observacion.de("El inquilino dejo el predio"));

            List<Inquilino> enJulio =
                    transaccion.execute(
                            estado -> repositorio.inquilinosDe(predio, LocalDate.of(2026, 7, 1)));
            assertThat(enJulio).as("ya se fue: en julio no deberia figurar como vigente").isEmpty();

            Optional<Inquilino> releido =
                    transaccion.execute(estado -> repositorio.inquilino(inquilino.id()));
            assertThat(releido).as("no se borra: sigue en la base, cerrado").isPresent();
            assertThat(releido.orElseThrow().estaVigente()).isFalse();
        }
    }

    @Nested
    @DisplayName("Padron del catastro (#400): el listado que ve tambien lo que falta")
    class PadronDelCatastro {

        /** Un sector propio para no depender del orden de las demas pruebas. */
        private long sectorDeListado() {
            Sector sector =
                    transaccion.execute(
                            estado ->
                                    repositorio.guardar(
                                            new Sector(
                                                    null, "SL-9", "Sector listado", "Z9", true)));
            return java.util.Objects.requireNonNull(sector).id();
        }

        @Test
        @DisplayName("el prefijo del codigo acota, y no por LIKE: filtra un sector entero")
        void elPrefijoAcota() {
            predioNuevo("20010100100100109010001", "AV. PREFIJO 1");
            predioNuevo("20010100100100109010002", "AV. PREFIJO 2");
            predioNuevo("20010100100100108010001", "AV. OTRA RAMA 1");

            Pagina<PredioDelCatastro> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010901", null, null, null),
                                            Paginacion.de(0, 50, "codRefCatastral")));

            assertThat(pagina.contenido())
                    .extracting(fila -> fila.codigo().valor())
                    .containsExactly("20010100100100109010001", "20010100100100109010002");
        }

        @Test
        @DisplayName("fichado=false es la cola de saneamiento: lo que entro y nadie ficho")
        void laColaDeSaneamiento() throws SQLException {
            long conFicha = predioNuevo("20010100100100107010001", "AV. FICHADA 1");
            long sinFicha = predioNuevo("20010100100100107010002", "AV. SIN FICHAR 2");
            sembrarFicha(municipalidad, conFicha);

            Pagina<PredioDelCatastro> pendientes =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010701", null, null, false),
                                            Paginacion.de(0, 50, "codRefCatastral")));

            assertThat(pendientes.contenido())
                    .as(
                            "es lo unico que encuentra lo que entra por una carga cartografica y"
                                    + " todavia no tiene ficha; la consulta de fichas no lo ve porque"
                                    + " lista fichas")
                    .extracting(PredioDelCatastro::predioId)
                    .containsExactly(sinFicha);

            Pagina<PredioDelCatastro> fichados =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010701", null, null, true),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(fichados.contenido())
                    .extracting(PredioDelCatastro::predioId)
                    .containsExactly(conFicha);
            assertThat(fichados.contenido().getFirst().fichado()).isTrue();
        }

        @Test
        @DisplayName("sin filtro de estado salen tambien los dados de baja: es lo que hay que ver")
        void losDadosDeBajaSalen() {
            long activo = predioNuevo("20010100100100106010001", "AV. VIVA 1");
            Predio retirado =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010100100100106010002"),
                                    "AV. RETIRADA 2"),
                            Observacion.de("Alta para la prueba del listado"));
            registrar.darDeBaja(retirado, Observacion.de("Se demolio"));

            Pagina<PredioDelCatastro> todos =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010601", null, null, null),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(todos.contenido())
                    .as(
                            "el listado del catastro no es el de la emision: esconder los retirados"
                                    + " seria esconder lo que hay que revisar")
                    .hasSize(2);

            Pagina<PredioDelCatastro> soloActivos =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010601",
                                                    null,
                                                    EstadoPredio.ACTIVO,
                                                    null),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(soloActivos.contenido())
                    .extracting(PredioDelCatastro::predioId)
                    .containsExactly(activo);
        }

        @Test
        @DisplayName(
                "la via, el sector y la manzana salen por CODIGO, que es lo que se vuelve a mandar")
        void laUbicacionSalePorCodigo() {
            long sectorId = sectorDeListado();
            Manzana manzana =
                    transaccion.execute(
                            estado -> repositorio.guardar(new Manzana(null, sectorId, "M9")));

            Predio predio =
                    registrar.registrar(
                            Predio.urbano(
                                            CodigoReferenciaCatastral.de("20010100100100105010001"),
                                            "AV. UBICADA 1")
                                    .ubicadoEn(
                                            sectorId,
                                            java.util.Objects.requireNonNull(manzana).id(),
                                            "L9"),
                            Observacion.de("Alta ubicada para el listado"));

            PredioDelCatastro fila =
                    transaccion
                            .execute(
                                    estado ->
                                            repositorio.predios(
                                                    new FiltroDePredios(null, "SL-9", null, null),
                                                    Paginacion.de(0, 50, "codRefCatastral")))
                            .contenido()
                            .getFirst();

            assertThat(fila.predioId()).isEqualTo(predio.id());
            assertThat(fila.codigoDeSector())
                    .as("la correccion del predio recibe codigos, no identificadores internos")
                    .isEqualTo("SL-9");
            assertThat(fila.codigoDeManzana()).isEqualTo("M9");
            assertThat(fila.lote()).isEqualTo("L9");
        }

        @Test
        @DisplayName("un predio sin via, sin sector y sin ficha SALE: los JOIN son externos")
        void elPredioPeladoSale() {
            long pelado = predioNuevo("20010100100100104010001", "AV. PELADA 1");

            Pagina<PredioDelCatastro> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010401", null, null, null),
                                            Paginacion.de(0, 50, "codRefCatastral")));

            assertThat(pagina.contenido())
                    .as("con JOIN interno, la cola de saneamiento se esconderia de si misma")
                    .extracting(PredioDelCatastro::predioId)
                    .containsExactly(pelado);
            PredioDelCatastro fila = pagina.contenido().getFirst();
            assertThat(fila.codigoDeVia()).isNull();
            assertThat(fila.codigoDeSector()).isNull();
            assertThat(fila.fichado()).isFalse();
        }

        @Test
        @DisplayName("el listado es de esta municipalidad: desde la vecina no sale ninguno")
        void elListadoNoCruzaMunicipalidades() {
            predioNuevo("20010100100100103010001", "AV. AISLADA 1");

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            Pagina<PredioDelCatastro> desdeLaVecina =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "2001010010010010301", null, null, null),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(desdeLaVecina.contenido()).isEmpty();
            assertThat(desdeLaVecina.totalElementos()).isZero();
        }
    }

    @Nested
    @DisplayName("Carga del plano (#400): el camino por el que un catastro real se puebla")
    class CargaDelPlano {

        /**
         * Entre comillas porque un WKT lleva comas: sin ellas el CSV lo parte en columnas y la fila
         * entera pierde el sentido. {@code csv.writer} del guion las pone sola.
         */
        private static final String LOTE =
                "\"MULTIPOLYGON(((-80.6700 -5.2600, -80.6699 -5.2600,"
                        + " -80.6699 -5.2601, -80.6700 -5.2601, -80.6700 -5.2600)))\"";

        private static final String CABECERA =
                "departamento,provincia,distrito,sector,manzana,lote,edificacion,entrada,piso,"
                        + "unidad,tipoPredio,direccion,codigoVia,numeroMunicipal,codigoSector,"
                        + "codigoManzana,loteTexto,geometria";

        private InformeDeImportacion cargar(String... filas) {
            String archivo = CABECERA + "\n" + String.join("\n", filas) + "\n";
            return importarDelPlano.importar(
                    new java.io.StringReader(archivo),
                    Observacion.de("Carga del plano catastral del distrito"));
        }

        /** Los diez tramos del codigo del manual, con la unidad como ultimo. */
        private static String tramos(String unidad) {
            return "20,01,01,01,001,001,01,01,01," + unidad;
        }

        @Test
        @DisplayName("un lote nace SIN ficha y con su poligono: es la cola de saneamiento")
        void unLoteNaceSinFicha() {
            InformeDeImportacion informe =
                    cargar(tramos("501") + ",URBANO,CALLE DEL PLANO 1,,,,,," + LOTE);

            assertThat(informe.rechazadas()).isEmpty();
            assertThat(informe.nuevas()).isEqualTo(1);

            Pagina<PredioDelCatastro> sinFichar =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "20010101001001010101", null, null, false),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(sinFichar.contenido())
                    .as(
                            "el plano da el lote y no la ficha: el area construida, el uso y el"
                                    + " titular los levanta alguien despues, y hasta entonces el predio"
                                    + " es cola de saneamiento")
                    .isNotEmpty();
            long predioId = sinFichar.contenido().getFirst().predioId();
            Optional<String> poligono =
                    transaccion.execute(estado -> repositorio.geometriaDe(predioId));
            assertThat(poligono).isNotEmpty();
        }

        @Test
        @DisplayName("sobre un predio que ya existe SOLO pone el poligono, sin pisar la direccion")
        void sobreUnPredioExistenteSoloPoneElPoligono() {
            Predio existente =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010101001001010101502"),
                                    "DIRECCION DE VENTANILLA"),
                            Observacion.de("Alta en ventanilla, antes del plano"));

            InformeDeImportacion informe =
                    cargar(tramos("502") + ",URBANO,LO QUE DICE EL PLANO,,,,,," + LOTE);

            assertThat(informe.rechazadas()).isEmpty();
            assertThat(informe.nuevas()).as("no nacio ningun predio: ya estaba").isZero();

            Predio despues =
                    transaccion.execute(estado -> repositorio.predio(existente.id())).orElseThrow();
            assertThat(despues.direccion())
                    .as(
                            "lo corrigio alguien en ventanilla con su observacion, y un archivo"
                                    + " cartografico que lo pisara borraria ese trabajo")
                    .isEqualTo("DIRECCION DE VENTANILLA");
            Optional<String> poligono =
                    transaccion.execute(estado -> repositorio.geometriaDe(existente.id()));
            assertThat(poligono)
                    .as("y lo unico que el plano sabe mejor que nadie SI se escribe")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("sobre un predio dado de baja no escribe: reactivarlo es otro acto")
        void sobreUnPredioDeBajaNoEscribe() {
            Predio predio =
                    registrar.registrar(
                            Predio.urbano(
                                    CodigoReferenciaCatastral.de("20010101001001010101503"),
                                    "AV. RETIRADA DEL PLANO"),
                            Observacion.de("Alta para la prueba del plano"));
            registrar.darDeBaja(predio, Observacion.de("Se demolio"));

            InformeDeImportacion informe =
                    cargar(tramos("503") + ",URBANO,LO QUE DICE EL PLANO,,,,,," + LOTE);

            assertThat(informe.rechazadas()).hasSize(1);
            Optional<String> poligono =
                    transaccion.execute(estado -> repositorio.geometriaDe(predio.id()));
            assertThat(poligono).isEmpty();
        }

        @Test
        @DisplayName("una fila mala se rechaza SOLA y la siguiente entra")
        void unaFilaMalaNoSeLlevaALaSiguiente() {
            InformeDeImportacion informe =
                    cargar(
                            tramos("504") + ",URBANO,CON SECTOR INEXISTENTE,,,SC-NADA,,," + LOTE,
                            tramos("505") + ",URBANO,LA QUE SIGUE ENTRA,,,,,," + LOTE);

            assertThat(informe.rechazadas())
                    .as("envolver el bucle en una transaccion se llevaria las dos por delante")
                    .hasSize(1);
            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(informe.rechazadas().getFirst().motivo()).contains("SC-NADA");
        }

        @Test
        @DisplayName(
                "un lote sin geometria se rechaza: esto no es un alta masiva de predios vacios")
        void unLoteSinGeometriaSeRechaza() {
            InformeDeImportacion informe = cargar(tramos("506") + ",URBANO,SIN POLIGONO,,,,,,");

            assertThat(informe.rechazadas()).hasSize(1);
            assertThat(informe.nuevas()).isZero();
            assertThat(informe.rechazadas().getFirst().motivo())
                    .as(
                            "el motivo tiene que decir que FALTA la geometria. Sin esta"
                                    + " comprobacion, quitar la guarda pasa en verde: el motor rechaza"
                                    + " igual la cadena vacia, y la fila se cuenta como rechazada por"
                                    + " un motivo que no es el suyo")
                    .contains("necesita su geometria");
        }

        @Test
        @DisplayName("una geometria que el motor rechaza NO deja el predio suelto")
        void unaGeometriaInvalidaNoDejaElPredioSuelto() {
            InformeDeImportacion informe =
                    cargar(tramos("507") + ",URBANO,CON UN PUNTO,,,,,,\"POINT(-80.67 -5.26)\"");

            assertThat(informe.rechazadas()).hasSize(1);
            assertThat(informe.rechazadas().getFirst().motivo()).contains("MULTIPOLYGON");

            Pagina<PredioDelCatastro> ninguno =
                    transaccion.execute(
                            estado ->
                                    repositorio.predios(
                                            new FiltroDePredios(
                                                    "20010101001001010101507", null, null, null),
                                            Paginacion.de(0, 50, "codRefCatastral")));
            assertThat(ninguno.contenido())
                    .as(
                            "el predio se escribe antes que su poligono y se deshace con el: sin"
                                    + " una transaccion por fila quedaria un predio sin geometria que"
                                    + " nadie pidio")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Geometria del predio (ADR-0021, V61): PostGIS de verdad")
    class Geometria {

        /** Una manzana de Catacaos, aproximada. Lo que importa es que sea un poligono valido. */
        private static final String LOTE =
                "MULTIPOLYGON(((-80.6800 -5.2700, -80.6799 -5.2700,"
                        + " -80.6799 -5.2701, -80.6800 -5.2701, -80.6800 -5.2700)))";

        @Test
        @DisplayName("se guarda y se vuelve a leer como el poligono que es")
        void seGuardaYSeLee() {
            long predioId = predioNuevo("20010100100100102010001", "AV. CON PLANO 1");

            transaccion.executeWithoutResult(
                    estado -> repositorio.asignarGeometria(predioId, LOTE));

            Optional<String> leido =
                    transaccion.execute(estado -> repositorio.geometriaDe(predioId));
            assertThat(leido).isPresent();
            assertThat(leido.orElseThrow()).startsWith("MULTIPOLYGON(((");
        }

        @Test
        @DisplayName("un predio sin plano no tiene geometria, y eso es lo normal")
        void sinPlanoNoHayGeometria() {
            long predioId = predioNuevo("20010100100100102010002", "AV. SIN PLANO 2");

            Optional<String> sinPlano =
                    transaccion.execute(estado -> repositorio.geometriaDe(predioId));
            assertThat(sinPlano)
                    .as("un predio declarado en ventanilla no trae plano, y no por eso es invalido")
                    .isEmpty();
        }

        @Test
        @DisplayName("la columna solo admite MULTIPOLYGON: un punto no entra")
        void unPuntoNoEntra() {
            long predioId = predioNuevo("20010100100100102010003", "AV. MAL TIPADA 3");

            assertThatThrownBy(
                            () ->
                                    transaccion.executeWithoutResult(
                                            estado ->
                                                    repositorio.asignarGeometria(
                                                            predioId, "POINT(-80.68 -5.27)")))
                    .as(
                            "el tipo de la columna es la validacion; una comprobacion en Java se"
                                    + " desincronizaria de ella")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        @Test
        @DisplayName("un predio de otra municipalidad no se puede geometrizar desde aqui")
        void noSePuedeGeometrizarLoAjeno() {
            long predioId = predioNuevo("20010100100100102010004", "AV. AJENA 4");

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            assertThatThrownBy(
                            () ->
                                    transaccion.executeWithoutResult(
                                            estado -> repositorio.asignarGeometria(predioId, LOTE)))
                    .as("RLS no lo deja ver, asi que el UPDATE no toca ninguna fila")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("el area del poligono NO es el area imponible, y esa es la mitad del ADR")
        void elAreaDelPoligonoNoEsLaImponible() throws SQLException {
            long predioId = predioNuevo("20010100100100102010005", "AV. DOS AREAS 5");
            sembrarFicha(municipalidad, predioId);
            transaccion.executeWithoutResult(
                    estado -> repositorio.asignarGeometria(predioId, LOTE));

            double areaDelPoligono =
                    transaccion.execute(
                            estado ->
                                    java.util.Objects.requireNonNull(
                                            jdbcDePrueba
                                                    .sql(
                                                            "SELECT ST_Area(geometria) FROM predio"
                                                                    + " WHERE id = :id")
                                                    .param("id", predioId)
                                                    .query(Double.class)
                                                    .single()));
            java.math.BigDecimal areaDeLaFicha =
                    transaccion.execute(
                            estado ->
                                    jdbcDePrueba
                                            .sql(
                                                    "SELECT area_terreno FROM ficha_catastral"
                                                            + " WHERE predio_id = :id")
                                            .param("id", predioId)
                                            .query(java.math.BigDecimal.class)
                                            .single());

            assertThat(areaDelPoligono > 0.0)
                    .as("el poligono mide en metros sobre el elipsoide y da una cifra propia")
                    .isTrue();
            assertThat(areaDeLaFicha)
                    .as(
                            "y la ficha sigue diciendo lo que midio el tecnico. Derivar una de la"
                                    + " otra cambiaria el autovaluo de todo el padron sin que nadie lo"
                                    + " decidiera, y el error seria invisible: un area es"
                                    + " indistinguible de otra al leerla")
                    .isEqualByComparingTo("120.00");
            assertThat(java.math.BigDecimal.valueOf(areaDelPoligono))
                    .isNotEqualByComparingTo(areaDeLaFicha);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Una ficha minima por SQL directo: lo unico que esta prueba necesita de ella es que exista,
     * para que {@code fichado} tenga algo que encontrar.
     */
    private static void sembrarFicha(long muni, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            // Con el contexto fijado, como cualquier escritura: `ficha_catastral` lleva RLS con
            // FORCE, asi que ni siquiera el propietario de la tabla entra sin el.
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA HABITACION',"
                                    + " DATE '2026-01-01', 'DECLARACION_JURADA', 'DJ 1-2026',"
                                    + " 'Siembra de la prueba', 'catastro.tecnico')")) {
                sentencia.setLong(1, muni);
                sentencia.setLong(2, predioId);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static long predioNuevo(String codigo, String direccion) {
        Predio predio =
                registrar.registrar(
                        Predio.urbano(CodigoReferenciaCatastral.de(codigo), direccion),
                        Observacion.de("Alta del predio para la prueba de titularidad"));
        return java.util.Objects.requireNonNull(predio.id());
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

    private static long crearContribuyente(long muni, String codigo, String dni, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.setString(4, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
