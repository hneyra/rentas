package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.Familia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #43 — sin base de datos: lo que se verifica aqui es la orquestacion (cierra la version vigente,
 * guarda la nueva, audita). El aislamiento contra PostgreSQL real vive en {@code
 * CodigoInfraccionRepositoryJdbcTest}.
 */
@DisplayName("#43 — MantenerCatalogoDeInfracciones")
class MantenerCatalogoDeInfraccionesTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private RepositorioEnMemoria repositorio;
    private AuditoriaDeMentira auditoria;
    private MantenerCatalogoDeInfracciones servicio;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioEnMemoria();
        auditoria = new AuditoriaDeMentira();
        servicio = new MantenerCatalogoDeInfracciones(repositorio, auditoria, RELOJ);
    }

    @Test
    @DisplayName("registrar un codigo nuevo lo guarda y audita el alta")
    void registrarUnCodigoNuevoLoGuardaYAuditaElAlta() {
        CodigoInfraccion guardado =
                servicio.registrar(codigoDe(LocalDate.of(2026, 1, 1)), OBSERVACION);

        assertThat(guardado.id()).isNotNull();
        assertThat(repositorio.porId(guardado.id())).isPresent();
        assertThat(auditoria.ultima.operacion()).isEqualTo(Operacion.ALTA);
        assertThat(auditoria.ultima.tabla()).isEqualTo("codigo_infraccion");
    }

    @Test
    @DisplayName("modificar cierra la version vigente y crea una nueva; la anterior queda")
    void modificarCierraLaVersionVigenteYCreaUnaNueva() {
        CodigoInfraccion original =
                servicio.registrar(codigoDe(LocalDate.of(2026, 1, 1)), OBSERVACION);

        CodigoInfraccion nuevaVersion =
                CodigoInfraccion.nuevo(
                        Familia.TRANSITO,
                        "G-01",
                        "Exceso de velocidad, texto corregido",
                        Alicuota.de("10"),
                        "Retencion de licencia",
                        (short) 6,
                        "RNT art. 300, modificado por D.S. 002-2026",
                        LocalDate.of(2026, 7, 1));

        CodigoInfraccion actual =
                servicio.modificar(Familia.TRANSITO, "G-01", nuevaVersion, OBSERVACION);

        CodigoInfraccion anteriorReleida = repositorio.porId(original.id()).orElseThrow();
        assertThat(anteriorReleida.estaVigente())
                .as("la version anterior queda, no se pisa (regla 4)")
                .isFalse();
        assertThat(anteriorReleida.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(anteriorReleida.descripcion())
                .as("la fila anterior no cambia su contenido, solo su vigencia")
                .isEqualTo("Exceso de velocidad");

        assertThat(actual.id()).isNotEqualTo(original.id());
        assertThat(actual.porcentajeUit()).isEqualTo(Alicuota.de("10"));
        assertThat(auditoria.ultima.operacion()).isEqualTo(Operacion.MODIFICACION);
    }

    @Test
    @DisplayName("consultar a una fecha devuelve la version vigente entonces, no la ultima")
    void consultarAUnaFechaDevuelveLaVersionVigenteEntonces() {
        servicio.registrar(codigoDe(LocalDate.of(2026, 1, 1)), OBSERVACION);
        CodigoInfraccion nuevaVersion =
                CodigoInfraccion.nuevo(
                        Familia.TRANSITO,
                        "G-01",
                        "Exceso de velocidad, texto corregido",
                        Alicuota.de("10"),
                        null,
                        null,
                        "RNT art. 300, modificado",
                        LocalDate.of(2026, 7, 1));
        servicio.modificar(Familia.TRANSITO, "G-01", nuevaVersion, OBSERVACION);

        Optional<CodigoInfraccion> enMarzo =
                repositorio.vigenteA(Familia.TRANSITO, "G-01", LocalDate.of(2026, 3, 1));
        Optional<CodigoInfraccion> enAgosto =
                repositorio.vigenteA(Familia.TRANSITO, "G-01", LocalDate.of(2026, 8, 1));

        assertThat(enMarzo).isPresent();
        assertThat(enMarzo.get().porcentajeUit()).isEqualTo(Alicuota.de("8"));
        assertThat(enAgosto).isPresent();
        assertThat(enAgosto.get().porcentajeUit()).isEqualTo(Alicuota.de("10"));
    }

    @Test
    @DisplayName("modificar sin version vigente previa falla")
    void modificarSinVersionVigentePreviaFalla() {
        CodigoInfraccion nuevaVersion = codigoDe(LocalDate.of(2026, 7, 1));

        assertThatThrownBy(
                        () ->
                                servicio.modificar(
                                        Familia.TRANSITO, "G-99", nuevaVersion, OBSERVACION))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CodigoInfraccion codigoDe(LocalDate vigenciaDesde) {
        return CodigoInfraccion.nuevo(
                Familia.TRANSITO,
                "G-01",
                "Exceso de velocidad",
                Alicuota.de("8"),
                "Retencion de licencia",
                (short) 4,
                "RNT art. 300",
                vigenciaDesde);
    }

    /** Suficiente para probar la orquestacion: sin SQL, sin RLS, sin paginacion real. */
    private static final class RepositorioEnMemoria implements CodigoInfraccionRepository {

        private final Map<Long, CodigoInfraccion> filas = new HashMap<>();
        private long siguiente = 1;

        @Override
        public Optional<CodigoInfraccion> findById(long id) {
            return Optional.ofNullable(filas.get(id));
        }

        @Override
        public Optional<CodigoInfraccion> vigenteA(
                Familia familia, String codigo, LocalDate fecha) {
            return filas.values().stream()
                    .filter(c -> c.familia() == familia && c.codigo().equals(codigo))
                    .filter(c -> c.rigeEn(fecha))
                    .findFirst();
        }

        @Override
        public Pagina<CodigoInfraccion> buscar(
                CriterioDeCodigoInfraccion criterio, Paginacion paginacion) {
            List<CodigoInfraccion> todos = List.copyOf(filas.values());
            return Pagina.de(todos, paginacion, todos.size());
        }

        @Override
        public CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion) {
            long id = siguiente++;
            CodigoInfraccion guardado =
                    new CodigoInfraccion(
                            id,
                            codigoInfraccion.familia(),
                            codigoInfraccion.codigo(),
                            codigoInfraccion.descripcion(),
                            codigoInfraccion.porcentajeUit(),
                            codigoInfraccion.medida(),
                            codigoInfraccion.puntos(),
                            codigoInfraccion.baseLegal(),
                            codigoInfraccion.vigenciaDesde(),
                            codigoInfraccion.vigenciaHasta());
            filas.put(id, guardado);
            return guardado;
        }

        @Override
        public CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion) {
            filas.put(codigoInfraccion.id(), codigoInfraccion);
            return codigoInfraccion;
        }

        Optional<CodigoInfraccion> porId(long id) {
            return Optional.ofNullable(filas.get(id));
        }
    }

    private static final class AuditoriaDeMentira implements Auditoria {
        private RegistroDeAuditoria ultima;

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            this.ultima = registro;
        }
    }
}
