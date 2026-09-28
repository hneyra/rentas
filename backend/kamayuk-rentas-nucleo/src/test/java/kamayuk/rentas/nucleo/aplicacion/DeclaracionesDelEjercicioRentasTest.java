package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.TipoDeDeclaracion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cual es «la declaracion del ejercicio» de un predio cuando hay dos vigentes (#344, anotado en
 * #629).
 *
 * <p>La lectura de la base no lleva {@code ORDER BY}: las filas llegan en el orden que el motor
 * quiera. Por eso cada caso se prueba con las dos declaraciones <b>en los dos ordenes</b>: una
 * eleccion que dependiera de cual llega primero pasaria con uno y caeria con el otro.
 */
@DisplayName(
        "#344 — La declaracion del ejercicio es la mas reciente, llegue en el orden que llegue")
class DeclaracionesDelEjercicioRentasTest {

    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final long PREDIO = 7L;

    @Test
    @DisplayName(
            "el mismo dia, la registrada despues —identificador mayor—, como el SQL del acta y de"
                    + " la deteccion")
    void elMismoDiaGanaLaRegistradaDespues() {
        DeclaracionJurada primera = declaracion(10L, LocalDate.of(2026, 1, 15), 100L);
        DeclaracionJurada segunda = declaracion(11L, LocalDate.of(2026, 1, 15), 200L);

        assertThat(elegida(primera, segunda)).isEqualTo(11L);
        assertThat(elegida(segunda, primera))
                .as(
                        "hasta #629 ganaba la que la consulta devolviera primero, y el acta publicaba"
                                + " lo declarado en la otra")
                .isEqualTo(11L);
    }

    @Test
    @DisplayName("y entre dias distintos manda la fecha, no el identificador")
    void entreDiasDistintosMandaLaFecha() {
        DeclaracionJurada tardia = declaracion(10L, LocalDate.of(2026, 2, 20), 100L);
        DeclaracionJurada temprana = declaracion(11L, LocalDate.of(2026, 1, 15), 200L);

        assertThat(elegida(tardia, temprana)).isEqualTo(10L);
        assertThat(elegida(temprana, tardia)).isEqualTo(10L);
    }

    private static long elegida(DeclaracionJurada... enEsteOrden) {
        return new DeclaracionesDelEjercicioRentas(new LasQueDevuelve(List.of(enEsteOrden)))
                .dePredios(Set.of(PREDIO), E2026)
                .get(PREDIO)
                .declaracionId();
    }

    private static DeclaracionJurada declaracion(long id, LocalDate presentada, long fichaId) {
        return new DeclaracionJurada(
                id,
                "DJ-" + id,
                E2026,
                1L,
                TipoDeDeclaracion.PU,
                PREDIO,
                null,
                fichaId,
                presentada,
                LocalDate.of(2026, 2, 28),
                EstadoDeDeclaracion.PRESENTADA,
                null,
                "prueba",
                Observacion.de("Declaracion sembrada para la prueba"));
    }

    /** Devuelve las vigentes en el orden en que se le dieron: el de una consulta sin orden. */
    private record LasQueDevuelve(List<DeclaracionJurada> filas)
            implements DeclaracionJuradaRepository {

        @Override
        public List<DeclaracionJurada> vigentesDePredios(
                Collection<Long> predioIds, Ejercicio ejercicio) {
            return filas;
        }

        @Override
        public Optional<DeclaracionJurada> findById(long id) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Optional<DeclaracionJurada> porNumero(String numero, Ejercicio ejercicio) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Pagina<DeclaracionJurada> deContribuyente(
                long contribuyenteId, Paginacion paginacion) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Set<Long> prediosConDeclaracionVigente(
                Collection<Long> predioIds, Ejercicio ejercicio) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Set<Long> prediosConDeclaracionVigente(Ejercicio ejercicio) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public long siguienteCorrelativo(Ejercicio ejercicio) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public DeclaracionJurada insertar(DeclaracionJurada declaracion) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public DeclaracionJurada marcar(long id, EstadoDeDeclaracion nuevo) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }
    }
}
