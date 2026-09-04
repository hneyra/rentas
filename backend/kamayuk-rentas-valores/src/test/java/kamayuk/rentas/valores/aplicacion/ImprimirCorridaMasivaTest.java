package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.DocumentoEmitido;
import kamayuk.rentas.documentos.DocumentoRepository;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeItemMasivo;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #38 — la etapa "impresion", sin base de datos. {@link EmitirDocumento#emitirEnLote} no toca
 * ningun repositorio -es puro flujo, RTF/PDF/XLS en memoria-, asi que se prueba con los
 * renderizadores reales y {@link RegimenDeLaInstalacion#REAL}, sin PostgreSQL.
 */
@DisplayName("#38 — ImprimirCorridaMasiva")
class ImprimirCorridaMasivaTest {

    private static final LocalDate PROYECTADO_A = LocalDate.of(2026, 3, 15);
    private static final Observacion OBSERVACION = Observacion.de("Se emite para la prueba");

    @Test
    @DisplayName("imprime un documento por cada valor GENERADO de la corrida, y solo esos")
    void imprimeUnDocumentoPorValorGenerado() {
        Valor valorUno = valorDe(1L, "OP-2026-000001", 7L);
        Valor valorDos = valorDe(2L, "OP-2026-000002", 8L);
        RepositorioValorDeMentira repositorioValor =
                new RepositorioValorDeMentira(Map.of(1L, valorUno, 2L, valorDos));
        RepositorioMasivoDeMentira repositorioMasivo =
                new RepositorioMasivoDeMentira(
                        List.of(
                                itemGenerado(10L, 1L, 7L),
                                itemGenerado(11L, 2L, 8L),
                                itemPendiente(12L, 9L)));
        ContribuyentesDeMentira contribuyentes = new ContribuyentesDeMentira();
        contribuyentes.con(7L, "C-0007", "TITULAR UNO");
        contribuyentes.con(8L, "C-0008", "TITULAR DOS");

        ConstruirModeloDeValor construirModelo =
                new ConstruirModeloDeValor(repositorioValor, contribuyentes);
        EmitirDocumento emitirDocumento = emitirDocumentoDeVerdad();
        ImprimirCorridaMasiva servicio =
                new ImprimirCorridaMasiva(repositorioMasivo, construirModelo, emitirDocumento);

        Map<String, ByteArrayOutputStream> salidas = new HashMap<>();
        long impresos =
                servicio.imprimir(
                        1L,
                        FormatoDeDocumento.PDF,
                        modelo ->
                                salidas.computeIfAbsent(
                                        modelo.titulo(), t -> new ByteArrayOutputStream()));

        assertThat(impresos).isEqualTo(2);
        assertThat(salidas).hasSize(2);
        assertThat(salidas.keySet()).anyMatch(t -> t.contains("OP-2026-000001"));
        assertThat(salidas.keySet()).anyMatch(t -> t.contains("OP-2026-000002"));
        assertThat(salidas.values()).allSatisfy(bytes -> assertThat(bytes.size()).isPositive());
    }

    private static Valor valorDe(long id, String numero, long contribuyenteId) {
        return new Valor(
                id,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                new Ejercicio(2026),
                contribuyenteId,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                Dinero.de("1000.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                PROYECTADO_A,
                EstadoDeValor.EMITIDO,
                PROYECTADO_A,
                "prueba",
                OBSERVACION);
    }

    private static ValorMasivoItem itemGenerado(long id, long valorId, long contribuyenteId) {
        return new ValorMasivoItem(
                id, 1L, contribuyenteId, EstadoDeItemMasivo.GENERADO, valorId, null);
    }

    private static ValorMasivoItem itemPendiente(long id, long contribuyenteId) {
        return new ValorMasivoItem(
                id, 1L, contribuyenteId, EstadoDeItemMasivo.PENDIENTE, null, null);
    }

    private static EmitirDocumento emitirDocumentoDeVerdad() {
        GeneradorDeDocumentos generador =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorRtf(),
                                new RenderizadorXls()),
                        PuntoDeFirma.SIN_FIRMA,
                        RegimenDeLaInstalacion.REAL);
        return new EmitirDocumento(
                new DocumentoRepositorioNoUsado(), generador, registro -> {}, Clock.systemUTC());
    }

    // ------------------------------------------------------------------

    private static final class RepositorioValorDeMentira implements ValorRepository {

        private final Map<Long, Valor> porId;

        RepositorioValorDeMentira(Map<Long, Valor> porId) {
            this.porId = porId;
        }

        @Override
        public Valor insertar(Valor valor, List<ValorDetalle> detalle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Valor> porNumero(String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Valor> cobrablesDe(long contribuyenteId, String tributo, Ejercicio ejercicio) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Valor cambiarEstado(long valorId, EstadoDeValor nuevo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Valor> porNumero(TipoValor tipo, Ejercicio ejercicio, String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Valor> porId(long id) {
            return Optional.ofNullable(porId.get(id));
        }

        @Override
        public List<ValorDetalle> detalleDe(long valorId) {
            return List.of();
        }

        @Override
        public Pagina<Valor> buscar(CriterioDeValor criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException();
        }

        /** {@code consulta_valores} no pasa por este caso de uso. */
        @Override
        public Pagina<ValorEnConsulta> consultar(
                CriterioDeConsultaDeValores criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("Este doble no sirve la grilla de consulta");
        }

        @Override
        public long contar(CriterioDeConsultaDeValores criterio) {
            throw new UnsupportedOperationException("Este doble no cuenta la grilla de consulta");
        }

        @Override
        public long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RepositorioMasivoDeMentira implements ValorMasivoRepository {

        private final List<ValorMasivoItem> items;

        RepositorioMasivoDeMentira(List<ValorMasivoItem> items) {
            this.items = items;
        }

        @Override
        public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ValorMasivo> porId(long id) {
            return Optional.empty();
        }

        @Override
        public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ValorMasivoItem> itemsGenerados(long corridaId) {
            List<ValorMasivoItem> generados = new ArrayList<>();
            for (ValorMasivoItem item : items) {
                if (item.corridaId() == corridaId && item.estado() == EstadoDeItemMasivo.GENERADO) {
                    generados.add(item);
                }
            }
            return generados;
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

    private static final class ContribuyentesDeMentira implements DirectorioDeContribuyentes {

        private final Map<Long, ResumenDeContribuyente> porId = new HashMap<>();

        void con(long id, String codigo, String nombre) {
            porId.put(id, new ResumenDeContribuyente(id, codigo, nombre, ""));
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.copyOf(porId.values());
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return porId.values().stream().filter(c -> c.codigo().equals(codigo)).findFirst();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> resultado = new HashMap<>();
            for (Long id : ids) {
                if (porId.containsKey(id)) {
                    resultado.put(id, porId.get(id));
                }
            }
            return resultado;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /** {@link EmitirDocumento#emitirEnLote} no lo toca; solo hace falta para el constructor. */
    private static final class DocumentoRepositorioNoUsado implements DocumentoRepository {

        @Override
        public Optional<DocumentoEmitido> porNumero(
                String tipo, Ejercicio ejercicio, String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentoEmitido> de(String tipo, String referencia) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentoEmitido insertar(DocumentoEmitido documento) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentoEmitido registrarReimpresion(DocumentoEmitido documento) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long siguienteCorrelativo(String tipo, Ejercicio ejercicio) {
            throw new UnsupportedOperationException();
        }
    }
}
