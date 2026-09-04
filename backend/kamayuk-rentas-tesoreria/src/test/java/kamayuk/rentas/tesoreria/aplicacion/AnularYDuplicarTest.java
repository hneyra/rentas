package kamayuk.rentas.tesoreria.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dobles.CajasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.tesoreria.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.dobles.MovimientosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.RecibosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.SinConvenios;
import kamayuk.rentas.tesoreria.dobles.TasasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.TurnosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeReciboRepository;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #34 — Las decisiones de anular y de duplicar, sin base de datos.
 *
 * <p>Lo que aqui se prueba son las <b>decisiones</b>: que un recibo de ayer no se anule, que anular
 * devuelva la deuda, que el duplicado salga del desglose congelado y no de volver a preguntar. La
 * concurrencia, el indice unico y los privilegios los prueba {@code ReciboJdbcTest} contra
 * PostgreSQL, porque contra un doble no se pueden demostrar.
 *
 * <p>El generador de documentos <b>si es el de verdad</b>: los tres renderizadores, sin base y sin
 * reloj. Es lo que deja comprobar byte a byte que la reimpresion de dentro de seis meses sale igual
 * —que es el requisito— y no solo que el metodo devuelve algo.
 */
@DisplayName("#34 — Duplicado y anulacion de recibo")
class AnularYDuplicarTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final LocalDate MANIANA = HOY.plusDays(1);
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final SeleccionDeObligacion PREDIAL =
            new SeleccionDeObligacion("PREDIAL", EJERCICIO, 55L, null);

    private static final Caja CAJA = new Caja(1L, "C-01", "Caja tributaria", "001", null, true);

    private static final ResumenDeContribuyente TITULAR =
            new ResumenDeContribuyente(7L, "C-0007", "SANTOS RIVERA, ELENA", "DNI 12345678");

    private static Clock relojDe(LocalDate dia) {
        return Clock.fixed(dia.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    private final CajasEnMemoria cajas = new CajasEnMemoria().con(CAJA);
    private final TurnosEnMemoria turnos = new TurnosEnMemoria();
    private final RecibosEnMemoria recibos = new RecibosEnMemoria();
    private final MovimientosEnMemoria movimientos = new MovimientosEnMemoria();
    private final LibroDeMentira libro = new LibroDeMentira();
    private final TasasEnMemoria tasas = new TasasEnMemoria();
    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira().con(TITULAR);

    private static final GeneradorDeDocumentos GENERADOR = generador(RegimenDeLaInstalacion.REAL);

    private static GeneradorDeDocumentos generador(RegimenDeLaInstalacion regimen) {
        return new GeneradorDeDocumentos(
                List.of(new RenderizadorPdf(), new RenderizadorXls(), new RenderizadorRtf()),
                regimen);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — Anular devuelve la deuda al libro")
    class DeLaAnulacion {

        @Test
        @DisplayName("la obligacion vuelve a tener deuda y el recibo conserva su numero")
        void laDeudaVuelveYElReciboSigue() {
            Recibo cobrado = cobrar(Dinero.de("300.00"));

            AnularRecibo.Anulado anulado = anular(cobrado, HOY);

            assertThat(libro.tieneDeuda(PREDIAL))
                    .as("tras anular, esa obligacion vuelve a estar pendiente")
                    .isTrue();
            assertThat(anulado.recibo().numero())
                    .as("el recibo no desaparece ni cambia de numero: el papel sigue por ahi")
                    .isEqualTo(cobrado.numero());
            assertThat(anulado.recibo().total()).isEqualTo(Dinero.de("300.00"));
            assertThat(anulado.anulacion().importeReversado())
                    .as("y el acta congela lo que deja de estar cobrado")
                    .isEqualTo(Dinero.de("300.00"));
        }

        @Test
        @DisplayName("la reversion se asienta con un documento distinto del de la cobranza")
        void laReversionNoSeMarcaComoLaCobranza() {
            Recibo cobrado = cobrar(Dinero.de("120.00"));

            anular(cobrado, HOY);

            assertThat(libro.documentosOrigen())
                    .as("la cobranza marco sus asientos con el numero del recibo")
                    .containsExactly("RECIBO " + cobrado.numero().impreso());
            assertThat(libro.documentosReversados())
                    .as("y la anulacion busco exactamente esos")
                    .containsExactly("RECIBO " + cobrado.numero().impreso());
        }

        @Test
        @DisplayName("el movimiento lleva la caja y el turno DEL RECIBO, para el arqueo de #36")
        void elMovimientoLlevaElTurnoDelRecibo() {
            Recibo cobrado = cobrar(Dinero.de("80.00"));

            MovimientoDeRecibo anulacion = anular(cobrado, HOY).anulacion();

            assertThat(anulacion.cajaId()).isEqualTo(cobrado.cajaId());
            assertThat(anulacion.turnoId())
                    .as("el dinero sale del cajon en el que entro")
                    .isEqualTo(cobrado.turnoId());
            assertThat(anulacion.tipo()).isEqualTo(TipoDeMovimientoDeRecibo.ANULACION);
        }

        @Test
        @DisplayName("un recibo de ayer no se anula: lo que corresponde es una devolucion")
        void elReciboDeAyerNoSeAnula() {
            Recibo cobrado = cobrar(Dinero.de("50.00"));

            assertThatThrownBy(() -> anular(cobrado, MANIANA))
                    .isInstanceOf(AnularRecibo.FueraDelDiaDePago.class)
                    .hasMessageContaining("mismo dia del pago")
                    .hasMessageContaining("devolucion");
            assertThat(libro.tieneDeuda(PREDIAL))
                    .as("y nada se reverso: la deuda sigue pagada")
                    .isFalse();
            assertThat(movimientos.registrados()).isEmpty();
        }

        @Test
        @DisplayName("anular dos veces no reversa dos veces")
        void anularDosVecesNoReversaDosVeces() {
            Recibo cobrado = cobrar(Dinero.de("200.00"));
            anular(cobrado, HOY);

            assertThatThrownBy(() -> anular(cobrado, HOY))
                    .isInstanceOf(MovimientoDeReciboRepository.ReciboYaAnulado.class);
            assertThat(libro.documentosReversados())
                    .as("una sola reversion: dos dejarian al contribuyente debiendo el doble")
                    .hasSize(1);
        }

        @Test
        @DisplayName("un recibo de tasas se anula sin tocar el libro")
        void elReciboDeTasasNoTocaElLibro() {
            Recibo cobrado = cobrarTasa();

            AnularRecibo.Anulado anulado = anular(cobrado, HOY);

            assertThat(anulado.asientosReversados())
                    .as("un derecho de tramite no es deuda tributaria: nunca hubo asientos")
                    .isZero();
            assertThat(libro.documentosReversados()).isEmpty();
            assertThat(anulado.anulacion().importeReversado())
                    .as("pero del cajon sale igual, y el arqueo lo tiene que restar")
                    .isEqualTo(cobrado.total());
        }

        @Test
        @DisplayName("sin motivo no se anula")
        void sinMotivoNoSeAnula() {
            Recibo cobrado = cobrar(Dinero.de("40.00"));

            assertThatThrownBy(
                            () -> new AnularRecibo.Anulacion(cobrado.numero(), "   ", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sustento");
        }

        @Test
        @DisplayName("el turno ya cerrado no admite anulaciones: su arqueo esta firmado")
        void elTurnoCerradoNoAdmiteAnulaciones() {
            // Se cobra con un cajero y despues se cierra su turno, que es lo que hara #35.
            Recibo cobrado = cobrar(Dinero.de("60.00"));
            turnos.cerrar(cobrado.turnoId());

            assertThatThrownBy(() -> anular(cobrado, HOY))
                    .isInstanceOf(AnularRecibo.TurnoYaCerrado.class)
                    .hasMessageContaining("arqueo");
        }
    }

    @Nested
    @DisplayName("AC 2 — El duplicado sale de lo congelado")
    class DelDuplicado {

        @Test
        @DisplayName("seis meses despues, y con el libro movido, sale byte a byte igual")
        void seisMesesDespuesSaleIgual() {
            Recibo cobrado = cobrar(Dinero.de("250.00"));

            byte[] enMarzo = duplicadoDe(cobrado, HOY).contenido();

            // El mundo sigue: la misma obligacion vuelve a tener deuda -otra determinacion,
            // otro ejercicio, da igual- y han pasado seis meses. Nada de eso puede cambiar
            // el papel, porque el papel no se recalcula.
            libro.con(PREDIAL, Dinero.de("999.99"), Dinero.CERO, Dinero.CERO, Dinero.CERO);
            byte[] enSetiembre = duplicadoDe(cobrado, HOY.plusMonths(6)).contenido();

            // Las dos lineas de fecha enteras, no una subcadena suelta: el instante de
            // emision tambien contiene «2026-03-15», asi que buscar solo eso dejaria pasar
            // un aLaFecha resuelto con el reloj del dia de la reimpresion.
            assertThat(texto(enSetiembre, FormatoDeDocumento.PDF))
                    .as("y la fecha del papel sigue siendo la del cobro, no la de hoy (regla 9)")
                    .contains("Datos al " + HOY)
                    .contains("Importes actualizados al " + HOY)
                    .doesNotContain("999.99");
            // Se comparan quitando la marca de duplicado, que si cambia: el primero es el
            // N.° 1 y el segundo el N.° 2. Todo lo demas -cada cifra y todo el desglose-
            // tiene que ser identico.
            assertThat(sinLaMarca(enSetiembre))
                    .as("dibujar lo congelado seis meses despues da los mismos bytes")
                    .isEqualTo(sinLaMarca(enMarzo));
        }

        @Test
        @DisplayName("va marcado como duplicado, y numerado")
        void vaMarcadoYNumerado() {
            Recibo cobrado = cobrar(Dinero.de("70.00"));

            DuplicadoDeRecibo.Duplicado primero = duplicadoDe(cobrado, HOY);
            DuplicadoDeRecibo.Duplicado segundo = duplicadoDe(cobrado, HOY);

            assertThat(primero.cual()).isEqualTo(1);
            assertThat(segundo.cual()).isEqualTo(2);
            assertThat(texto(primero.contenido(), FormatoDeDocumento.PDF))
                    .as("uno sin marcar circula como si fuera el original")
                    .contains("DUPLICADO N");
            assertThat(movimientos.registrados())
                    .as("y cada reimpresion deja su rastro con quien la genero")
                    .hasSize(2);
        }

        @Test
        @DisplayName("si el recibo esta anulado, el duplicado lo dice")
        void elDuplicadoDiceQueEstaAnulado() {
            Recibo cobrado = cobrar(Dinero.de("90.00"));
            anular(cobrado, HOY);

            String papel = texto(duplicadoDe(cobrado, HOY).contenido(), FormatoDeDocumento.PDF);

            assertThat(papel)
                    .as("quien tenga el papel tiene que poder saber que ya no acredita pago")
                    .contains("RECIBO ANULADO")
                    .contains("ERROR EN EL IMPORTE");
        }

        @Test
        @DisplayName("la vista previa no emite nada: mirar no es reimprimir")
        void laVistaPreviaNoEmite() {
            Recibo cobrado = cobrar(Dinero.de("30.00"));

            DuplicadoDeRecibo.Consultado visto =
                    duplicados(HOY).consultar(cobrado.numero()).orElseThrow();

            assertThat(visto.estaAnulado()).isFalse();
            assertThat(visto.duplicados()).isZero();
            assertThat(movimientos.registrados())
                    .as("numerar un duplicado por abrir la pantalla llenaria la bitacora")
                    .isEmpty();
        }

        @Test
        @DisplayName("si lo congelado ya no se dibuja igual, el segundo duplicado falla")
        void siYaNoSeDibujaIgualFalla() {
            Recibo cobrado = cobrar(Dinero.de("45.00"));

            // Lo que en produccion seria un cambio del renderizador o del modelo: un primer
            // duplicado que se dibujo distinto de como se dibuja ahora.
            movimientos.conDuplicadoDeResumen(
                    cobrado.id(), HOY, cobrado.cajaId(), cobrado.turnoId(), "f".repeat(64));

            assertThatThrownBy(() -> duplicadoDe(cobrado, HOY))
                    .isInstanceOf(DuplicadoDeRecibo.LaReimpresionNoCoincide.class)
                    .hasMessageContaining("papel distinto al original");
        }

        @Test
        @DisplayName("bajo demostracion, el duplicado sale marcado como tal")
        void bajoDemostracionVaMarcado() {
            Recibo cobrado = cobrar(Dinero.de("20.00"));

            DuplicadoDeRecibo deMarchaBlanca =
                    new DuplicadoDeRecibo(
                            recibos,
                            movimientos,
                            contribuyentes,
                            generador(RegimenDeLaInstalacion.DEMOSTRACION),
                            (RegistroDeAuditoria registro) -> {},
                            relojDe(HOY));

            String papel =
                    texto(
                            deMarchaBlanca
                                    .imprimir(
                                            cobrado.numero(),
                                            FormatoDeDocumento.PDF,
                                            Observacion.de("Duplicado pedido en ventanilla"))
                                    .contenido(),
                            FormatoDeDocumento.PDF);

            assertThat(papel)
                    .as("un recibo de la marcha blanca sin marca es un papel que alguien cobra")
                    .contains(ModeloDeDocumento.MARCA_DE_DEMOSTRACION);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private Recibo cobrar(Dinero monto) {
        libro.con(PREDIAL, monto, Dinero.CERO, Dinero.CERO, Dinero.CERO);
        AbrirCaja abrir =
                new AbrirCaja(cajas, turnos, (RegistroDeAuditoria registro) -> {}, relojDe(HOY));
        CobrarDeuda cobrar =
                new CobrarDeuda(
                        abrir,
                        libro,
                        recibos,
                        SinConvenios.formalizador(relojDe(HOY)),
                        (RegistroDeAuditoria registro) -> {},
                        relojDe(HOY));
        return cobrar.cobrar(
                new CobrarDeuda.Cobranza(
                        "C-01",
                        "cajero.prueba",
                        TITULAR.id(),
                        List.of(PREDIAL),
                        FormaDePago.EFECTIVO,
                        TipoDePago.NORMAL,
                        null,
                        HOY,
                        null,
                        null),
                Observacion.de("Cobranza en ventanilla, prueba de #34"));
    }

    private Recibo cobrarTasa() {
        tasas.con(
                new Tasa(
                        9L,
                        "T-100",
                        "Derecho de tramite",
                        3L,
                        "1.3.1.1.1.1",
                        Dinero.de("12.50"),
                        LocalDate.of(2026, 1, 1),
                        null,
                        "TUPA 2026 de la prueba"));
        AbrirCaja abrir =
                new AbrirCaja(cajas, turnos, (RegistroDeAuditoria registro) -> {}, relojDe(HOY));
        CobrarTasa cobrar =
                new CobrarTasa(
                        abrir, tasas, recibos, (RegistroDeAuditoria registro) -> {}, relojDe(HOY));
        return cobrar.cobrar(
                new CobrarTasa.CobroDeTasas(
                        "C-01",
                        "cajero.prueba",
                        TITULAR.id(),
                        List.of(new LineaDeTasaPedida("T-100", 2)),
                        FormaDePago.EFECTIVO,
                        HOY,
                        null),
                Observacion.de("Cobro de tasas, prueba de #34"));
    }

    private AnularRecibo.Anulado anular(Recibo recibo, LocalDate dia) {
        AnularRecibo anular =
                new AnularRecibo(
                        recibos,
                        movimientos,
                        turnos,
                        libro,
                        (RegistroDeAuditoria registro) -> {},
                        relojDe(dia));
        return anular.anular(
                new AnularRecibo.Anulacion(
                        recibo.numero(),
                        "ERROR EN EL IMPORTE",
                        "RESPONSABLE DE TESORERIA",
                        "MEMO-2026-001"),
                Observacion.de("Se cobro de mas por error del cajero"));
    }

    private DuplicadoDeRecibo duplicados(LocalDate dia) {
        return new DuplicadoDeRecibo(
                recibos,
                movimientos,
                contribuyentes,
                GENERADOR,
                (RegistroDeAuditoria registro) -> {},
                relojDe(dia));
    }

    private DuplicadoDeRecibo.Duplicado duplicadoDe(Recibo recibo, LocalDate dia) {
        return duplicados(dia)
                .imprimir(
                        recibo.numero(),
                        FormatoDeDocumento.PDF,
                        Observacion.de("Duplicado pedido en ventanilla"));
    }

    /**
     * El documento sin la marca de duplicado, para poder comparar dos reimpresiones.
     *
     * <p>La marca cambia entre la primera y la segunda —{@code N.° 1} y {@code N.° 2}— y tiene que
     * cambiar. Lo que no puede cambiar es nada mas, y eso es lo que se compara.
     */
    private static String sinLaMarca(byte[] documento) {
        return texto(documento, FormatoDeDocumento.PDF)
                .replaceAll("DUPLICADO N[^\\n)]*", "DUPLICADO")
                .replaceAll("/Length [0-9]+", "/Length")
                .replaceAll("(?s)xref.*", "");
    }

    /**
     * Los bytes como texto.
     *
     * <p>El PDF se lee en {@code windows-1252} y no en UTF-8 porque es lo que declara su fuente
     * ({@code /WinAnsiEncoding}): leerlo de otro modo convierte la raya del titulo en basura y una
     * prueba que busque la marca se pondria roja sin que nada este mal.
     */
    private static String texto(byte[] documento, FormatoDeDocumento formato) {
        return new String(
                documento,
                formato == FormatoDeDocumento.PDF
                        ? java.nio.charset.Charset.forName("windows-1252")
                        : StandardCharsets.UTF_8);
    }

    /** El instante fijo del reloj, por si alguna prueba lo necesita. */
    static final Instant MOMENTO = HOY.atStartOfDay(ZoneOffset.UTC).toInstant();
}
