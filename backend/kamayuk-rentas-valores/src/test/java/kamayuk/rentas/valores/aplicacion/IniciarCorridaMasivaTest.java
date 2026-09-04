package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #38 — la etapa "criterio", sin base de datos: todo o nada (RF-133). Ya sea que la lista venga
 * escrita a mano o de un archivo, un solo codigo invalido rechaza la corrida entera.
 */
@DisplayName("#38 — IniciarCorridaMasiva")
class IniciarCorridaMasivaTest {

    private static final Observacion OBSERVACION = Observacion.de("Corrida de prueba");
    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 3, 15).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    private ContribuyentesDeMentira contribuyentes;
    private RepositorioDeMentira repositorio;
    private IniciarCorridaMasiva servicio;

    @BeforeEach
    void preparar() {
        contribuyentes = new ContribuyentesDeMentira();
        repositorio = new RepositorioDeMentira();
        servicio = new IniciarCorridaMasiva(repositorio, contribuyentes, RELOJ);
    }

    @Test
    @DisplayName("por seleccion, con codigos validos, registra la corrida")
    void porSeleccionConCodigosValidos() {
        contribuyentes.con(7L, "C-0007");
        contribuyentes.con(8L, "C-0008");

        ValorMasivo corrida =
                servicio.porSeleccion(
                        TipoValor.ORDEN_DE_PAGO,
                        null,
                        new Ejercicio(2024),
                        new Ejercicio(2026),
                        null,
                        List.of("C-0007", "C-0008"),
                        OBSERVACION);

        assertThat(corrida.id()).isNotNull();
        assertThat(corrida.totalCandidatos()).isEqualTo(2);
        assertThat(repositorio.candidatosDe(corrida.id())).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    @DisplayName("sin candidatos, no registra nada")
    void porSeleccionSinCandidatos() {
        assertThatThrownBy(
                        () ->
                                servicio.porSeleccion(
                                        TipoValor.ORDEN_DE_PAGO,
                                        null,
                                        new Ejercicio(2024),
                                        new Ejercicio(2026),
                                        null,
                                        List.of(),
                                        OBSERVACION))
                .isInstanceOf(IniciarCorridaMasiva.SinCandidatos.class);
        assertThat(repositorio.corridas).isEmpty();
    }

    @Test
    @DisplayName("un codigo invalido rechaza la corrida entera: no se registra ni el valido")
    void porSeleccionConUnCodigoInvalidoRechazaTodo() {
        contribuyentes.con(7L, "C-0007");

        assertThatThrownBy(
                        () ->
                                servicio.porSeleccion(
                                        TipoValor.ORDEN_DE_PAGO,
                                        null,
                                        new Ejercicio(2024),
                                        new Ejercicio(2026),
                                        null,
                                        List.of("C-0007", "NO-EXISTE"),
                                        OBSERVACION))
                .isInstanceOf(IniciarCorridaMasiva.CandidatosInvalidos.class)
                .satisfies(
                        e ->
                                assertThat(((IniciarCorridaMasiva.CandidatosInvalidos) e).motivos())
                                        .anyMatch(m -> m.contains("NO-EXISTE")));
        assertThat(repositorio.corridas).isEmpty();
    }

    @Test
    @DisplayName("por importacion, lee el CSV y registra un candidato por fila")
    void porImportacionConCsvValido() throws Exception {
        contribuyentes.con(7L, "C-0007");
        contribuyentes.con(8L, "C-0008");
        String csv = "codContribuyente\nC-0007\nC-0008\n";

        ValorMasivo corrida =
                servicio.porImportacion(
                        TipoValor.RESOLUCION_DE_DETERMINACION,
                        null,
                        new Ejercicio(2024),
                        new Ejercicio(2026),
                        null,
                        new StringReader(csv),
                        OBSERVACION);

        assertThat(corrida.totalCandidatos()).isEqualTo(2);
    }

    @Test
    @DisplayName("una fila con codigo invalido rechaza el archivo entero, nombrando la fila")
    void porImportacionConFilaInvalidaRechazaTodo() {
        contribuyentes.con(7L, "C-0007");
        String csv = "codContribuyente\nC-0007\nNO-EXISTE\n";

        assertThatThrownBy(
                        () ->
                                servicio.porImportacion(
                                        TipoValor.ORDEN_DE_PAGO,
                                        null,
                                        new Ejercicio(2024),
                                        new Ejercicio(2026),
                                        null,
                                        new StringReader(csv),
                                        OBSERVACION))
                .isInstanceOf(IniciarCorridaMasiva.CandidatosInvalidos.class)
                .satisfies(
                        e ->
                                assertThat(((IniciarCorridaMasiva.CandidatosInvalidos) e).motivos())
                                        .anyMatch(
                                                m ->
                                                        m.contains("Fila 3")
                                                                && m.contains("NO-EXISTE")));
        assertThat(repositorio.corridas).isEmpty();
    }

    @Test
    @DisplayName("un archivo sin filas de datos no tiene con que registrar la corrida")
    void porImportacionSinFilas() {
        assertThatThrownBy(
                        () ->
                                servicio.porImportacion(
                                        TipoValor.ORDEN_DE_PAGO,
                                        null,
                                        new Ejercicio(2024),
                                        new Ejercicio(2026),
                                        null,
                                        new StringReader("codContribuyente\n"),
                                        OBSERVACION))
                .isInstanceOf(IniciarCorridaMasiva.SinCandidatos.class);
    }

    // ------------------------------------------------------------------

    private static final class ContribuyentesDeMentira implements DirectorioDeContribuyentes {

        private final Map<String, ResumenDeContribuyente> porCodigo = new HashMap<>();

        void con(long id, String codigo) {
            porCodigo.put(codigo, new ResumenDeContribuyente(id, codigo, "TITULAR " + codigo, ""));
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.copyOf(porCodigo.values());
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return Optional.ofNullable(porCodigo.get(codigo));
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class RepositorioDeMentira implements ValorMasivoRepository {

        private long siguienteId = 1;
        private final List<ValorMasivo> corridas = new ArrayList<>();
        private final Map<Long, List<Long>> candidatosPorCorrida = new HashMap<>();

        List<Long> candidatosDe(long corridaId) {
            return candidatosPorCorrida.get(corridaId);
        }

        @Override
        public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
            long id = siguienteId++;
            ValorMasivo guardada =
                    new ValorMasivo(
                            id,
                            corrida.tipo(),
                            corrida.tributo(),
                            corrida.ejercicioDesde(),
                            corrida.ejercicioHasta(),
                            corrida.fechaCriterio(),
                            corrida.origen(),
                            contribuyenteIds.size(),
                            "prueba",
                            null,
                            corrida.observacion());
            corridas.add(guardada);
            candidatosPorCorrida.put(id, List.copyOf(contribuyenteIds));
            return guardada;
        }

        @Override
        public Optional<ValorMasivo> porId(long id) {
            return corridas.stream().filter(c -> c.id() != null && c.id() == id).findFirst();
        }

        @Override
        public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
            return List.of();
        }

        @Override
        public List<ValorMasivoItem> itemsGenerados(long corridaId) {
            return List.of();
        }

        @Override
        public long contarPendientes(long corridaId) {
            return 0;
        }

        @Override
        public void marcarGenerado(long itemId, long valorId) {}

        @Override
        public void marcarSinDeuda(long itemId) {}
    }
}
