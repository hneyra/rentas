package kamayuk.rentas.nucleo.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La declaracion jurada, sin base de datos y sin reloj (RF-023, #28). */
@DisplayName("#28 — Declaracion jurada")
class DeclaracionJuradaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate LIMITE = LocalDate.of(2026, 2, 28);
    private static final Observacion OBSERVACION = Observacion.de("declaracion de prueba");

    @Test
    @DisplayName("presentada dentro del plazo no esta fuera de plazo")
    void dentroDelPlazo() {
        DeclaracionJurada dj = nueva(LocalDate.of(2026, 2, 28), LIMITE);

        assertThat(dj.fueraDePlazo()).isFalse();
    }

    @Test
    @DisplayName("presentada un dia despues del limite esta fuera de plazo")
    void unDiaDespuesDelLimite() {
        DeclaracionJurada dj = nueva(LocalDate.of(2026, 3, 1), LIMITE);

        assertThat(dj.fueraDePlazo()).isTrue();
    }

    @Test
    @DisplayName("el plazo se recibe, nunca se calcula desde un literal (regla 5)")
    void sinFechaLimiteNoSeConstruye() {
        assertThatThrownBy(() -> nueva(LocalDate.of(2026, 2, 28), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("parametros sellados");
    }

    @Test
    @DisplayName("una VEHICULAR no lleva predio, y una predial no lleva vehiculo")
    void tipoYUnidadSonConsistentes() {
        assertThatThrownBy(
                        () ->
                                new DeclaracionJurada(
                                        null,
                                        "DJ-1",
                                        EJERCICIO,
                                        1L,
                                        TipoDeDeclaracion.VEHICULAR,
                                        7L,
                                        null,
                                        null,
                                        LocalDate.of(2026, 2, 1),
                                        LIMITE,
                                        EstadoDeDeclaracion.PRESENTADA,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no lleva predio");

        assertThatThrownBy(
                        () ->
                                new DeclaracionJurada(
                                        null,
                                        "DJ-2",
                                        EJERCICIO,
                                        1L,
                                        TipoDeDeclaracion.HR,
                                        null,
                                        9L,
                                        null,
                                        LocalDate.of(2026, 2, 1),
                                        LIMITE,
                                        EstadoDeDeclaracion.PRESENTADA,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Solo una declaracion VEHICULAR");
    }

    @Test
    @DisplayName("RECTIFICATORIA sin djRectificaId no se construye, y HR con uno tampoco")
    void rectificaIdConsistenteConElTipo() {
        assertThatThrownBy(
                        () ->
                                new DeclaracionJurada(
                                        null,
                                        "DJ-3",
                                        EJERCICIO,
                                        1L,
                                        TipoDeDeclaracion.RECTIFICATORIA,
                                        5L,
                                        null,
                                        null,
                                        LocalDate.of(2026, 3, 5),
                                        LIMITE,
                                        EstadoDeDeclaracion.PRESENTADA,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("que DJ sustituye");

        assertThatThrownBy(
                        () ->
                                new DeclaracionJurada(
                                        null,
                                        "DJ-4",
                                        EJERCICIO,
                                        1L,
                                        TipoDeDeclaracion.HR,
                                        5L,
                                        null,
                                        null,
                                        LocalDate.of(2026, 2, 1),
                                        LIMITE,
                                        EstadoDeDeclaracion.PRESENTADA,
                                        1L,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Solo una declaracion RECTIFICATORIA");
    }

    @Test
    @DisplayName("rectificadaPor crea otra DJ, tipo RECTIFICATORIA, sin tocar la anterior")
    void rectificadaPorCreaOtra() {
        DeclaracionJurada original =
                new DeclaracionJurada(
                        10L,
                        "DJ-100",
                        EJERCICIO,
                        1L,
                        TipoDeDeclaracion.HR,
                        5L,
                        null,
                        77L,
                        LocalDate.of(2026, 2, 1),
                        LIMITE,
                        EstadoDeDeclaracion.PRESENTADA,
                        null,
                        "cajera.1",
                        OBSERVACION);

        DeclaracionJurada rectificatoria =
                original.rectificadaPor(
                        "DJ-101",
                        5L,
                        null,
                        78L,
                        LocalDate.of(2026, 3, 10),
                        LIMITE,
                        Observacion.de("correccion de area"));

        assertThat(rectificatoria.esNueva()).isTrue();
        assertThat(rectificatoria.tipo()).isEqualTo(TipoDeDeclaracion.RECTIFICATORIA);
        assertThat(rectificatoria.djRectificaId()).isEqualTo(10L);
        assertThat(rectificatoria.fichaCatastralId()).isEqualTo(78L);

        assertThat(original.estado())
                .as(
                        "original no cambia por construir su rectificatoria: el UPDATE lo hace el"
                                + " caso de uso, no este metodo")
                .isEqualTo(EstadoDeDeclaracion.PRESENTADA);
    }

    @Test
    @DisplayName("solo se rectifica una DJ ya guardada")
    void soloSeRectificaUnaGuardada() {
        DeclaracionJurada sinGuardar =
                DeclaracionJurada.nueva(
                        "DJ-1",
                        EJERCICIO,
                        1L,
                        TipoDeDeclaracion.HR,
                        5L,
                        null,
                        null,
                        LocalDate.of(2026, 2, 1),
                        LIMITE,
                        OBSERVACION);

        assertThatThrownBy(
                        () ->
                                sinGuardar.rectificadaPor(
                                        "DJ-2",
                                        5L,
                                        null,
                                        null,
                                        LocalDate.of(2026, 3, 1),
                                        LIMITE,
                                        OBSERVACION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ya esta guardada");
    }

    @Test
    @DisplayName("sustituida() marca el estado, y no se puede sustituir dos veces")
    void sustituidaMarcaElEstado() {
        DeclaracionJurada guardada =
                new DeclaracionJurada(
                        10L,
                        "DJ-100",
                        EJERCICIO,
                        1L,
                        TipoDeDeclaracion.HR,
                        5L,
                        null,
                        77L,
                        LocalDate.of(2026, 2, 1),
                        LIMITE,
                        EstadoDeDeclaracion.PRESENTADA,
                        null,
                        "cajera.1",
                        OBSERVACION);

        DeclaracionJurada sustituida = guardada.sustituida();

        assertThat(sustituida.estado()).isEqualTo(EstadoDeDeclaracion.SUSTITUIDA);
        assertThat(sustituida.numero()).isEqualTo(guardada.numero());
        assertThat(sustituida.fechaPresentacion()).isEqualTo(guardada.fechaPresentacion());

        assertThatThrownBy(sustituida::sustituida).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sin observacion no se construye (regla 10)")
    void sinObservacionNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                DeclaracionJurada.nueva(
                                        "DJ-1",
                                        EJERCICIO,
                                        1L,
                                        TipoDeDeclaracion.HR,
                                        5L,
                                        null,
                                        null,
                                        LocalDate.of(2026, 2, 1),
                                        LIMITE,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Sin observacion");
    }

    @Test
    @DisplayName("observar una presentada la deja OBSERVADA, y observarla otra vez no es un acto")
    void observarUnaPresentada() {
        DeclaracionJurada guardada = guardada();

        DeclaracionJurada observada = guardada.observada();

        assertThat(observada.estado()).isEqualTo(EstadoDeDeclaracion.OBSERVADA);
        assertThat(observada.numero())
                .as("regla 4: observar no toca ninguna otra columna")
                .isEqualTo(guardada.numero());
        assertThatThrownBy(observada::observada)
                .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
    }

    @Test
    @DisplayName("una OBSERVADA se anula y se rectifica; una ANULADA no hace ninguna de las dos")
    void losCaminosDeLaObservada() {
        DeclaracionJurada observada = guardada().observada();

        assertThat(observada.anulada().estado()).isEqualTo(EstadoDeDeclaracion.ANULADA);
        assertThat(observada.sustituida().estado()).isEqualTo(EstadoDeDeclaracion.SUSTITUIDA);

        DeclaracionJurada anulada = guardada().anulada();
        assertThatThrownBy(anulada::observada)
                .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        assertThatThrownBy(anulada::anulada).isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        assertThatThrownBy(anulada::sustituida)
                .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
    }

    @Test
    @DisplayName("solo se rectifica una declaracion en pie: la anulada y la sustituida no")
    void soloSeRectificaUnaEnPie() {
        for (DeclaracionJurada terminal :
                java.util.List.of(guardada().anulada(), guardada().sustituida())) {
            assertThatThrownBy(
                            () ->
                                    terminal.rectificadaPor(
                                            "DJ-2",
                                            5L,
                                            null,
                                            null,
                                            LocalDate.of(2026, 4, 1),
                                            LIMITE,
                                            OBSERVACION))
                    .as(
                            "rectificar una anulada la reviviria; rectificar una sustituida dejaria"
                                    + " dos rectificatorias vivas sobre la misma DJ")
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }
    }

    /** Como sale del repositorio: con identificador, que es lo que exigen las transiciones. */
    private static DeclaracionJurada guardada() {
        DeclaracionJurada sinGuardar = nueva(LocalDate.of(2026, 2, 1), LIMITE);
        return new DeclaracionJurada(
                7L,
                sinGuardar.numero(),
                sinGuardar.ejercicio(),
                sinGuardar.contribuyenteId(),
                sinGuardar.tipo(),
                sinGuardar.predioId(),
                sinGuardar.vehiculoId(),
                sinGuardar.fichaCatastralId(),
                sinGuardar.fechaPresentacion(),
                sinGuardar.fechaLimite(),
                sinGuardar.estado(),
                sinGuardar.djRectificaId(),
                "prueba",
                sinGuardar.observacion());
    }

    private static DeclaracionJurada nueva(LocalDate presentacion, LocalDate limite) {
        return DeclaracionJurada.nueva(
                "DJ-1",
                EJERCICIO,
                1L,
                TipoDeDeclaracion.HR,
                5L,
                null,
                null,
                presentacion,
                limite,
                OBSERVACION);
    }
}
