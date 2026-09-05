package kamayuk.rentas.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De donde sale la marca: de la fila, no de un archivo de configuracion (#122, ADR-0007).
 *
 * <p>Contra PostgreSQL y como {@code kamayuk_app}, porque las tres cosas que se verifican aqui solo
 * existen ahi: que la consulta resuelva la municipalidad por el parametro de sesion que fija {@code
 * SET LOCAL} —y no por un identificador que viaje en Java—, que una instalacion que atiende a
 * varias municipalidades no confunda el regimen de una con el de otra, y que la pregunta se pueda
 * hacer <b>sin traer transaccion</b>, porque quien la hace es un controlador que ya cerro la de su
 * lectura (#535).
 */
@DisplayName("#122 — El regimen de la instalacion sale de la base")
class RegimenDeLaInstalacionJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long deDemostracion;
    private static long real;
    private static long soloParaLaCache;

    /**
     * La municipalidad que <b>solo</b> pregunta la prueba de #535, y por un motivo que se descubrio
     * midiendo: con {@code real} —a la que preguntan otras dos pruebas— la cache ya sabia la
     * respuesta cuando llegaba, asi que quitarle la transaccion al codigo de produccion la dejaba
     * en VERDE. Una municipalidad propia garantiza la cache fria sea cual sea el orden de
     * ejecucion.
     */
    private static long soloSinTransaccion;

    private static RegimenDeLaInstalacion regimen;
    private static TransactionTemplate transaccion;
    private static TransaccionesContadas gestor;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        deDemostracion = crearMunicipalidad("400101", "Municipalidad de la marcha blanca", true);
        real = crearMunicipalidad("400102", "Municipalidad que ya opera", false);
        soloParaLaCache =
                crearMunicipalidad("400103", "Municipalidad que solo mide la cache", true);
        soloSinTransaccion =
                crearMunicipalidad(
                        "400104", "Municipalidad que solo se pregunta sin transaccion", false);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        gestor = new TransaccionesContadas(pool);
        regimen = new RegimenDeLaInstalacionJdbc(JdbcClient.create(pool), gestor);
        transaccion = new TransactionTemplate(gestor);
    }

    @AfterAll
    static void cerrar() {
        TenantContext.limpiar();
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("la municipalidad marcada emite marcado, y la que no, no")
    void cadaUnaConSuRegimen() {
        assertThat(regimenDe(deDemostracion))
                .as("es la fila la que lo dice, no una variable de entorno")
                .isTrue();
        assertThat(regimenDe(real)).isFalse();
    }

    @Test
    @DisplayName("la cache no deja que la primera municipalidad decida por las demas")
    void laCacheEsPorMunicipalidad() {
        // El fallo que esto atrapa no se ve probando con una sola municipalidad, y es el
        // peor de los posibles: una cache de un solo valor haria que la primera que
        // emitiera fijara el regimen de toda la instalacion. Con la de demostracion
        // primero, la que ya opera emitiria papeles marcados —molesto—; al reves, la de
        // la marcha blanca emitiria papeles SIN marca, que es justo lo que #122 impide.
        assertThat(regimenDe(deDemostracion)).isTrue();
        assertThat(regimenDe(real)).as("preguntada despues de una de demostracion").isFalse();
        assertThat(regimenDe(deDemostracion)).as("y preguntada de nuevo, no ha cambiado").isTrue();
    }

    @Test
    @DisplayName("sin contexto de tenant no responde: falla en vez de suponer")
    void sinContextoNoResponde() {
        TenantContext.limpiar();

        assertThatThrownBy(() -> regimen.esDeDemostracion())
                .as("suponer «no es de demostracion» seria emitir sin marca por descuido")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("una municipalidad que no esta en el registro no emite nada")
    void unaMunicipalidadQueNoEstaNoEmite() {
        // Sin saber el regimen no se puede decidir si el documento va marcado, y de las
        // dos respuestas posibles la comoda —«no es de demostracion»— es la que produce
        // un papel sin marca. Asi que no hay respuesta comoda: hay excepcion.
        assertThatThrownBy(() -> regimenDe(999_999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldria sin marca");
    }

    @Test
    @DisplayName("se puede preguntar SIN traer transaccion, que es como pregunta un controlador")
    void sinTransaccionDelLlamadorTambienResponde() {
        // Este es #535 entero. Un controlador que dibuja un documento ya cerro la
        // transaccion de su lectura, y `SET LOCAL app.municipalidad_id` muere con ella;
        // sin la que abre esta clase, la consulta revienta —«unrecognized configuration
        // parameter» sobre una conexion nueva, «invalid input syntax for type bigint: ""»
        // sobre una del pool que ya llevo el parametro— y el borde contesta 500.
        //
        // La municipalidad es SUYA y de nadie mas: preguntando por `real` —que otras dos
        // pruebas ya calentaron— esta misma prueba pasa en VERDE con la transaccion
        // quitada del codigo de produccion, porque la respuesta sale de la cache y no de
        // la base. Es la misma intermitencia que hace que el defecto de #535 no se vea
        // siempre en produccion: si el primer documento de la municipalidad sale por un
        // camino sano, los dieciocho endpoints rotos contestan 200 el resto de la vida
        // del proceso.
        TenantContext.fijar(new MunicipalidadId(soloSinTransaccion));
        try {
            assertThat(regimen.esDeDemostracion())
                    .as("la pregunta abre la suya cuando el llamador no trae ninguna")
                    .isFalse();
        } finally {
            TenantContext.limpiar();
        }
    }

    @Test
    @DisplayName("y no abre una transaccion por documento: solo la primera vez")
    void laCacheEvitaLaTransaccionYNoSoloLaConsulta() {
        // Es lo que sostiene que la transaccion se abra con TransactionTemplate DENTRO
        // del metodo y no con @Transactional en su borde. Con la anotacion, la primera
        // linea de abajo mediria 1 y la segunda tambien: `emitirEnLote` emite miles de
        // documentos y tomaria una conexion del pool por cada uno, que es exactamente lo
        // que el javadoc de RegimenDeLaInstalacion dice que no puede permitirse.
        TenantContext.fijar(new MunicipalidadId(soloParaLaCache));
        try {
            int antesDeLaPrimera = gestor.abiertas();
            assertThat(regimen.esDeDemostracion()).isTrue();
            int trasLaPrimera = gestor.abiertas();
            assertThat(regimen.esDeDemostracion()).isTrue();
            int trasLaSegunda = gestor.abiertas();

            assertThat(trasLaPrimera - antesDeLaPrimera)
                    .as("la primera pregunta si va a la base, y para eso necesita transaccion")
                    .isEqualTo(1);
            assertThat(trasLaSegunda - trasLaPrimera)
                    .as("la segunda la contesta la cache, y no abre ninguna")
                    .isZero();
        } finally {
            TenantContext.limpiar();
        }
    }

    private static boolean regimenDe(long municipalidad) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return Boolean.TRUE.equals(transaccion.execute(estado -> regimen.esDeDemostracion()));
        } finally {
            TenantContext.limpiar();
        }
    }

    /**
     * El gestor de siempre, contando cuantas transacciones <b>nuevas</b> abre.
     *
     * <p>Cuenta {@code doBegin}, que Spring solo invoca cuando no hay ninguna en curso: participar
     * en una ya abierta no pasa por ahi. Eso es justamente lo que hay que distinguir.
     */
    private static final class TransaccionesContadas extends TenantTransactionManager {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final AtomicInteger abiertas = new AtomicInteger();

        TransaccionesContadas(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            abiertas.incrementAndGet();
            super.doBegin(transaction, definition);
        }

        int abiertas() {
            return abiertas.get();
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre, boolean esDemostracion)
            throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo, es_demostracion)"
                                        + " VALUES (?, ?, 'DISTRITAL', ?) RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            sentencia.setBoolean(3, esDemostracion);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }
}
