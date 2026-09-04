package kamayuk.rentas.tesoreria.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dobles.CajasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.CierresEnMemoria;
import kamayuk.rentas.tesoreria.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.dobles.TurnosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.ReciboDelTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #36 — Las decisiones de cerrar y de reversar, sin base de datos.
 *
 * <p>Lo que aqui se prueba son las <b>decisiones</b>: que un cierre no se pueda repetir, que
 * reversar deje el anterior intacto y reabra el turno, que el cuadre contra el libro distinga los
 * recibos que no abonan, y que un descuadre de caja no impida cerrar mientras que un descuadre
 * contra el libro si. La concurrencia, el {@code REVOKE UPDATE} y la no contencion los prueba
 * {@code CierreDeCajaJdbcTest} contra PostgreSQL, porque contra un doble no se pueden demostrar.
 */
@DisplayName("#36 — Cierre y arqueo de caja")
class CerrarYArquearTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-15T18:00:00Z"), ZoneOffset.UTC);
    private static final long CAJA = 1L;
    private static final long TURNO = 10L;
    private static final String CAJERO = "jperez";

    @Nested
    @DisplayName("El cierre congela su arqueo")
    class DelCierre {

        @Test
        @DisplayName("cierra con lo cobrado, lo anulado y el neto, y deja el turno cerrado")
        void cierraConSuArqueo() {
            LibroDeMentira libro = new LibroDeMentira();
            CierresEnMemoria cierres =
                    new CierresEnMemoria()
                            .conRecibosDelTurno(
                                    TURNO,
                                    tasa(1, "80.00", "0.00"),
                                    preconvenio(2, "200.00", "0.00"));
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno(cierres, libro)
                            .cerrar(
                                    new CerrarTurno.Cierre(
                                            "C-01",
                                            CAJERO,
                                            HOY,
                                            Map.of(FormaDePago.EFECTIVO, Dinero.de("280.00"))),
                                    porQue());

            assertThat(cerrado.cierre().tipo()).isEqualTo(TipoDeMovimientoDeTurno.CIERRE);
            assertThat(cerrado.cierre().secuencia()).isEqualTo(1);
            assertThat(cerrado.cierre().arqueoCongelado().neto()).isEqualTo(Dinero.de("280.00"));
            assertThat(cerrado.cierre().arqueoCongelado().diferencia()).isEqualTo(Dinero.CERO);
            assertThat(cerrado.cuadre().sinAsientos())
                    .as("la tasa y la cuota inicial no tocan el libro: cuadran contra el recibo")
                    .isEqualTo(Dinero.de("280.00"));
            assertThat(cerrado.cuadre().conAsientos()).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("un descuadre de caja NO impide cerrar: es justo lo que hay que dejar escrito")
        void elDescuadreDeCajaSeGuarda() {
            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, tasa(1, "500.00", "0.00"));

            CerrarTurno.Cerrado cerrado =
                    cerrarTurno(cierres, new LibroDeMentira())
                            .cerrar(
                                    new CerrarTurno.Cierre(
                                            "C-01",
                                            CAJERO,
                                            HOY,
                                            Map.of(FormaDePago.EFECTIVO, Dinero.de("490.00"))),
                                    porQue());

            assertThat(cerrado.cierre().arqueoCongelado().diferencia())
                    .isEqualTo(Dinero.de("-10.00"));
            assertThat(cerrado.cierre().arqueoCongelado().cuadra()).isFalse();
        }

        @Test
        @DisplayName("cerrar dos veces es 409: dos arqueos vigentes sobre el mismo dinero")
        void noSeCierraDosVeces() {
            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, tasa(1, "80.00", "0.00"));
            CerrarTurno cerrarTurno = cerrarTurno(cierres, new LibroDeMentira());
            cerrarTurno.cerrar(cierre(), porQue());

            assertThatThrownBy(() -> cerrarTurno.cerrar(cierre(), porQue()))
                    .isInstanceOf(CerrarTurno.TurnoYaCerrado.class)
                    .hasMessageContaining("se reversa el que hay");
        }

        @Test
        @DisplayName("sin turno abierto ese dia no hay nada que arquear")
        void sinTurnoNoHayArqueo() {
            CerrarTurno cerrarTurno =
                    new CerrarTurno(
                            new CajasEnMemoria().con(caja()),
                            new TurnosEnMemoria(),
                            new CierresEnMemoria(),
                            new ArqueoDeTurno(new CierresEnMemoria(), new LibroDeMentira()),
                            registro -> {},
                            RELOJ);

            assertThatThrownBy(() -> cerrarTurno.cerrar(cierre(), porQue()))
                    .isInstanceOf(CerrarTurno.TurnoSinAbrir.class);
        }
    }

    @Nested
    @DisplayName("Un cierre no se modifica: se reversa con otro")
    class DeLaReversion {

        @Test
        @DisplayName("la reversion deja el cierre anterior intacto y trazable, y reabre el turno")
        void reversarDejaElAnteriorIntacto() {
            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, tasa(1, "300.00", "0.00"));
            CerrarTurno cerrarTurno = cerrarTurno(cierres, new LibroDeMentira());
            CerrarTurno.Cerrado primero = cerrarTurno.cerrar(cierre(), porQue());

            CerrarTurno.Reversado reversado =
                    cerrarTurno.reversar(
                            "C-01", CAJERO, HOY, "faltaba registrar una cobranza", porQue());

            assertThat(reversado.reversion().tipo()).isEqualTo(TipoDeMovimientoDeTurno.REVERSION);
            assertThat(reversado.reversion().revierteAId())
                    .isEqualTo(primero.cierre().idGuardado());
            assertThat(reversado.reversado().arqueoCongelado().neto())
                    .as("el arqueo del cierre reversado sigue diciendo lo que decia")
                    .isEqualTo(Dinero.de("300.00"));

            List<CierreDeTurno> historia = cierres.deTurno(TURNO);
            assertThat(historia).hasSize(2);
            assertThat(CierreDeTurno.vigenteEn(historia))
                    .as("sin cierre vigente: el turno vuelve a estar abierto")
                    .isNull();
        }

        @Test
        @DisplayName("y despues de reversar se puede volver a cerrar, con la secuencia siguiente")
        void seCierraOtraVezDespuesDeReversar() {
            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, tasa(1, "300.00", "0.00"));
            CerrarTurno cerrarTurno = cerrarTurno(cierres, new LibroDeMentira());
            cerrarTurno.cerrar(cierre(), porQue());
            cerrarTurno.reversar("C-01", CAJERO, HOY, "hay que seguir cobrando", porQue());

            // Entretanto la ventanilla cobro otro recibo.
            cierres.conRecibosDelTurno(TURNO, tasa(1, "300.00", "0.00"), tasa(2, "50.00", "0.00"));
            CerrarTurno.Cerrado segundo = cerrarTurno.cerrar(cierre(), porQue());

            assertThat(segundo.cierre().secuencia()).isEqualTo(3);
            assertThat(segundo.cierre().arqueoCongelado().neto())
                    .as("el cierre nuevo incluye lo cobrado despues de reabrir")
                    .isEqualTo(Dinero.de("350.00"));
        }

        @Test
        @DisplayName("un turno sin cierre vigente no tiene nada que reversar")
        void nadaQueReversar() {
            CerrarTurno cerrarTurno = cerrarTurno(new CierresEnMemoria(), new LibroDeMentira());

            assertThatThrownBy(
                            () ->
                                    cerrarTurno.reversar(
                                            "C-01", CAJERO, HOY, "por si acaso", porQue()))
                    .isInstanceOf(CerrarTurno.TurnoSinCerrar.class);
        }
    }

    @Nested
    @DisplayName("El cierre cuadra contra el libro")
    class DelCuadre {

        @Test
        @DisplayName("lo cobrado en deuda tributaria es lo que el libro asento")
        void loTributarioCuadra() {
            LibroDeMentira libro = new LibroDeMentira();
            SeleccionDeObligacion predial =
                    new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 1L, null);
            libro.con(predial, Dinero.de("300.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);
            libro.abonarPagoIntegro(
                    1L,
                    List.of(predial),
                    HOY,
                    new NumeroDeRecibo("001", 1).documentoDeLaCobranza(),
                    porQue());

            CierresEnMemoria cierres =
                    new CierresEnMemoria()
                            .conRecibosDelTurno(
                                    TURNO, normal(1, "300.00", "0.00"), tasa(2, "80.00", "0.00"));

            CerrarTurno.Cerrado cerrado =
                    cerrarTurno(cierres, libro)
                            .cerrar(
                                    new CerrarTurno.Cierre(
                                            "C-01",
                                            CAJERO,
                                            HOY,
                                            Map.of(FormaDePago.EFECTIVO, Dinero.de("380.00"))),
                                    porQue());

            assertThat(cerrado.cuadre().conAsientos()).isEqualTo(Dinero.de("300.00"));
            assertThat(cerrado.cuadre().sinAsientos())
                    .as("los 80 de la tasa quedan fuera del cuadre contra el libro")
                    .isEqualTo(Dinero.de("80.00"));
            assertThat(cerrado.cuadre().total()).isEqualTo(Dinero.de("380.00"));
        }

        @Test
        @DisplayName("una anulacion del dia se resta de los dos lados, y el turno sigue cuadrando")
        void laAnulacionSeRestaEnLosDosLados() {
            LibroDeMentira libro = new LibroDeMentira();
            SeleccionDeObligacion predial =
                    new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 1L, null);
            libro.con(predial, Dinero.de("120.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);
            NumeroDeRecibo numero = new NumeroDeRecibo("001", 1);
            libro.abonarPagoIntegro(
                    1L, List.of(predial), HOY, numero.documentoDeLaCobranza(), porQue());
            libro.reversarAbonos(
                    numero.documentoDeLaCobranza(), numero.documentoDeLaAnulacion(), HOY, porQue());

            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, normal(1, "120.00", "120.00"));

            CerrarTurno.Cerrado cerrado =
                    cerrarTurno(cierres, libro)
                            .cerrar(
                                    new CerrarTurno.Cierre("C-01", CAJERO, HOY, Map.of()),
                                    porQue());

            assertThat(cerrado.cierre().arqueoCongelado().neto()).isEqualTo(Dinero.CERO);
            assertThat(cerrado.cuadre().conAsientos()).isEqualTo(Dinero.CERO);
            assertThat(cerrado.cierre().arqueoCongelado().recibosAnulados()).isEqualTo(1);
        }

        @Test
        @DisplayName("si el libro no dice lo que dicen los recibos, el cierre NO se firma")
        void siNoCuadraNoSeFirma() {
            // Un recibo tributario cuyos asientos no estan: es lo que pasaria si alguien
            // hubiera reversado los abonos por otro camino.
            CierresEnMemoria cierres =
                    new CierresEnMemoria().conRecibosDelTurno(TURNO, normal(1, "300.00", "0.00"));
            CerrarTurno cerrarTurno = cerrarTurno(cierres, new LibroDeMentira());

            assertThatThrownBy(() -> cerrarTurno.cerrar(cierre(), porQue()))
                    .isInstanceOf(ArqueoDeTurno.ElArqueoNoCuadraConElLibro.class)
                    .hasMessageContaining("el cierre no se firma");
            assertThat(cierres.registrados()).as("no queda ni un acta").isEmpty();
        }
    }

    @Nested
    @DisplayName("Cobrar despues de cerrar")
    class DeLaCobranzaTrasElCierre {

        @Test
        @DisplayName("con el turno cerrado, la caja rechaza cobrar")
        void conTurnoCerradoNoSeCobra() {
            TurnosEnMemoria turnos = new TurnosEnMemoria().conTurnoCerrado(CAJA, CAJERO, HOY);
            AbrirCaja abrirCaja =
                    new AbrirCaja(new CajasEnMemoria().con(caja()), turnos, registro -> {}, RELOJ);

            assertThatThrownBy(() -> abrirCaja.enLaCaja("C-01", CAJERO, HOY, porQue()))
                    .isInstanceOf(AbrirCaja.TurnoCerrado.class)
                    .as("y el mensaje dice como se reabre, que es reversando el cierre")
                    .hasMessageContaining("reversar");
        }
    }

    // ------------------------------------------------------------------

    private static CerrarTurno cerrarTurno(CierresEnMemoria cierres, LibroDeMentira libro) {
        return new CerrarTurno(
                new CajasEnMemoria().con(caja()),
                new TurnosEnMemoria().conTurnoAbierto(TURNO, CAJA, CAJERO, HOY),
                cierres,
                new ArqueoDeTurno(cierres, libro),
                registro -> {},
                RELOJ);
    }

    private static CerrarTurno.Cierre cierre() {
        return new CerrarTurno.Cierre("C-01", CAJERO, HOY, Map.of());
    }

    private static Caja caja() {
        return new Caja(CAJA, "C-01", "Caja tributaria", "001", null, true);
    }

    private static ReciboDelTurno normal(long numero, String total, String anulado) {
        return recibo(numero, TipoDePago.NORMAL, total, anulado);
    }

    private static ReciboDelTurno tasa(long numero, String total, String anulado) {
        return recibo(numero, TipoDePago.TASA, total, anulado);
    }

    private static ReciboDelTurno preconvenio(long numero, String total, String anulado) {
        return recibo(numero, TipoDePago.PRECONVENIO, total, anulado);
    }

    private static ReciboDelTurno recibo(
            long numero, TipoDePago tipo, String total, String anulado) {
        return new ReciboDelTurno(
                new NumeroDeRecibo("001", numero),
                tipo,
                FormaDePago.EFECTIVO,
                Dinero.de(total),
                Dinero.de(anulado));
    }

    private static Observacion porQue() {
        return Observacion.de("cierre del turno de la prueba");
    }
}
