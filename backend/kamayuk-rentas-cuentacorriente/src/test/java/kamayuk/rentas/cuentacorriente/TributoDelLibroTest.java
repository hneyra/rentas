package kamayuk.rentas.cuentacorriente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El vocabulario de tributos del libro, sin base y sin reloj (#553).
 *
 * <p>Mide las dos mitades que el issue pide y una tercera que ninguna de las dos dice:
 *
 * <ul>
 *   <li><b>AC 1 — un solo origen.</b> Los doce textos salen de aqui, y los cinco que el {@code
 *       CHECK} de {@code determinacion} no tenia —{@code MULTA_TRIBUTARIA}, {@code MULTA_TRANSITO},
 *       {@code MULTA_ADMINISTRATIVA}, {@code CONVENIO} y {@code COSTAS PROCESALES}— estan dentro.
 *       Que coincidan con lo que la base admite lo mide {@code VocabularioDeTributosJdbcTest}
 *       contra PostgreSQL, que es donde vive la otra barrera (#188, #435).
 *   <li><b>AC 2 — el rechazo nombra el valor y los admitidos.</b> Las dos mitades hacen falta: sin
 *       el valor no se distingue «me equivoque de grafia» de «este tributo no existe», y sin la
 *       lista quien atiende no sabe cual teclear.
 *   <li><b>Lo que NO valida, y es deliberado.</b> El constructor canonico de {@link Asiento} no
 *       comprueba el vocabulario, porque es el que usa el repositorio al <b>leer</b> una fila y el
 *       que usa {@link Asiento#reversionDe} al corregirla. Las filas escritas antes de que el
 *       vocabulario existiera no se pueden reparar (V7, regla 4): validar al leer dejaria sin
 *       estado de cuenta a la instalacion que tenga una.
 * </ul>
 */
@DisplayName("#553 — El tributo del libro es un vocabulario cerrado")
class TributoDelLibroTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 15);

    @Nested
    @DisplayName("AC 1 — un solo origen, con los cinco que faltaban")
    class ElVocabulario {

        @Test
        @DisplayName("los cinco nombres que el CHECK de determinacion no tenia estan dentro")
        void losCincoQueFaltabanEstanDentro() {
            assertThat(TributoDelLibro.admitidos())
                    .as("los escriben fiscalizacion, sanciones, tesoreria y coactiva desde #42")
                    .contains(
                            "MULTA_TRIBUTARIA",
                            "MULTA_TRANSITO",
                            "MULTA_ADMINISTRATIVA",
                            "CONVENIO",
                            "COSTAS PROCESALES");
        }

        @Test
        @DisplayName("los siete de determinacion (V2) siguen dentro, con ARBITRIO en singular")
        void losSieteDeDeterminacionSiguenDentro() {
            assertThat(TributoDelLibro.admitidos())
                    .contains(
                            "PREDIAL",
                            "ARBITRIO",
                            "VEHICULAR",
                            "ALCABALA",
                            "ESPECTACULOS",
                            "ANUNCIOS",
                            "JUEGOS")
                    .as("y el plural que sembraba deuda.csv no es ninguno de ellos")
                    .doesNotContain("ARBITRIOS");
        }

        @Test
        @DisplayName("«COSTAS PROCESALES» lleva un espacio, no un guion bajo")
        void lasCostasLlevanEspacio() {
            // El nombre de la constante NO puede decidir el texto almacenado: esas filas estan
            // escritas desde #42, y COSTAS_PROCESALES huerfanaria las costas ya liquidadas.
            assertThat(TributoDelLibro.COSTAS_PROCESALES.texto()).isEqualTo("COSTAS PROCESALES");
            assertThat(TributoDelLibro.admitidos()).doesNotContain("COSTAS_PROCESALES");
        }

        @Test
        @DisplayName("ninguno pasa de los 20 caracteres de la columna")
        void ningunoPasaDeVeinte() {
            // MULTA_ADMINISTRATIVA mide exactamente 20: el vocabulario no tiene margen.
            assertThat(TributoDelLibro.admitidos())
                    .allSatisfy(texto -> assertThat(texto.length()).isBetween(1, 20));
            assertThat(TributoDelLibro.MULTA_ADMINISTRATIVA.texto()).hasSize(20);
        }

        @Test
        @DisplayName("se lee normalizado, igual que lo normaliza el asiento")
        void seLeeNormalizado() {
            assertThat(TributoDelLibro.de("  predial ")).isEqualTo(TributoDelLibro.PREDIAL);
            assertThat(TributoDelLibro.esDelVocabulario(" costas procesales "))
                    .as("la deteccion usa la misma normalizacion que la escritura")
                    .isTrue();
            assertThat(TributoDelLibro.esDelVocabulario("ARBITRIOS")).isFalse();
            assertThat(TributoDelLibro.esDelVocabulario(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("AC 2 — el rechazo nombra el valor recibido y los admitidos")
    class ElRechazo {

        @Test
        @DisplayName("un asiento nuevo con ARBITRIOS se rechaza, nombrando la grafia y la lista")
        void unAsientoNuevoConLaGrafiaViejaSeRechaza() {
            assertThatThrownBy(() -> asientoNuevo("ARBITRIOS"))
                    .isInstanceOf(TributoDelLibro.TributoDesconocido.class)
                    .hasMessageContaining("'ARBITRIOS'")
                    .as("sin la lista, quien atiende no sabe cual teclear")
                    .hasMessageContaining("ARBITRIO")
                    .hasMessageContaining("COSTAS PROCESALES");
        }

        @Test
        @DisplayName("y el texto de 21 caracteres del issue tambien, por el vocabulario")
        void elTextoLargoTambien() {
            // Antes contestaba «El tributo va de 1 a 20 caracteres», que dice cuanto cabe y no
            // que tributos existen: el desplegable del manual ofrecia «ARBITRIOS MUNICIPALES».
            assertThatThrownBy(() -> asientoNuevo("ARBITRIOS MUNICIPALES"))
                    .isInstanceOf(TributoDelLibro.TributoDesconocido.class)
                    .hasMessageContaining("no es uno de los del libro");
        }

        @Test
        @DisplayName("un movimiento de deuda con la grafia vieja se rechaza al construirse")
        void unMovimientoDeDeudaSeRechazaAlConstruirse() {
            // Aqui y no en ClaveDeSaldo: la clave tambien identifica una obligacion que se LEE.
            // Y es lo que hace que el alta conteste 422 y no 500 — el controlador construye este
            // objeto dentro del try que traduce IllegalArgumentException.
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeDeuda(
                                            SentidoDelMovimiento.ALTA,
                                            new ClaveDeSaldo(
                                                    7L, "ARBITRIOS", EJERCICIO, 1, null, null),
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Fase.ORDINARIA,
                                            FECHA,
                                            "RES-001",
                                            null))
                    .isInstanceOf(TributoDelLibro.TributoDesconocido.class)
                    .hasMessageContaining("'ARBITRIOS'");
        }

        @Test
        @DisplayName("el del vocabulario entra, y entra normalizado")
        void elDelVocabularioEntra() {
            assertThat(asientoNuevo("  arbitrio ").tributo()).isEqualTo("ARBITRIO");
        }
    }

    @Nested
    @DisplayName("Lo que el vocabulario NO cierra: leer y reversar una fila anterior")
    class LoQueSigueAbierto {

        @Test
        @DisplayName("el constructor canonico admite la grafia vieja: es el que usa el mapeador")
        void elConstructorCanonicoAdmiteLaGrafiaVieja() {
            // Si validara, `AsientoRepositoryJdbc.mapear` no podria reconstruir una fila
            // anterior a V74 y la instalacion que tenga una se quedaria sin estado de cuenta,
            // sin panel y sin caja. Las filas no se pueden corregir (V7, regla 4): se leen.
            assertThatCode(() -> asientoGuardado(99L, "ARBITRIOS")).doesNotThrowAnyException();
            assertThat(asientoGuardado(99L, "ARBITRIOS").tributo()).isEqualTo("ARBITRIOS");
        }

        @Test
        @DisplayName("la reversion copia el tributo: es el unico modo de corregir un asiento")
        void laReversionCopiaElTributo() {
            // Regla 4: un asiento equivocado no se corrige, se reversa. Cerrar este camino
            // sobre las filas con grafia vieja seria cerrarlo justo donde hace falta, y por eso
            // V74 exceptua del CHECK a la fila que reversa otra.
            Asiento reversion =
                    Asiento.reversionDe(
                            asientoGuardado(99L, "ARBITRIOS"),
                            FECHA,
                            "RES-REVERSION",
                            "Se asento con la grafia equivocada");

            assertThat(reversion.tributo()).isEqualTo("ARBITRIOS");
            assertThat(reversion.asientoReversadoId()).isEqualTo(99L);
            assertThat(reversion.tipo()).isEqualTo(TipoAsiento.ABONO);
        }

        @Test
        @DisplayName("y por eso se pueden DETECTAR: esDelVocabulario las distingue")
        void sePuedenDetectar() {
            assertThat(
                            TributoDelLibro.esDelVocabulario(
                                    asientoGuardado(99L, "ARBITRIOS").tributo()))
                    .isFalse();
            assertThat(TributoDelLibro.esDelVocabulario(asientoNuevo("ARBITRIO").tributo()))
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------

    private static Asiento asientoNuevo(String tributo) {
        return Asiento.nuevo(
                EJERCICIO,
                7L,
                tributo,
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                1,
                null,
                null,
                null,
                Dinero.de("100.00"),
                FECHA,
                "RES-001");
    }

    /** Una fila ya guardada, como la que devuelve el mapeador del repositorio. */
    private static Asiento asientoGuardado(long id, String tributo) {
        return new Asiento(
                id,
                EJERCICIO,
                7L,
                tributo,
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                1,
                null,
                null,
                null,
                Dinero.de("100.00"),
                FECHA,
                "RES-001",
                null,
                "cajero",
                null,
                null);
    }
}
