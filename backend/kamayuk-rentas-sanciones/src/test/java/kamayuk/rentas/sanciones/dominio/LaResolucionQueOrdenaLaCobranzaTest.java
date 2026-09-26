package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #384 — Cuál de las resoluciones de una papeleta ordena su cobranza, sin base de datos ni reloj.
 *
 * <p>La política vive en {@link CorridaDeValores#laQueOrdenaLaCobranza} porque hasta #384 no vivía
 * en ningún sitio: estaba escondida en la cardinalidad de una consulta que suponía una sola
 * resolución del tipo. Aquí se fija lo que la prueba contra PostgreSQL no puede fijar sola: el
 * desempate de dos resoluciones <b>del mismo día</b>, que la política ordena por sí misma y no
 * hereda del {@code ORDER BY} de quien le pasa la lista.
 */
@DisplayName("#384 — La resolucion que ordena la cobranza")
class LaResolucionQueOrdenaLaCobranzaTest {

    private static final LocalDate RIS = LocalDate.of(2026, 3, 5);
    private static final LocalDate RESUELTA = LocalDate.of(2026, 3, 16);
    private static final Instant AHORA = Instant.parse("2026-03-20T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final CorridaDeValores ADMINISTRATIVA = corridaDe(Familia.ADMINISTRATIVA);
    private static final CorridaDeValores TRANSITO = corridaDe(Familia.TRANSITO);

    @Test
    @DisplayName("sin ninguna resolucion, ninguna ordena la cobranza")
    void sinNinguna() {
        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of())).isEmpty();
        assertThat(ADMINISTRATIVA.laQueDejoLaMultaSinEfecto(List.of())).isEmpty();
    }

    @Test
    @DisplayName("con una sola RIS, es ella")
    void conUnaSolaRis() {
        ResolucionDeGerencia ris = administrativa(7, RIS, null);

        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(ris))).contains(ris);
    }

    @Test
    @DisplayName("dos del mismo dia: la registrada despues, aunque la lista llegue al reves")
    void aIgualFechaDesempataElIdentificador() {
        ResolucionDeGerencia primera = administrativa(7, RIS, null);
        ResolucionDeGerencia segunda = administrativa(8, RIS, null);

        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(segunda, primera)))
                .as("el orden lo pone la politica, no el ORDER BY del repositorio")
                .contains(segunda);
        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(primera, segunda)))
                .contains(segunda);
    }

    @Test
    @DisplayName("la fecha manda sobre el identificador")
    void laFechaMandaSobreElIdentificador() {
        // Registrada antes (identificador menor) pero dictada despues: una resolucion dispuesta
        // por otra entra con la fecha que le corresponde, no con la del dia en que se registra.
        ResolucionDeGerencia posterior =
                administrativa(3, RESUELTA, EfectoSobreLaMulta.SE_MANTIENE);
        ResolucionDeGerencia ris = administrativa(9, RIS, null);

        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(posterior, ris)))
                .contains(posterior);
    }

    @Test
    @DisplayName("la RIS y su reconsideracion mantenida: la ordena la que la resuelve")
    void laReconsideracionMantenida() {
        ResolucionDeGerencia ris = administrativa(7, RIS, null);
        ResolucionDeGerencia mantenida =
                administrativa(8, RESUELTA, EfectoSobreLaMulta.SE_MANTIENE);

        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(ris, mantenida)))
                .contains(mantenida);
        assertThat(ADMINISTRATIVA.laQueDejoLaMultaSinEfecto(List.of(ris, mantenida))).isEmpty();
    }

    @Test
    @DisplayName("si la ultima que fallo la deja sin efecto, ninguna ordena, y se sabe cual fue")
    void laUltimaQueFalloLaDejaSinEfecto() {
        ResolucionDeGerencia ris = administrativa(7, RIS, null);
        ResolucionDeGerencia fundada =
                administrativa(8, RESUELTA, EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);

        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(ris, fundada))).isEmpty();
        assertThat(ADMINISTRATIVA.laQueDejoLaMultaSinEfecto(List.of(ris, fundada)))
                .contains(fundada);
    }

    @Test
    @DisplayName("una que la dejo sin efecto seguida de otra que la mantiene: decide la ultima")
    void decideLaUltimaQueFallo() {
        ResolucionDeGerencia fundada =
                administrativa(7, RIS, EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);
        ResolucionDeGerencia mantenida =
                administrativa(8, RESUELTA, EfectoSobreLaMulta.SE_MANTIENE);

        assertThat(ADMINISTRATIVA.laQueDejoLaMultaSinEfecto(List.of(fundada, mantenida))).isEmpty();
        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(fundada, mantenida)))
                .contains(mantenida);
    }

    @Test
    @DisplayName("en transito la ordena la ordinaria, y la sancionadora posterior no cuenta")
    void enTransitoLaOrdinaria() {
        ResolucionDeGerencia ordinaria = deTipo(TipoDeResolucionDeGerencia.ORDINARIA, 7, RIS);
        ResolucionDeGerencia sancionadora =
                deTipo(TipoDeResolucionDeGerencia.SANCIONADORA, 8, RESUELTA);

        assertThat(TRANSITO.laQueOrdenaLaCobranza(List.of(ordinaria, sancionadora)))
                .contains(ordinaria);
        assertThat(ADMINISTRATIVA.laQueOrdenaLaCobranza(List.of(ordinaria, sancionadora)))
                .as("ninguna de las dos es del procedimiento administrativo")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static CorridaDeValores corridaDe(Familia familia) {
        return CorridaDeValores.nueva(
                familia,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 15),
                OrigenDeLaCorrida.SELECCION,
                1,
                AHORA,
                PORQUE);
    }

    /** Una administrativa ya guardada; con efecto, resuelve un recurso. */
    private static ResolucionDeGerencia administrativa(
            long id, LocalDate fecha, @Nullable EfectoSobreLaMulta efecto) {
        SentidoDelFallo sentido =
                efecto == null
                        ? null
                        : efecto.extingueLaDeuda()
                                ? SentidoDelFallo.FUNDADO
                                : SentidoDelFallo.INFUNDADO;
        return new ResolucionDeGerencia(
                id,
                1L,
                TipoDeResolucionDeGerencia.ADMINISTRATIVA,
                "RGA-2026-" + id,
                100L + id,
                fecha,
                efecto == null ? null : 200L + id,
                sentido,
                efecto,
                null,
                null,
                null,
                "Sustento de la prueba",
                AHORA,
                "gerente",
                PORQUE);
    }

    /** Una de tránsito ya guardada, sin recurso. */
    private static ResolucionDeGerencia deTipo(
            TipoDeResolucionDeGerencia tipo, long id, LocalDate fecha) {
        boolean sancionadora = tipo.exigeOrdinariaVencida();
        return new ResolucionDeGerencia(
                id,
                1L,
                tipo,
                tipo.tipoDeDocumento() + "-2026-" + id,
                100L + id,
                fecha,
                null,
                null,
                null,
                sancionadora ? 300L : null,
                sancionadora ? fecha : null,
                null,
                "Sustento de la prueba",
                AHORA,
                "gerente",
                PORQUE);
    }
}
