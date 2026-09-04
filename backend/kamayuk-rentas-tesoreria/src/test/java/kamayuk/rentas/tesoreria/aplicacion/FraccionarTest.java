package kamayuk.rentas.tesoreria.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.dobles.AcogimientoDeMentira;
import kamayuk.rentas.tesoreria.dobles.ConveniosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.MovimientosDeConvenioEnMemoria;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #35 — El preconvenio y su formalizacion, con dobles.
 *
 * <p>Lo que se prueba aqui y no contra la base: que las <b>cifras del convenio no vengan de quien
 * lo pide</b>. Que el interes salga del conjunto sellado y no de la peticion, que la deuda acogida
 * salga del libro y no de la pantalla, y que formalizar exija que lo cobrado sea la cuota inicial
 * que el cronograma congelo. Todo eso se ve mejor con dobles que reproducen el comportamiento del
 * libro; que los asientos sean de verdad lo demuestra {@code ConvenioJdbcTest}.
 */
@DisplayName("#35 — Fraccionar y formalizar")
class FraccionarTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final SeleccionDeObligacion PREDIAL =
            new SeleccionDeObligacion("PREDIAL", EJERCICIO, null, null);

    private static final SeleccionDeObligacion ARBITRIOS =
            new SeleccionDeObligacion("ARBITRIO", EJERCICIO, null, null);

    private final ConveniosEnMemoria convenios = new ConveniosEnMemoria();
    private final MovimientosDeConvenioEnMemoria movimientos = new MovimientosDeConvenioEnMemoria();
    private final AcogimientoDeMentira acogimiento =
            new AcogimientoDeMentira()
                    .con(PREDIAL, "ORDINARIA", Dinero.de("300.00"), HOY)
                    .con(ARBITRIOS, "COACTIVA", Dinero.de("200.00"), HOY);

    private final RegistrarPreconvenio registrar =
            new RegistrarPreconvenio(
                    convenios,
                    acogimiento,
                    new CondicionesParametrizadas(new ParametrosDeLaPrueba()),
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);

    private final FormalizarConvenio formalizar =
            new FormalizarConvenio(
                    convenios,
                    movimientos,
                    acogimiento,
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);

    @Nested
    @DisplayName("El preconvenio")
    class DelPreconvenio {

        @Test
        @DisplayName("acoge lo que dice el libro, no lo que diga la pantalla")
        void acogeLoQueDiceElLibro() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());

            assertThat(convenio.montoTotal())
                    .as("300 de predial mas 200 de arbitrios: los dijo el libro")
                    .isEqualTo(Dinero.de("500.00"));
            assertThat(convenio.acogida())
                    .as("cuota por cuota, con la fase de la que sale cada una")
                    .hasSize(2);
            assertThat(convenio.acogida().stream().map(fila -> fila.faseOrigen()).toList())
                    .containsExactlyInAnyOrder("ORDINARIA", "COACTIVA");
        }

        @Test
        @DisplayName("el interes y el maximo salen del conjunto sellado, con su identificador")
        void elInteresSaleDelConjuntoSellado() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());

            CondicionesDelConvenio condiciones = convenio.condiciones();
            assertThat(condiciones.interesMensual()).isEqualTo(Alicuota.de("1"));
            assertThat(condiciones.maximoDeCuotas()).isEqualTo(12);
            assertThat(condiciones.conjuntoId())
                    .as(
                            "de que conjunto salieron queda escrito: recalcular no resuelve «el"
                                    + " vigente» (ARQ-09 §3)")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("no escribe ningun movimiento: lo que sale de aqui es un preconvenio")
        void noEscribeNingunMovimiento() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());

            assertThat(movimientos.deConvenio(convenio.idGuardado()))
                    .as("sin cuota inicial pagada en caja no hay convenio: es estructural")
                    .isEmpty();
            assertThat(acogimiento.documentosAcogidos())
                    .as("y el libro no se entera de que existe")
                    .isEmpty();
        }

        @Test
        @DisplayName("simular no consume correlativo ni registra nada")
        void simularNoConsumeCorrelativo() {
            RegistrarPreconvenio.Simulacion simulacion = registrar.simular(peticion(6, "20"));

            assertThat(simulacion.total()).isEqualTo(Dinero.de("500.00"));
            assertThat(simulacion.aLaFecha()).isEqualTo(HOY);
            assertThat(convenios.registrados()).isEmpty();

            // Y el primer convenio que se registre de verdad se lleva el numero 1: si la
            // simulacion hubiera numerado, la pantalla habria impreso un papel con un
            // numero que no existe.
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());
            assertThat(convenio.numero()).isEqualTo(new NumeroDeConvenio(EJERCICIO, 1));
        }

        @Test
        @DisplayName("sin deuda a la fecha de corte no hay nada que fraccionar")
        void sinDeudaNoHayNadaQueFraccionar() {
            acogimiento.vaciar(PREDIAL);
            acogimiento.vaciar(ARBITRIOS);

            assertThatThrownBy(() -> registrar.registrar(peticion(6, "20"), null, porQue()))
                    .isInstanceOf(RegistrarPreconvenio.SinDeudaQueFraccionar.class)
                    .hasMessageContaining("Un convenio sobre cero no es un convenio");
        }

        @Test
        @DisplayName("mas cuotas de las que admite la ordenanza se rechazan")
        void masCuotasDeLasAdmitidasSeRechazan() {
            assertThatThrownBy(() -> registrar.registrar(peticion(24, "20"), null, porQue()))
                    .isInstanceOf(CondicionesDelConvenio.DemasiadasCuotas.class);
            assertThat(convenios.registrados()).isEmpty();
        }
    }

    @Nested
    @DisplayName("La formalizacion")
    class DeLaFormalizacion {

        @Test
        @DisplayName("acoge la deuda y deja el convenio vigente")
        void acogeYDejaVigente() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());

            FormalizarConvenio.Formalizado formalizado =
                    formalizar.formalizar(
                            convenio.numero(), 9L, Dinero.de("100.00"), HOY, porQue());

            assertThat(formalizado.acogido().importe()).isEqualTo(Dinero.de("500.00"));
            assertThat(formalizado.formalizacion().reciboId()).isEqualTo(9L);
            assertThat(formalizado.formalizacion().cuota()).isZero();
            assertThat(acogimiento.documentosAcogidos())
                    .containsExactly("CONVENIO " + convenio.numero().impreso());
        }

        @Test
        @DisplayName("lo cobrado tiene que ser la cuota inicial del cronograma, al centimo")
        void loCobradoTieneQueSerLaInicial() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());

            assertThatThrownBy(
                            () ->
                                    formalizar.formalizar(
                                            convenio.numero(),
                                            9L,
                                            Dinero.de("1.00"),
                                            HOY,
                                            porQue()))
                    .isInstanceOf(FormalizarConvenio.LaInicialNoCuadra.class)
                    .hasMessageContaining("y el recibo cobro");
            assertThat(movimientos.deConvenio(convenio.idGuardado())).isEmpty();
            assertThat(acogimiento.documentosAcogidos())
                    .as("y nada se acogio: el convenio sigue sin existir para el libro")
                    .isEmpty();
        }

        @Test
        @DisplayName(
                "si la deuda se pago entre la firma y el cobro, no se acoge un saldo que no"
                        + " existe")
        void siLaDeudaSePagoNoSeAcoge() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());
            acogimiento.vaciar(PREDIAL);
            acogimiento.vaciar(ARBITRIOS);

            assertThatThrownBy(
                            () ->
                                    formalizar.formalizar(
                                            convenio.numero(),
                                            9L,
                                            Dinero.de("100.00"),
                                            HOY,
                                            porQue()))
                    .isInstanceOf(FormalizarConvenio.SinDeudaQueAcoger.class)
                    .hasMessageContaining("se pago entre la firma y el cobro");
        }

        @Test
        @DisplayName("un convenio que no existe no se formaliza")
        void unConvenioInexistenteNoSeFormaliza() {
            assertThatThrownBy(
                            () ->
                                    formalizar.formalizar(
                                            new NumeroDeConvenio(EJERCICIO, 99),
                                            9L,
                                            Dinero.de("100.00"),
                                            HOY,
                                            porQue()))
                    .isInstanceOf(FormalizarConvenio.ConvenioInexistente.class);
        }

        @Test
        @DisplayName("formalizar dos veces se rechaza: acogeria la deuda por segunda vez")
        void formalizarDosVecesSeRechaza() {
            Convenio convenio = registrar.registrar(peticion(6, "20"), null, porQue());
            formalizar.formalizar(convenio.numero(), 9L, Dinero.de("100.00"), HOY, porQue());

            assertThatThrownBy(
                            () ->
                                    formalizar.formalizar(
                                            convenio.numero(),
                                            10L,
                                            Dinero.de("100.00"),
                                            HOY,
                                            porQue()))
                    .isInstanceOf(FormalizarConvenio.ConvenioNoEsPreconvenio.class);
            assertThat(acogimiento.documentosAcogidos()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------

    private static RegistrarPreconvenio.Peticion peticion(int cuotas, String inicial) {
        return new RegistrarPreconvenio.Peticion(
                7L,
                List.of(PREDIAL, ARBITRIOS),
                TipoDeConvenio.ORDINARIO,
                HOY,
                HOY,
                cuotas,
                Alicuota.de(inicial),
                HOY.plusMonths(1),
                TipoDeGarantia.NO_REQUIERE,
                null,
                null,
                null);
    }

    private static Observacion porQue() {
        return Observacion.de("Acogimiento a fraccionamiento, prueba de #35");
    }

    /** Un interes y un maximo de mentira: lo que se prueba es el mecanismo, no las cifras. */
    private static final class ParametrosDeLaPrueba implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .numero("INTERES_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("1"))
                    .numero("CUOTAS_MAXIMAS_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("12"))
                    .numero(
                            PoliticasDeRedondeoSelladas.TIPO,
                            PuntoDeRedondeo.CUOTA.name(),
                            ValorNormativo.de("2"))
                    .texto(
                            PoliticasDeRedondeoSelladas.TIPO,
                            PuntoDeRedondeo.CUOTA.name(),
                            RoundingMode.HALF_UP.name())
                    .construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1);
        }
    }
}
