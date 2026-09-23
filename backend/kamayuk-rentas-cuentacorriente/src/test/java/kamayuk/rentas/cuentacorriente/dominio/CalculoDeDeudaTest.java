package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code deudaActualizadaA(fecha)}: la funcion sobre la que se apoya toda la cobranza (#22,
 * RF-042).
 *
 * <p>Sin Spring, sin Docker y sin reloj (regla 6): cada prueba arma su propia lista de asientos y
 * llama al metodo directamente, tal como {@code MotorDeReglasTest} prueba el motor de #14.
 */
@DisplayName("#22 — deudaActualizadaA(fecha)")
class CalculoDeDeudaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    @Test
    @DisplayName("insoluto, reajuste, interes y gasto salen de netear cargos contra abonos")
    void neteaCargosYAbonosPorConcepto() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        cargo(Concepto.GASTO, Dinero.de(50), LocalDate.of(2026, 3, 1)),
                        abono(Concepto.PAGO, Dinero.de(200), LocalDate.of(2026, 4, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 4, 1), REDONDEO);

        // El PAGO es su propio concepto (V2): quien lo registra decide a que bucket
        // lo imputa, y en esta prueba no imputa a ninguno de los cuatro del desglose.
        assertThat(deuda.insoluto()).isEqualTo(Dinero.de(1000));
        assertThat(deuda.gasto()).isEqualTo(Dinero.de(50));
        assertThat(deuda.reajuste()).isEqualTo(Dinero.CERO);
        assertThat(deuda.interes()).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("un abono que reduce el insoluto se neta en el mismo concepto")
    void unAbonoDeInsolutoReduceElInsoluto() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        abono(Concepto.INSOLUTO, Dinero.de(400), LocalDate.of(2026, 4, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 4, 1), REDONDEO);

        assertThat(deuda.insoluto()).isEqualTo(Dinero.de(600));
    }

    @Test
    @DisplayName("un asiento posterior a la fecha de corte no entra en el calculo")
    void unAsientoPosteriorNoEntra() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        abono(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 8, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 4, 1), REDONDEO);

        assertThat(deuda.insoluto())
                .as("el pago de agosto es del futuro visto desde el corte de abril")
                .isEqualTo(Dinero.de(1000));
    }

    @Test
    @DisplayName("dos fechas de corte distintas, con los mismos asientos, dan resultados distintos")
    void dosFechasDistintasDanResultadosDistintos() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        abono(Concepto.INSOLUTO, Dinero.de(300), LocalDate.of(2026, 6, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        DeudaActualizada antesDelAbono =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 5, 1), REDONDEO);
        DeudaActualizada despuesDelAbono =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 7, 1), REDONDEO);

        assertThat(antesDelAbono.insoluto()).isEqualTo(Dinero.de(1000));
        assertThat(despuesDelAbono.insoluto()).isEqualTo(Dinero.de(700));
        assertThat(antesDelAbono.fecha()).isNotEqualTo(despuesDelAbono.fecha());
    }

    @Test
    @DisplayName("es pura: la misma lista y la misma fecha dan siempre el mismo centimo")
    void esPura() {
        List<Asiento> asientos =
                List.of(cargo(Concepto.INSOLUTO, Dinero.de(1234), LocalDate.of(2026, 3, 1)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        DeudaActualizada primera =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 9, 1), REDONDEO);
        DeudaActualizada segunda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 9, 1), REDONDEO);

        assertThat(primera).isEqualTo(segunda);
    }

    @Test
    @DisplayName("el desglose suma exactamente el total, sin diferencia de un centimo")
    void elDesgloseSumaExactamenteElTotal() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("1000.33"), LocalDate.of(2026, 3, 1)),
                        cargo(Concepto.REAJUSTE, Dinero.de("12.11"), LocalDate.of(2026, 3, 1)),
                        cargo(Concepto.INTERES, Dinero.de("7.05"), LocalDate.of(2026, 3, 1)),
                        cargo(Concepto.GASTO, Dinero.de("3.20"), LocalDate.of(2026, 3, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 3, 1), REDONDEO);

        assertThat(deuda.total())
                .isEqualTo(
                        deuda.insoluto()
                                .mas(deuda.reajuste())
                                .mas(deuda.interes())
                                .mas(deuda.gasto()));
        assertThat(deuda.total()).isEqualTo(Dinero.de("1022.69"));
    }

    @Test
    @DisplayName("con insoluto pendiente, pide a la politica de mora el tramo sin asentar")
    void conInsolutoPendientePideElTramoSinAsentar() {
        List<Asiento> asientos =
                List.of(cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)));
        PoliticaDeMoraDeApoyo mora = new PoliticaDeMoraDeApoyo(Dinero.de(5), Dinero.de(9));

        CalculoDeDeuda calculo = new CalculoDeDeuda(mora);
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 4, 1), REDONDEO);

        assertThat(mora.invocaciones)
                .as(
                        "se pide una vez a cada metodo, con el tramo desde el ultimo movimiento"
                                + " hasta el corte")
                .isEqualTo(2);
        assertThat(mora.desdeRecibido).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(mora.hastaRecibido).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(deuda.reajuste()).isEqualTo(Dinero.de(5));
        assertThat(deuda.interes()).isEqualTo(Dinero.de(9));
    }

    @Test
    @DisplayName("sin insoluto pendiente, no se le pide nada a la politica de mora")
    void sinInsolutoPendienteNoSePideNada() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        abono(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 15)));
        PoliticaDeMoraDeApoyo mora = new PoliticaDeMoraDeApoyo(Dinero.de(5), Dinero.de(9));

        CalculoDeDeuda calculo = new CalculoDeDeuda(mora);
        DeudaActualizada deuda =
                calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 6, 1), REDONDEO);

        assertThat(mora.invocaciones).isZero();
        assertThat(deuda.reajuste()).isEqualTo(Dinero.CERO);
        assertThat(deuda.interes()).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("mismo dia del ultimo movimiento y del corte: no hay tramo que acumular")
    void mismoDiaNoAcumulaNada() {
        List<Asiento> asientos =
                List.of(cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)));
        PoliticaDeMoraDeApoyo mora = new PoliticaDeMoraDeApoyo(Dinero.de(5), Dinero.de(9));

        CalculoDeDeuda calculo = new CalculoDeDeuda(mora);
        calculo.deudaActualizadaA(asientos, LocalDate.of(2026, 3, 1), REDONDEO);

        assertThat(mora.invocaciones)
                .as("el corte cae el mismo dia del cargo: no hay dias transcurridos que acumular")
                .isZero();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "asentadoA no agrega el devengo: es lo que la cobranza tiene que cristalizar (#33)")
    void loAsentadoNoLlevaElDevengo() {
        List<Asiento> asientos =
                List.of(cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)));

        // Una politica que SI acumula: el interes de deudaActualizadaA no esta en el libro.
        CalculoDeDeuda calculo =
                new CalculoDeDeuda(new PoliticaDeMoraDeApoyo(Dinero.de(30), Dinero.de(70)));
        LocalDate corte = LocalDate.of(2026, 6, 1);

        DeudaActualizada cobrable = calculo.deudaActualizadaA(asientos, corte, REDONDEO);
        DeudaActualizada asentado = calculo.asentadoA(asientos, corte);

        assertThat(cobrable.interes())
                .as("lo que hay que cobrar incluye el interes devengado")
                .isEqualTo(Dinero.de(70));
        assertThat(asentado.interes())
                .as("pero en el libro no hay ni un asiento de interes: se calcula, no se asienta")
                .isEqualTo(Dinero.CERO);
        assertThat(asentado.insoluto())
                .as("el insoluto si esta asentado, y es el mismo en las dos")
                .isEqualTo(cobrable.insoluto());
        assertThat(cobrable.interes().menos(asentado.interes()))
                .as(
                        "la diferencia es exactamente el cargo que #33 asienta antes de abonar; sin"
                                + " el, netear(INTERES) quedaria en negativo para siempre")
                .isEqualTo(Dinero.de(70));
    }

    @Test
    @DisplayName("asentadoA respeta la fecha de corte igual que deudaActualizadaA")
    void loAsentadoRespetaElCorte() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de(1000), LocalDate.of(2026, 3, 1)),
                        cargo(Concepto.INSOLUTO, Dinero.de(500), LocalDate.of(2026, 9, 1)));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        assertThat(calculo.asentadoA(asientos, LocalDate.of(2026, 6, 1)).insoluto())
                .as("el cargo de setiembre no entra en un corte de junio")
                .isEqualTo(Dinero.de(1000));
    }

    // ---------- #445: lo extinguible no es lo que se debia ----------
    //
    // La siembra que distingue: el abono tiene fecha valor POSTERIOR a la fecha que se pregunta.
    // Con el abono antes, «lo que se debia a esa fecha» y «lo que queda por extinguir» dan lo
    // mismo, y cualquiera de las dos funciones pasaria.

    @Test
    @DisplayName(
            "#445 — con un cobro posterior, extinguibleDesde da 0,00 donde deudaActualizadaA da"
                    + " 148,30")
    void unCobroPosteriorYaExtinguioLaDeuda() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 2, 28)),
                        abono(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 3, 10)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        LocalDate laBaja = LocalDate.of(2026, 3, 1);

        assertThat(calculo.deudaActualizadaA(asientos, laBaja, REDONDEO).insoluto())
                .as("lo que se debia el 03-01: el cobro del 03-10 es del futuro visto desde ahi")
                .isEqualTo(Dinero.de("148.30"));
        DeudaActualizada extinguible = calculo.extinguibleDesde(asientos, laBaja, REDONDEO);
        assertThat(extinguible.insoluto())
                .as(
                        "pero lo que queda por extinguir es cero: el cobro ya lo extinguio, y una"
                                + " baja del 03-01 lo extinguiria por segunda vez")
                .isEqualTo(Dinero.de("0.00"));
        assertThat(extinguible.fecha())
                .as("la cifra dice su fecha, que es la del acto (regla 9)")
                .isEqualTo(laBaja);
    }

    @Test
    @DisplayName("#445 — un cobro posterior parcial deja extinguible exactamente lo que no alcanzo")
    void unCobroPosteriorParcialDejaElResto() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 2, 28)),
                        abono(Concepto.INSOLUTO, Dinero.de("100.00"), LocalDate.of(2026, 3, 10)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        assertThat(
                        calculo.extinguibleDesde(asientos, LocalDate.of(2026, 3, 1), REDONDEO)
                                .insoluto())
                .as("148,30 menos los 100,00 que el cobro del 03-10 ya extinguio")
                .isEqualTo(Dinero.de("48.30"));
    }

    @Test
    @DisplayName("#445 — sin nada posterior, extinguibleDesde y deudaActualizadaA coinciden")
    void sinNadaPosteriorCoincideConLaDeuda() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 2, 28)),
                        cargo(Concepto.GASTO, Dinero.de("12.00"), LocalDate.of(2026, 2, 28)),
                        abono(Concepto.INSOLUTO, Dinero.de("40.00"), LocalDate.of(2026, 3, 10)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());
        LocalDate hoy = LocalDate.of(2026, 9, 23);

        assertThat(calculo.extinguibleDesde(asientos, hoy, REDONDEO))
                .as(
                        "el caso de todos los dias —la baja con la fecha de hoy— no cambia: la"
                                + " cuenta es la misma que ensena la pantalla (#551)")
                .isEqualTo(calculo.deudaActualizadaA(asientos, hoy, REDONDEO));
    }

    @Test
    @DisplayName("#445 — un cargo posterior no se puede extinguir con una baja anterior a el")
    void unCargoPosteriorNoEsExtinguibleAntes() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("100.00"), LocalDate.of(2026, 2, 28)),
                        cargo(Concepto.INSOLUTO, Dinero.de("50.00"), LocalDate.of(2026, 4, 1)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        assertThat(
                        calculo.extinguibleDesde(asientos, LocalDate.of(2026, 3, 1), REDONDEO)
                                .insoluto())
                .as(
                        "el 03-01 los 50,00 del 04-01 todavia no se deben: el minimo es el del"
                                + " 03-01, igual que antes de #445")
                .isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("#445 — el minimo es parte por parte: un cobro de insoluto no toca el gasto")
    void elMinimoEsParteAParte() {
        List<Asiento> asientos =
                List.of(
                        cargo(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 2, 28)),
                        cargo(Concepto.GASTO, Dinero.de("12.00"), LocalDate.of(2026, 2, 28)),
                        abono(Concepto.INSOLUTO, Dinero.de("148.30"), LocalDate.of(2026, 3, 10)),
                        cargo(Concepto.GASTO, Dinero.de("5.00"), LocalDate.of(2026, 3, 20)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        DeudaActualizada extinguible =
                calculo.extinguibleDesde(asientos, LocalDate.of(2026, 3, 1), REDONDEO);

        assertThat(extinguible.insoluto()).isEqualTo(Dinero.de("0.00"));
        assertThat(extinguible.gasto())
                .as(
                        "el gasto del 03-01 sigue vivo y el del 03-20 todavia no existe: es 12,00,"
                                + " y no el 0,00 del insoluto ni los 17,00 del final")
                .isEqualTo(Dinero.de("12.00"));
    }

    @Test
    @DisplayName("#445 — por periodo: cada cuota con su propio minimo, del primero al ultimo")
    void porPeriodoCadaCuotaConSuMinimo() {
        List<Asiento> asientos =
                List.of(
                        cargoDeLaCuota(2, "148.30", LocalDate.of(2026, 5, 31)),
                        cargoDeLaCuota(1, "148.30", LocalDate.of(2026, 2, 28)),
                        abonoDeLaCuota(1, "148.30", LocalDate.of(2026, 6, 20)),
                        cargoDeLaCuota(3, "148.30", LocalDate.of(2026, 8, 31)));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacionDePrueba());

        java.util.Map<Integer, DeudaActualizada> porPeriodo =
                calculo.extinguiblePorPeriodoDesde(asientos, LocalDate.of(2026, 6, 15), REDONDEO);

        assertThat(porPeriodo.keySet())
                .as("del primero al ultimo, no en el orden de la lista (#551)")
                .containsExactly(1, 2, 3);
        assertThat(porPeriodo.get(1).insoluto())
                .as("la cuota 1 la extinguio el cobro del 06-20")
                .isEqualTo(Dinero.de("0.00"));
        assertThat(porPeriodo.get(2).insoluto())
                .as("la cuota 2 no la toco nadie")
                .isEqualTo(Dinero.de("148.30"));
        assertThat(porPeriodo.get(3).insoluto())
                .as("y la 3 vence el 08-31: el 06-15 no se debe")
                .isEqualTo(Dinero.CERO);
    }

    private static Asiento cargoDeLaCuota(int cuota, String monto, LocalDate fechaValor) {
        return asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(monto), fechaValor, cuota);
    }

    private static Asiento abonoDeLaCuota(int cuota, String monto, LocalDate fechaValor) {
        return asiento(Concepto.INSOLUTO, TipoAsiento.ABONO, Dinero.de(monto), fechaValor, cuota);
    }

    private static Asiento cargo(Concepto concepto, Dinero monto, LocalDate fechaValor) {
        return asiento(concepto, TipoAsiento.CARGO, monto, fechaValor);
    }

    private static Asiento abono(Concepto concepto, Dinero monto, LocalDate fechaValor) {
        return asiento(concepto, TipoAsiento.ABONO, monto, fechaValor);
    }

    private static Asiento asiento(
            Concepto concepto, TipoAsiento tipo, Dinero monto, LocalDate fechaValor) {
        return asiento(concepto, tipo, monto, fechaValor, 1);
    }

    private static Asiento asiento(
            Concepto concepto, TipoAsiento tipo, Dinero monto, LocalDate fechaValor, int cuota) {
        Asiento nuevo =
                Asiento.nuevo(
                        EJERCICIO,
                        1L,
                        "PREDIAL",
                        concepto,
                        tipo,
                        Fase.ORDINARIA,
                        cuota,
                        null,
                        null,
                        null,
                        monto,
                        fechaValor,
                        "EM-2026-0001");
        return concepto.exigeMotivo() ? nuevo.conMotivo("motivo de la prueba") : nuevo;
    }

    /** Nunca se acumula nada: sirve para las pruebas que solo miran el neteo del libro. */
    private static final class SinAcumulacionDePrueba implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /** Devuelve cifras fijas y registra como la llamo {@link CalculoDeDeuda}, para verificarlo. */
    private static final class PoliticaDeMoraDeApoyo implements PoliticaDeMora {
        private final Dinero reajuste;
        private final Dinero interes;
        int invocaciones;
        LocalDate desdeRecibido;
        LocalDate hastaRecibido;

        PoliticaDeMoraDeApoyo(Dinero reajuste, Dinero interes) {
            this.reajuste = reajuste;
            this.interes = interes;
        }

        @Override
        public Dinero reajusteAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            registrar(desde, hasta);
            return reajuste;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            registrar(desde, hasta);
            return interes;
        }

        private void registrar(LocalDate desde, LocalDate hasta) {
            invocaciones++;
            desdeRecibido = desde;
            hastaRecibido = hasta;
        }
    }
}
