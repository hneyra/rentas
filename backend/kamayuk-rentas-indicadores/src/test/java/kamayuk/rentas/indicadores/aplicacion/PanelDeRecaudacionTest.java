package kamayuk.rentas.indicadores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.dobles.CajaDeMentira;
import kamayuk.rentas.indicadores.dobles.LibroDeMentira;
import kamayuk.rentas.indicadores.dominio.AvanceDeRecaudacion;
import kamayuk.rentas.indicadores.dominio.Cartera;
import kamayuk.rentas.indicadores.dominio.FormatoDeCifra;
import kamayuk.rentas.indicadores.dominio.Indicador;
import kamayuk.rentas.indicadores.dominio.LineaDeCartera;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El panel compone lo que otros publican, y no calcula nada por su cuenta (#56, RF-130).
 *
 * <p>Los tres puertos son dobles en memoria de las <b>APIs publicas</b> —no de repositorios—, y esa
 * es la forma del panel: si esta prueba necesitara una base de datos para escribirse, seria porque
 * el panel se estaria saltando los puertos.
 */
@DisplayName("#56 — El panel de recaudacion")
class PanelDeRecaudacionTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 13);
    private static final Instant AHORA = Instant.parse("2026-08-13T14:05:31Z");

    /** Lo que envuelve a la cifra dentro de la nota del KPI «Avance de cobranza» (#549). */
    private static final String PREFIJO_DE_LA_NOTA = "de ";

    private static final String SUFIJO_DE_LA_NOTA = " cargados";

    private final LibroDeMentira libro =
            new LibroDeMentira()
                    // Cobrado en 2026: 800 de deuda de 2026 y 200 de deuda de 2025.
                    .conRecaudado("PREDIAL", EJERCICIO, 3, "500.00", 4)
                    .conRecaudado("PREDIAL", EJERCICIO, 7, "300.00", 2)
                    .conRecaudado("PREDIAL", new Ejercicio(2025), 7, "200.00", 1)
                    .conCargado("PREDIAL", "1000.00", 10)
                    .conCargado("ARBITRIO", "400.00", 8)
                    .conPendiente("PREDIAL", "200.00", 3);

    private final CajaDeMentira caja = new CajaDeMentira().con("310.00", "10.00");

    private final PanelDeRecaudacion panel = new PanelDeRecaudacion(libro, libro, caja);

    private AvanceDeRecaudacion panel() {
        return panel.del(EJERCICIO, HOY, AHORA);
    }

    @Nested
    @DisplayName("Lo que pide")
    class LoQuePide {

        @Test
        @DisplayName("pide el ano entero del ejercicio, no el ano del reloj")
        void pideElAnoDelEjercicio() {
            panel.del(new Ejercicio(2025), HOY, AHORA);

            assertThat(libro.desde()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(libro.hasta()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(libro.ejercicioPedido()).isEqualTo(new Ejercicio(2025));
        }

        @Test
        @DisplayName("el avance de caja es el de HOY, no el del ultimo dia del ejercicio")
        void elAvanceDeCajaEsElDeHoy() {
            // Con el ejercicio en curso los dos dias caen en el mismo ano y la cifra
            // saldria igual de plausible; con un ejercicio pasado, «lo de hoy en caja»
            // seria de hace anios.
            panel.del(new Ejercicio(2025), HOY, AHORA);

            assertThat(caja.diaPedido()).isEqualTo(HOY);
        }
    }

    @Nested
    @DisplayName("Las cifras grandes")
    class LasCifrasGrandes {

        @Test
        @DisplayName("lo recaudado es el total del libro, y dice cuanto es del propio ejercicio")
        void loRecaudado() {
            Indicador recaudado = indicador("Recaudado 2026");

            assertThat(recaudado.cifra()).isEqualTo("S/ 1,000.00");
            assertThat(recaudado.importe()).isEqualTo(Dinero.de("1000.00"));
            // 800 de 2026 y 200 de 2025: las dos cifras son ciertas y el panel dice las
            // dos. Publicar solo el total haria pensar que todo corresponde a la emision
            // del ano.
            assertThat(recaudado.nota())
                    .isEqualTo("7 abonos · S/ 800.00 de deuda del propio ejercicio");
        }

        @Test
        @DisplayName("el avance es lo cobrado del ejercicio sobre lo cargado del ejercicio")
        void elAvance() {
            // Cobrado de deuda de 2026: 800. Cargado: 1400. 800/1400 = 57,1 % => 57.
            // Las dos cifras salen del libro, con el mismo criterio de reversion, y por
            // eso se pueden dividir sin explicar nada.
            Indicador avance = indicador("Avance de cobranza");

            assertThat(avance.cifra()).isEqualTo("57 %");
            assertThat(avance.nota()).isEqualTo("de S/ 1,400.00 cargados");
            assertThat(avance.importe()).as("un porcentaje no es un importe").isNull();
        }

        @Test
        @DisplayName("la cartera dice cuantas obligaciones son y a que fecha esta cortada")
        void laCartera() {
            Indicador cartera = indicador("Cartera pendiente");

            assertThat(cartera.cifra()).isEqualTo("S/ 200.00");
            // Desde #639 la fecha que acompana a la cifra es la que la DECIDE: la cartera
            // es el insoluto pendiente hasta ese dia. Antes decia «proyectado desde …» —la
            // frescura de un cache— y la cifra era la misma preguntara uno por la fecha que
            // preguntara.
            assertThat(cartera.nota()).isEqualTo("3 obligaciones · insoluto pendiente al " + HOY);
        }

        @Test
        @DisplayName("#639 — y la cartera se pide con la fecha de la peticion, no con otra")
        void laCarteraSePideConLaFechaDeLaPeticion() {
            panel.del(EJERCICIO, HOY, AHORA);

            assertThat(libro.fechaDeCorteDeLaCartera())
                    .as(
                            "con otra fecha el total seguiria siendo plausible y estaria contando"
                                    + " la cuota que aun no vence (#639)")
                    .isEqualTo(HOY);
        }

        @Test
        @DisplayName("lo de hoy en caja es el neto, y dice lo cobrado y lo anulado")
        void loDeHoyEnCaja() {
            Indicador hoy = indicador("Recaudado hoy en caja");

            assertThat(hoy.cifra()).isEqualTo("S/ 300.00");
            assertThat(hoy.nota()).isEqualTo("cobrado S/ 310.00 · anulado S/ 10.00");
        }

        @Test
        @DisplayName("todas llevan su fecha, y el panel ademas su hora")
        void todasLlevanSuFecha() {
            AvanceDeRecaudacion avance = panel();

            assertThat(avance.fechaCalculo()).isEqualTo(HOY);
            // Dos lecturas del mismo dia dan cifras distintas; sin la hora no se
            // distinguen (AC 2).
            assertThat(avance.calculadoEn()).isEqualTo(AHORA);
            assertThat(avance.indicadores())
                    .isNotEmpty()
                    .allSatisfy(indicador -> assertThat(indicador.actualizadoA()).isEqualTo(HOY));
        }
    }

    @Nested
    @DisplayName("Los bloques")
    class LosBloques {

        @Test
        @DisplayName("hay una fila por tributo, aunque solo tenga cargos")
        void unaFilaPorTributo() {
            // ARBITRIOS tiene cargos y ni un pago: es justo el que hay que mirar, y una
            // union por lo recaudado lo dejaria fuera.
            assertThat(porTributo().lineas())
                    .extracting(LineaDeCartera::concepto)
                    .containsExactly("ARBITRIO", "PREDIAL");
        }

        @Test
        @DisplayName("la fila dice lo cobrado, contra que se mide y cuanto queda")
        void laFilaDiceContraQueSeMide() {
            LineaDeCartera predial = fila(porTributo(), "PREDIAL");

            assertThat(predial.cifra()).isEqualTo("S/ 800.00");
            assertThat(predial.detalle()).isEqualTo("cargado S/ 1,000.00 · pendiente S/ 200.00");
            // 800 de 1000 ya no estan pendientes.
            assertThat(predial.avance()).isEqualTo(OptionalInt.of(80));
        }

        @Test
        @DisplayName("un tributo con cargos y sin un pago da 0 %, nunca 100 %")
        void unTributoConCargosYSinPagos() {
            // ARBITRIOS tiene 400 cargados y ni un abono, y NINGUNA fila proyectada.
            // Con la barra calculada como «(cargado - pendiente) / cargado» esto daria
            // 100 %, porque la proyeccion no distingue «cancelado» de «sin proyectar
            // todavia». Con las dos cifras del libro, el caso imposible no existe.
            LineaDeCartera arbitrios = fila(porTributo(), "ARBITRIO");

            assertThat(arbitrios.cifra()).isEqualTo("S/ 0.00");
            assertThat(arbitrios.avance()).isEqualTo(OptionalInt.of(0));
            assertThat(arbitrios.detalle()).isEqualTo("cargado S/ 400.00 · pendiente S/ 0.00");
        }

        @Test
        @DisplayName("un tributo sin cargos no lleva avance: lleva un hueco, y lo explica")
        void unTributoSinCargosNoLlevaAvance() {
            LibroDeMentira sinCargos =
                    new LibroDeMentira().conRecaudado("MULTA_TRANSITO", EJERCICIO, 4, "150.00", 2);
            PanelDeRecaudacion otro = new PanelDeRecaudacion(sinCargos, sinCargos, caja);

            LineaDeCartera multa =
                    fila(otro.del(EJERCICIO, HOY, AHORA).carteras().get(0), "MULTA_TRANSITO");

            // Vacio, NO cero: un 0 % se lee como «no se ha cobrado nada», y aqui lo que
            // pasa es que no hay contra que medir (regla 5).
            assertThat(multa.avance()).isEqualTo(OptionalInt.empty());
            assertThat(multa.detalle()).isEqualTo("sin cargos asentados en el ejercicio");
        }

        @Test
        @DisplayName("hay una fila por mes con movimiento, con la misma base que el otro bloque")
        void unaFilaPorMes() {
            Cartera porMes = panel().carteras().get(1);

            assertThat(porMes.lineas())
                    .extracting(LineaDeCartera::concepto)
                    .containsExactly("Mes 3", "Mes 7");
            // Julio: 300 de 2026 + 200 de 2025 = 500 sobre 1400 cargados => 35 %.
            LineaDeCartera julio = fila(porMes, "Mes 7");
            assertThat(julio.cifra()).isEqualTo("S/ 500.00");
            assertThat(julio.detalle()).isEqualTo("3 abonos");
            assertThat(julio.avance()).isEqualTo(OptionalInt.of(35));
        }

        @Test
        @DisplayName("cada bloque dice contra que mide sus barras")
        void cadaBloqueDiceContraQueMide() {
            // Dos bloques cuyas barras significaran cosas distintas sin decirlo invitan a
            // compararlas, que es peor que no tener barras.
            assertThat(panel().carteras())
                    .extracting(Cartera::nota)
                    .allSatisfy(nota -> assertThat(nota).contains("la parte del insoluto cargado"));
        }
    }

    @Nested
    @DisplayName("Sin datos")
    class SinDatos {

        @Test
        @DisplayName(
                "una municipalidad recien implantada ve ceros y «—», nunca una cifra inventada")
        void unaMunicipalidadRecienImplantada() {
            LibroDeMentira vacio = new LibroDeMentira();
            AvanceDeRecaudacion avance =
                    new PanelDeRecaudacion(vacio, vacio, new CajaDeMentira())
                            .del(EJERCICIO, HOY, AHORA);

            assertThat(indicador(avance, "Recaudado 2026").cifra()).isEqualTo("S/ 0.00");
            assertThat(indicador(avance, "Avance de cobranza").cifra()).isEqualTo("—");
            assertThat(indicador(avance, "Avance de cobranza").nota())
                    .isEqualTo("sin cargos asentados en el ejercicio: no hay avance que medir");
            assertThat(indicador(avance, "Cartera pendiente").nota())
                    .isEqualTo("sin obligaciones pendientes en el ejercicio");
            assertThat(avance.carteras()).allSatisfy(c -> assertThat(c.lineas()).isEmpty());
        }
    }

    @Nested
    @DisplayName("#549 — Lo cargado es un campo, no una frase")
    class LoCargadoEsUnCampo {

        @Test
        @DisplayName("AC 1.1 — el panel publica lo cargado del ejercicio, con su fecha")
        void elPanelPublicaLoCargado() {
            AvanceDeRecaudacion avance = panel();

            // 1000 de PREDIAL y 400 de ARBITRIOS: lo emitido del ejercicio.
            assertThat(avance.cargado()).isEqualTo(Dinero.de("1400.00"));
            assertThat(avance.cargadoA())
                    .as("es la fecha con la que el libro contesto, no la que el panel estampa")
                    .isEqualTo(HOY);
        }

        @Test
        @DisplayName("AC 1.2 — y es el mismo numero que la nota del KPI de avance dice")
        void elCampoDiceLoMismoQueLaNota() {
            // La nota es «de S/ 1,400.00 cargados». Se le quita el envoltorio y se compara
            // con el campo redactado igual: si la nota se compusiera con otra cifra —o el
            // campo saliera de otra suma— las dos dejarian de coincidir, y esta es la
            // unica prueba que lo ve. La cifra de la frase es la que la pantalla estuvo a
            // punto de tener que sacar con una expresion regular.
            AvanceDeRecaudacion avance = panel();
            String nota = indicador("Avance de cobranza").nota();

            assertThat(nota).startsWith(PREFIJO_DE_LA_NOTA).endsWith(SUFIJO_DE_LA_NOTA);
            String deLaFrase =
                    nota.substring(
                            PREFIJO_DE_LA_NOTA.length(),
                            nota.length() - SUFIJO_DE_LA_NOTA.length());

            assertThat(deLaFrase).isEqualTo(FormatoDeCifra.importe(avance.cargado()));
        }

        @Test
        @DisplayName("AC 1.2 — sin cargos la nota no lleva cifra, y el campo es S/ 0.00, no nulo")
        void sinCargosElCampoEsCero() {
            // El borde que la comparacion anterior no puede cubrir: la nota pasa a ser
            // «sin cargos asentados en el ejercicio: no hay avance que medir» y no hay de
            // donde extraer nada. El campo sigue existiendo, y su valor es un hecho que se
            // puede afirmar —el libro no tiene ni un cargo—, no un hueco como el avance.
            LibroDeMentira vacio = new LibroDeMentira();
            AvanceDeRecaudacion avance =
                    new PanelDeRecaudacion(vacio, vacio, new CajaDeMentira())
                            .del(EJERCICIO, HOY, AHORA);

            assertThat(indicador(avance, "Avance de cobranza").nota())
                    .doesNotContain(FormatoDeCifra.SIN_CIFRA)
                    .doesNotContain("S/");
            assertThat(avance.cargado()).isEqualTo(Dinero.CERO);
            assertThat(avance.cargadoA()).isEqualTo(HOY);
        }

        @Test
        @DisplayName("AC 1.3 — la fila de un tributo publica su cargado y su pendiente")
        void laFilaPublicaSuCargadoYSuPendiente() {
            LineaDeCartera predial = fila(porTributo(), "PREDIAL");

            assertThat(predial.cargado()).isEqualTo(Dinero.de("1000.00"));
            assertThat(predial.pendiente()).isEqualTo(Dinero.de("200.00"));
        }

        @Test
        @DisplayName("AC 1.3 — y son exactamente las dos cifras que el detalle ya decia")
        void losCamposDicenLoMismoQueElDetalle() {
            // El `sub` se queda: es lo que se lee. Lo que se añade es la misma cifra sin
            // redactar. Cruzar los dos campos —pasarle a la fila el pendiente donde va el
            // cargado— produce una fila cuyo texto y cuyos campos se contradicen, y esta
            // es la comparacion que lo ve.
            for (LineaDeCartera linea : porTributo().lineas()) {
                if (linea.cargado() == null) {
                    continue;
                }
                assertThat(linea.detalle())
                        .as("el texto y los campos de %s", linea.concepto())
                        .isEqualTo(
                                "cargado "
                                        + FormatoDeCifra.importe(linea.cargado())
                                        + " · pendiente "
                                        + FormatoDeCifra.importe(
                                                Objects.requireNonNull(linea.pendiente())));
            }
        }

        @Test
        @DisplayName("AC 1.3 — un tributo sin cargos lleva cero cargado, no un hueco")
        void unTributoSinCargosLlevaCeroCargado() {
            LibroDeMentira sinCargos =
                    new LibroDeMentira().conRecaudado("MULTA_TRANSITO", EJERCICIO, 4, "150.00", 2);
            PanelDeRecaudacion otro = new PanelDeRecaudacion(sinCargos, sinCargos, caja);

            LineaDeCartera multa =
                    fila(otro.del(EJERCICIO, HOY, AHORA).carteras().get(0), "MULTA_TRANSITO");

            // La fila SI agrupa cargos —lo que pasa es que son cero—, asi que el campo
            // existe. Lo que no existe es su avance, y eso lo dice `avance()`.
            assertThat(multa.cargado()).isEqualTo(Dinero.CERO);
            assertThat(multa.pendiente()).isEqualTo(Dinero.CERO);
            assertThat(multa.avance()).isEqualTo(OptionalInt.empty());
        }

        @Test
        @DisplayName("AC 1.3 — la fila de un MES no tiene ninguno de los dos, y va nula")
        void laFilaDeUnMesNoTieneNinguno() {
            // Lo cargado es del ejercicio entero y la cartera no se reparte por mes de
            // cobro. Un cero aqui afirmaria que ese mes cargo cero —la fila de julio diria
            // «cargado S/ 0.00» junto a «S/ 500.00 cobrados»—, que es el mismo defecto que
            // `avanceConocido` existe para evitar en la barra.
            Cartera porMes = panel().carteras().get(1);

            assertThat(porMes.lineas())
                    .isNotEmpty()
                    .allSatisfy(
                            linea -> {
                                assertThat(linea.cargado()).isNull();
                                assertThat(linea.pendiente()).isNull();
                            });
        }
    }

    // ------------------------------------------------------------------

    private Cartera porTributo() {
        return panel().carteras().get(0);
    }

    private Indicador indicador(String concepto) {
        return indicador(panel(), concepto);
    }

    private static Indicador indicador(AvanceDeRecaudacion avance, String concepto) {
        return buscar(avance.indicadores(), Indicador::concepto, concepto);
    }

    private static LineaDeCartera fila(Cartera cartera, String concepto) {
        return buscar(cartera.lineas(), LineaDeCartera::concepto, concepto);
    }

    private static <T> T buscar(
            List<T> elementos, java.util.function.Function<T, String> nombre, String buscado) {
        return elementos.stream()
                .filter(elemento -> nombre.apply(elemento).equals(buscado))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No esta en el panel: " + buscado));
    }
}
