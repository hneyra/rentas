package kamayuk.rentas.tesoreria.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dobles.CajaDeOrdenesDeMentira;
import kamayuk.rentas.tesoreria.dobles.LibroDeDeudaDeMentira;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * La mitad de {@code CobrarEnVentanillaTest} que P5D dejo huerfana, reescrita contra el camino
 * nuevo.
 *
 * <h2>De donde sale cada prueba de aqui</h2>
 *
 * <p>{@code CobrarEnVentanillaTest} era una sola clase para dos cobros —el de una deuda tributaria
 * y el de una tasa— y al extraer {@code caja} solo viajo su mitad de tasas, como {@code
 * CobrarTasasEnVentanillaTest}. Sus otros siete metodos median {@code CobrarDeuda}, que leia el
 * libro y cobraba en un solo acto; esa clase no existe en ningun repositorio, asi que las siete
 * afirmaciones se rehacen aqui o se retiran diciendo por que. Ninguna se queda sin sitio:
 *
 * <ul>
 *   <li>{@code elImporteSaleDelLibro} → aqui, con el mismo nombre;
 *   <li>{@code elReciboLlevaLaFechaDeLaDeuda} → {@code laOrdenLlevaLaFechaConLaQueSeLeyoElLibro};
 *   <li>{@code cobrarDosVecesNoEncuentraNada} → {@code emitirDosVecesNoEncuentraNada};
 *   <li>{@code elBeneficioNoDescuenta} → {@code noHayDondeDeclararUnBeneficio}, que ahora es
 *       <b>estructural</b>: en el monolito la campaña se guardaba sin efecto, y aqui ni siquiera
 *       viaja;
 *   <li>{@code laMismaObligacionDosVecesSeRechaza} → aqui, con el mismo nombre;
 *   <li><b>{@code elLibroSabeQueReciboLoOrigino} se RETIRA</b>: lo que medía —que el abono lleve
 *       {@code documento_origen = "RECIBO <n>"}— lo mide hoy {@code PagoInyectadoDosVecesTest}
 *       contra PostgreSQL real, que es donde se escribe. Rehacerla aqui seria una segunda copia de
 *       la misma afirmacion, y la de alla es mas fuerte porque lee la fila;
 *   <li><b>{@code unTipoDePagoNoImplementadoSeRechaza} se RETIRA</b>: {@code TipoDePago} ya no
 *       cruza esta frontera. Con que se paga es de la caja y se elige al cobrar, no al emitir; los
 *       tres valores que aquel metodo rechazaba —{@code A_CUENTA}, {@code PRECONVENIO} y {@code
 *       CUOTA_CONVENIO}— quedaron sin escritor posible, escrito en el entregable de P5D §6.
 * </ul>
 *
 * <p>Sin base de datos y con dobles, como la original: aqui no hay una sola sentencia SQL. Lo que
 * se prueba es el reparto —quien decide el importe y quien no—, y eso no necesita un motor.
 */
@DisplayName("P5D — La ventanilla pide que se cobre una deuda tributaria")
class EmitirOrdenDeCobroTest {

    private static final long CONTRIBUYENTE = 4_401L;
    private static final LocalDate MARZO = LocalDate.of(2026, 3, 16);
    private static final LocalDate ABRIL = LocalDate.of(2026, 4, 20);
    private static final Observacion PORQUE =
            Observacion.de("Cobranza en ventanilla, caja tributaria");

    private LibroDeDeudaDeMentira libro;
    private CajaDeOrdenesDeMentira caja;
    private EmitirOrdenDeCobro emitir;

    @BeforeEach
    void prepararLaVentanilla() {
        libro = new LibroDeDeudaDeMentira();
        caja = new CajaDeOrdenesDeMentira();
        emitir = new EmitirOrdenDeCobro(libro, caja);
    }

    @Nested
    @DisplayName("El importe lo dice el libro")
    class ElImporte {

        @Test
        @DisplayName("el importe de la orden sale del libro, no de la peticion")
        void elImporteSaleDelLibro() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "20.00", "15.50", "4.50");

            EmitirOrdenDeCobro.Emision emision = emitir.emitir(peticion(predial()), PORQUE);

            assertThat(emision.emitidas()).hasSize(1);
            assertThat(emision.emitidas().get(0).importe())
                    .as("las cuatro partes del desglose, sumadas, y ninguna quinta cifra")
                    .isEqualTo(Dinero.de("340.00"));
            assertThat(caja.recibidas().get(0).importe()).isEqualTo(Dinero.de("340.00"));
        }

        @Test
        @DisplayName("la peticion no tiene donde declarar un importe")
        void laPeticionNoLlevaImporte() {
            assertThat(componentes(EmitirOrdenDeCobro.Peticion.class))
                    .as(
                            "si la ventanilla pudiera mandar el importe, la caja lo imprimiria sin"
                                    + " discutir: no recalcula por diseño")
                    .doesNotContain("importe", "total", "monto");
        }

        @Test
        @DisplayName("no hay donde declarar una campaña de beneficio, asi que no puede descontar")
        void noHayDondeDeclararUnBeneficio() {
            // En el monolito la campaña se guardaba en el recibo COMO CONSTANCIA y sin efecto,
            // porque su descuento sigue bloqueado por D-02b. Aqui no viaja siquiera, que es una
            // barrera mas fuerte: no hay ningun sitio por el que un porcentaje inventado pudiera
            // entrar y acabar siendo una condonacion sin sustento normativo en todo un padron.
            assertThat(componentes(EmitirOrdenDeCobro.Peticion.class))
                    .doesNotContain("campaniaBeneficio", "beneficio", "descuento", "alicuota");
            assertThat(componentes(OrdenesDeCobro.Peticion.class))
                    .doesNotContain("campaniaBeneficio", "beneficio", "descuento", "alicuota");
        }

        @Test
        @DisplayName(
                "la orden lleva la fecha con la que se leyo el libro, y va dentro de su referencia")
        void laOrdenLlevaLaFechaConLaQueSeLeyoElLibro() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

            EmitirOrdenDeCobro.Emitida emitida =
                    emitir.emitir(peticion(predial()), PORQUE).emitidas().get(0);

            assertThat(emitida.actualizadoA()).isEqualTo(MARZO);
            assertThat(emitida.referencia().texto())
                    .as("regla 9: no existe «la deuda», existe la deuda a una fecha")
                    .isEqualTo("PREDIAL|2026|71||2026-03-16");
            assertThat(caja.recibidas().get(0).actualizadoA()).isEqualTo(MARZO);
        }
    }

    @Nested
    @DisplayName("Emitir dos veces no cobra dos veces")
    class DosVeces {

        @Test
        @DisplayName("una obligacion ya pagada no se encuentra: no se emite ninguna orden")
        void emitirDosVecesNoEncuentraNada() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");
            emitir.emitir(peticion(predial()), PORQUE);

            // Lo que hace el pago cuando vuelve por el buzon y se imputa.
            libro.salda("PREDIAL", 2026);

            assertThatThrownBy(() -> emitir.emitir(peticion(predial()), PORQUE))
                    .isInstanceOf(EmitirOrdenDeCobro.NadaQueCobrar.class)
                    .hasMessageContaining("no debe nada al 2026-03-16")
                    .hasMessageContaining("una orden de cero soles se cobraria");
        }

        @Test
        @DisplayName("el mismo dia, la misma obligacion es la MISMA orden")
        void elMismoDiaEsLaMismaOrden() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

            long primera = emitir.emitir(peticion(predial()), PORQUE).emitidas().get(0).ordenId();
            EmitirOrdenDeCobro.Emitida segunda =
                    emitir.emitir(peticion(predial()), PORQUE).emitidas().get(0);

            assertThat(segunda.ordenId()).isEqualTo(primera);
            assertThat(segunda.nueva())
                    .as("un reintento del mismo dia devuelve la que ya estaba")
                    .isFalse();
        }

        @Test
        @DisplayName("otro dia es OTRO importe, asi que es otra orden")
        void otroDiaEsOtraOrden() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

            long enMarzo = emitir.emitir(peticion(predial()), PORQUE).emitidas().get(0).ordenId();
            EmitirOrdenDeCobro.Emitida enAbril =
                    emitir.emitir(peticionA(ABRIL, predial()), PORQUE).emitidas().get(0);

            assertThat(enAbril.ordenId())
                    .as(
                            "sin la fecha dentro de la referencia, la primera emision congelaria el"
                                    + " importe para siempre y el interes devengado despues no se"
                                    + " podria cobrar por ninguna via")
                    .isNotEqualTo(enMarzo);
            assertThat(enAbril.nueva()).isTrue();
        }

        @Test
        @DisplayName("la misma obligacion dos veces en la misma peticion se rechaza")
        void laMismaObligacionDosVecesSeRechaza() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

            assertThatThrownBy(
                            () ->
                                    emitir.emitir(
                                            peticion(List.of(predial().get(0), predial().get(0))),
                                            PORQUE))
                    .isInstanceOf(EmitirOrdenDeCobro.ObligacionRepetida.class)
                    .hasMessageContaining("se cobraria menos de lo que la pantalla enseño");
            assertThat(caja.recibidas()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Lo que no se pudo emitir se dice")
    class LoQueNoSePudo {

        @Test
        @DisplayName("una fila marcada sin deuda sale nombrada, no se calla")
        void unaMarcadaSinDeudaSeDice() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

            EmitirOrdenDeCobro.Emision emision =
                    emitir.emitir(
                            peticion(
                                    List.of(
                                            predial().get(0),
                                            new SeleccionDeObligacion(
                                                    "ARBITRIOS", new Ejercicio(2026), 71L, null))),
                            PORQUE);

            assertThat(emision.emitidas()).hasSize(1);
            assertThat(emision.sinDeuda())
                    .as(
                            "una fila marcada que desaparece del total se lee como un error de la pantalla")
                    .hasSize(1)
                    .allSatisfy(s -> assertThat(s.tributo()).isEqualTo("ARBITRIOS"));
            assertThat(emision.total()).isEqualTo(Dinero.de("300.00"));
        }

        @Test
        @DisplayName("si la caja no contesta, no se devuelve una orden inventada")
        void laCajaCaidaNoDevuelveUnaOrdenInventada() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");
            caja.apagar();

            assertThatThrownBy(() -> emitir.emitir(peticion(predial()), PORQUE))
                    .as(
                            "una orden que se cree emitida y no lo esta deja al contribuyente"
                                    + " delante de una ventanilla que no encuentra su deuda")
                    .isInstanceOf(OrdenesDeCobro.CajaInalcanzable.class);
        }

        @Test
        @DisplayName("el libro se lee UNA vez por peticion, no una por obligacion")
        void elLibroSeLeeUnaVez() {
            libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");
            libro.debe(CONTRIBUYENTE, "ARBITRIOS", 2026, 71L, "120.00", "0.00", "0.00", "0.00");

            emitir.emitir(
                    peticion(
                            List.of(
                                    predial().get(0),
                                    new SeleccionDeObligacion(
                                            "ARBITRIOS", new Ejercicio(2026), 71L, null))),
                    PORQUE);

            assertThat(libro.consultas()).isEqualTo(1);
            assertThat(caja.recibidas()).hasSize(2);
        }
    }

    // ------------------------------------------------------------------

    private static List<SeleccionDeObligacion> predial() {
        return List.of(new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 71L, null));
    }

    private static EmitirOrdenDeCobro.Peticion peticion(List<SeleccionDeObligacion> obligaciones) {
        return peticionA(MARZO, obligaciones);
    }

    private static EmitirOrdenDeCobro.Peticion peticionA(
            LocalDate fecha, List<SeleccionDeObligacion> obligaciones) {
        return new EmitirOrdenDeCobro.Peticion(
                CONTRIBUYENTE, obligaciones, fecha, null, "03593174", "PEÑA GARCIA, MARIA");
    }

    /** Los nombres de los componentes de un {@code record}, para afirmar lo que NO tiene. */
    private static List<String> componentes(Class<?> tipo) {
        return Arrays.stream(tipo.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
