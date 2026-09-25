package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #342 — que deuda origino un documento, sin base de datos (regla 6).
 *
 * <p>La siembra no es uniforme: en la misma unidad y el mismo ejercicio que el cargo de la RDF
 * estan las cuatro cuotas ordinarias, con otro documento. Con solo el cargo de la RDF, una
 * Specification que devolviera cualquier clave de la unidad pasaria igual.
 */
@DisplayName("#342 — LoOriginadoPor: la deuda que nacio de un documento")
class LoOriginadoPorTest {

    private static final String RDF = "RDF-2026-000004";
    private static final long VEHICULO = 7L;

    @Test
    @DisplayName("solo la clave de saldo con un cargo de la RDF, no las cuotas ordinarias")
    void soloLaClaveDeLaRdf() {
        List<Asiento> libro =
                List.of(
                        cargo("VEHICULAR", 1, "120.00", "ALTA-1"),
                        cargo("VEHICULAR", 2, "120.00", "ALTA-1"),
                        cargo("VEHICULAR", 3, "120.00", "ALTA-1"),
                        cargo("VEHICULAR", 4, "120.00", "ALTA-1"),
                        cargo("VEHICULAR", null, "100.00", RDF));

        Map<ClaveDeSaldo, LoOriginadoPor.Originada> originadas =
                LoOriginadoPor.clavesDe(libro, Set.of(RDF));

        assertThat(originadas.keySet())
                .as("la clave del cargo de oficio, sin periodo: la ordinaria va por cuotas")
                .extracting(ClaveDeSaldo::tributo, ClaveDeSaldo::periodo)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("VEHICULAR", 0));
        assertThat(originadas.values())
                .singleElement()
                .satisfies(
                        o ->
                                assertThat(o.asientos())
                                        .as(
                                                "solo el cargo de oficio: agrupar sin el periodo"
                                                        + " arrastra las cuatro cuotas de ALTA-1")
                                        .hasSize(1));
    }

    @Test
    @DisplayName("la clave cuenta entera: tambien el abono del recibo, que lleva otro documento")
    void laClaveCuentaEntera() {
        Asiento abono =
                Asiento.nuevo(
                        new Ejercicio(2026),
                        1,
                        "VEHICULAR",
                        Concepto.INSOLUTO,
                        TipoAsiento.ABONO,
                        Fase.ORDINARIA,
                        null,
                        null,
                        VEHICULO,
                        null,
                        Dinero.de("40.00"),
                        LocalDate.of(2026, 5, 1),
                        "RECIBO 001-0000342");

        LoOriginadoPor.Originada originada =
                LoOriginadoPor.clavesDe(
                                List.of(cargo("VEHICULAR", null, "100.00", RDF), abono),
                                Set.of(RDF))
                        .values()
                        .iterator()
                        .next();

        assertThat(originada.asientos()).hasSize(2).contains(abono);
        assertThat(originada.documentos()).containsExactly(RDF);
    }

    @Test
    @DisplayName("la multa de la misma RDF es otra clave, y entra: no se filtra por tributo")
    void laMultaEntra() {
        assertThat(
                        LoOriginadoPor.clavesDe(
                                        List.of(
                                                cargo("VEHICULAR", null, "100.00", RDF),
                                                cargo("MULTA_TRIBUTARIA", null, "30.00", RDF)),
                                        Set.of(RDF))
                                .keySet())
                .extracting(ClaveDeSaldo::tributo)
                .containsExactlyInAnyOrder("VEHICULAR", "MULTA_TRIBUTARIA");
    }

    @Test
    @DisplayName("un documento que nadie pregunto no origina nada, y el vacio no trae nada")
    void otroDocumentoNoCuenta() {
        List<Asiento> libro = List.of(cargo("VEHICULAR", null, "70.00", "RDF-2026-000009"));

        assertThat(LoOriginadoPor.clavesDe(libro, Set.of(RDF))).isEmpty();
        assertThat(LoOriginadoPor.clavesDe(libro, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("el documento se compara sin espacios y sin distinguir mayusculas")
    void seComparaNormalizado() {
        assertThat(
                        LoOriginadoPor.clavesDe(
                                List.of(cargo("VEHICULAR", null, "100.00", "rdf-2026-000004 ")),
                                Set.of(RDF)))
                .hasSize(1);
    }

    @Test
    @DisplayName("un cargo que no origina deuda —el del movimiento de fase— no marca la clave")
    void elMovimientoDeFaseNoOrigina() {
        Asiento fase =
                Asiento.nuevoConMotivo(
                        new Ejercicio(2026),
                        1,
                        "VEHICULAR",
                        Concepto.AJUSTE,
                        TipoAsiento.CARGO,
                        Fase.VALOR,
                        1,
                        null,
                        VEHICULO,
                        null,
                        Dinero.de("120.00"),
                        LocalDate.of(2026, 5, 1),
                        RDF,
                        "pase a valor");

        assertThat(LoOriginadoPor.clavesDe(List.of(fase), Set.of(RDF)))
                .as("la definicion de cargo de origen es la de CargosDeUnSoloOrigen: una sola")
                .isEmpty();
    }

    private static Asiento cargo(
            String tributo, @Nullable Integer periodo, String monto, String documento) {
        return Asiento.nuevo(
                new Ejercicio(2026),
                1,
                tributo,
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                periodo,
                null,
                VEHICULO,
                null,
                Dinero.de(monto),
                LocalDate.of(2026, 3, 1),
                documento);
    }
}
