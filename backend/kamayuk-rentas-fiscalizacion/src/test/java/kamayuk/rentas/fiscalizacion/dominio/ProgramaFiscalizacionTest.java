package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#45 — ProgramaFiscalizacion")
class ProgramaFiscalizacionTest {

    @Test
    @DisplayName("un programa nuevo no tiene id, y nace ABIERTO")
    void unProgramaNuevoNoTieneIdYNaceAbierto() {
        ProgramaFiscalizacion programa =
                ProgramaFiscalizacion.nuevo(
                        "PF-001",
                        "Muestra de riesgo alto",
                        TipoDePrograma.PREDIAL,
                        LocalDate.of(2026, 3, 1),
                        null);

        assertThat(programa.esNuevo()).isTrue();
        assertThat(programa.estado()).isEqualTo(EstadoDePrograma.ABIERTO);
    }

    @Test
    @DisplayName("sin codigo no se construye")
    void sinCodigoNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                ProgramaFiscalizacion.nuevo(
                                        "  ",
                                        "descripcion",
                                        TipoDePrograma.PREDIAL,
                                        LocalDate.of(2026, 1, 1),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la fecha de fin no puede ser anterior a la de inicio")
    void laFechaDeFinNoPuedeSerAnteriorALaDeInicio() {
        assertThatThrownBy(
                        () ->
                                ProgramaFiscalizacion.nuevo(
                                        "PF-002",
                                        "descripcion",
                                        TipoDePrograma.VEHICULAR,
                                        LocalDate.of(2026, 3, 1),
                                        LocalDate.of(2026, 2, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("#343 — solo un programa PREDIAL sortea predios, y el VEHICULAR no le falta nada")
    void soloElPredialSorteaPredios() {
        ProgramaFiscalizacion vehicular =
                ProgramaFiscalizacion.nuevo(
                        "PF-VEH-01",
                        "Vehiculos omisos",
                        TipoDePrograma.VEHICULAR,
                        LocalDate.of(2026, 3, 1),
                        null,
                        new kamayuk.rentas.dominio.Ejercicio(2026),
                        null,
                        CondicionFiscalizada.OMISO,
                        "R. MENDOZA CRUZ");
        ProgramaFiscalizacion predial =
                ProgramaFiscalizacion.nuevo(
                        "PF-PRED-01",
                        "Predios omisos",
                        TipoDePrograma.PREDIAL,
                        LocalDate.of(2026, 3, 1),
                        null,
                        new kamayuk.rentas.dominio.Ejercicio(2026),
                        null,
                        CondicionFiscalizada.OMISO,
                        "R. MENDOZA CRUZ");

        assertThat(predial.sorteaPredios()).isTrue();
        assertThat(vehicular.sorteaPredios())
                .as("la deteccion es el cruce del padron de PREDIOS: no existe una de vehiculos")
                .isFalse();
        assertThat(vehicular.parametrosDeLaMuestra())
                .as(
                        "y no es que le falte un parametro: por eso la regla no vive en"
                                + " parametrosDeLaMuestra(), cuyo mensaje mentiria")
                .isEmpty();
    }

    @Test
    @DisplayName("#341 — cerrado() devuelve la copia CERRADA y no toca nada mas")
    void cerrarSoloMueveElEstado() {
        ProgramaFiscalizacion abierto =
                new ProgramaFiscalizacion(
                        7L,
                        "PF-2025-OMI",
                        "Omisos de 2025",
                        TipoDePrograma.PREDIAL,
                        LocalDate.of(2025, 3, 1),
                        LocalDate.of(2025, 12, 31),
                        EstadoDePrograma.ABIERTO,
                        new kamayuk.rentas.dominio.Ejercicio(2025),
                        "01",
                        CondicionFiscalizada.OMISO,
                        "R. MENDOZA CRUZ");

        ProgramaFiscalizacion cerrado = abierto.cerrado();

        assertThat(cerrado.estado()).isEqualTo(EstadoDePrograma.CERRADO);
        assertThat(cerrado)
                .as("lo que el programa declaro es con lo que sorteo: no se reescribe al cerrar")
                .usingRecursiveComparison()
                .ignoringFields("estado")
                .isEqualTo(abierto);
        assertThat(abierto.estado())
                .as("es una copia: el original no cambia")
                .isEqualTo(EstadoDePrograma.ABIERTO);
    }

    @Test
    @DisplayName("#341 — uno EN_PROCESO tambien se cierra: lo que lo impide es estar cerrado")
    void enProcesoTambienSeCierra() {
        ProgramaFiscalizacion enProceso =
                new ProgramaFiscalizacion(
                        8L,
                        "PF-MIGRADO",
                        "Traido de un padron migrado",
                        TipoDePrograma.PREDIAL,
                        LocalDate.of(2024, 3, 1),
                        null,
                        EstadoDePrograma.EN_PROCESO);

        assertThat(enProceso.cerrado().estado()).isEqualTo(EstadoDePrograma.CERRADO);
    }

    @Test
    @DisplayName("#341 — un programa cerrado no se vuelve a cerrar ni se reabre")
    void unCerradoNoSeVuelveACerrar() {
        ProgramaFiscalizacion cerrado =
                new ProgramaFiscalizacion(
                                9L,
                                "PF-2024-OMI",
                                "Omisos de 2024",
                                TipoDePrograma.PREDIAL,
                                LocalDate.of(2024, 3, 1),
                                null,
                                EstadoDePrograma.ABIERTO)
                        .cerrado();

        assertThatThrownBy(cerrado::cerrado)
                .isInstanceOf(ProgramaFiscalizacion.TransicionIlegal.class)
                .hasMessageContaining("PF-2024-OMI")
                .hasMessageContaining("CERRADO");
    }
}
