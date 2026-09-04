package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** La liquidación, sus versiones y su estado derivado (#49). Sin base y sin reloj. */
@DisplayName("#49 — Liquidacion, versiones y estado")
class LiquidacionYSusVersionesTest {

    private static final Observacion OBSERVACION = Observacion.de("Se liquida para la prueba");
    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final long CONJUNTO_2024 = 41L;

    private static Liquidacion primera() {
        return Liquidacion.primera(
                "LIQ-2026-000001",
                E2026,
                1,
                7L,
                E2024,
                E2024,
                TipoDeFiscalizacion.CIERTA,
                "Ampliacion detectada en inspeccion",
                HOY,
                OBSERVACION);
    }

    @Nested
    @DisplayName("La cadena de versiones (AC 2)")
    class CadenaDeVersiones {

        @Test
        @DisplayName("la version 1 no sustituye a nadie, y cualquier otra tiene que decir a cual")
        void laCadenaNoSeRompe() {
            assertThat(primera().esReliquidacion()).isFalse();

            assertThatThrownBy(
                            () ->
                                    new Liquidacion(
                                            9L,
                                            "LIQ-2026-000002",
                                            E2026,
                                            2,
                                            7L,
                                            2,
                                            null,
                                            E2024,
                                            E2024,
                                            TipoDeFiscalizacion.CIERTA,
                                            "sin encadenar",
                                            HOY,
                                            null,
                                            null,
                                            OBSERVACION))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a cual sustituye");
        }

        @Test
        @DisplayName("reliquidar produce OTRA fila y no toca la anterior")
        void reliquidarNoPisa() {
            Liquidacion guardada = conIdentificador(primera(), 5L);
            Liquidacion segunda =
                    guardada.reliquidadaPor(
                            "LIQ-2026-000002",
                            E2026,
                            2,
                            E2024,
                            E2024,
                            TipoDeFiscalizacion.CIERTA,
                            "Area corregida tras la reinspeccion",
                            HOY,
                            OBSERVACION);

            assertThat(segunda.version()).isEqualTo(2);
            assertThat(segunda.liquidacionAnteriorId()).isEqualTo(5L);
            assertThat(segunda.esNueva()).as("la reliquidacion todavia no esta guardada").isTrue();
            assertThat(guardada.version()).as("la anterior no cambia").isEqualTo(1);
            assertThat(guardada.numero()).isEqualTo("LIQ-2026-000001");
        }

        @Test
        @DisplayName("solo se reliquida una liquidacion ya guardada")
        void soloSeReliquidaLoGuardado() {
            assertThatThrownBy(
                            () ->
                                    primera()
                                            .reliquidadaPor(
                                                    "LIQ-2026-000002",
                                                    E2026,
                                                    2,
                                                    E2024,
                                                    E2024,
                                                    TipoDeFiscalizacion.CIERTA,
                                                    "motivo",
                                                    HOY,
                                                    OBSERVACION))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Explica la diferencia (AC 2)")
    class ExplicaLaDiferencia {

        @Test
        @DisplayName("nombra el area que cambio, con los dos lados")
        void nombraElAreaQueCambio() {
            Liquidacion anterior = conIdentificador(primera(), 5L);
            Liquidacion nueva =
                    conIdentificador(
                            anterior.reliquidadaPor(
                                    "LIQ-2026-000002",
                                    E2026,
                                    2,
                                    E2024,
                                    E2024,
                                    TipoDeFiscalizacion.CIERTA,
                                    "Area corregida",
                                    HOY,
                                    OBSERVACION),
                            6L);

            DiferenciaEntreLiquidaciones diferencia =
                    DiferenciaEntreLiquidaciones.entre(
                            anterior,
                            List.of(linea(AreaM2.de("300.00"), CondicionFiscalizada.SUBVALUADOR)),
                            nueva,
                            List.of(linea(AreaM2.de("180.00"), CondicionFiscalizada.SUBVALUADOR)));

            assertThat(diferencia.sinCambios()).isFalse();
            assertThat(diferencia.cambios())
                    .anySatisfy(
                            cambio -> {
                                assertThat(cambio.concepto()).contains("area hallada");
                                assertThat(cambio.antes()).isEqualTo("300.00 m2");
                                assertThat(cambio.despues()).isEqualTo("180.00 m2");
                            });
            assertThat(diferencia.cambios())
                    .anySatisfy(cambio -> assertThat(cambio.concepto()).contains("Motivo"));
        }

        @Test
        @DisplayName("los importes que faltan se nombran, no se omiten")
        void losImportesQueFaltanSeNombran() {
            Liquidacion anterior = conIdentificador(primera(), 5L);
            Liquidacion nueva =
                    conIdentificador(
                            anterior.reliquidadaPor(
                                    "LIQ-2026-000002",
                                    E2026,
                                    2,
                                    E2024,
                                    E2024,
                                    TipoDeFiscalizacion.CIERTA,
                                    "Area corregida",
                                    HOY,
                                    OBSERVACION),
                            6L);

            DiferenciaEntreLiquidaciones diferencia =
                    DiferenciaEntreLiquidaciones.entre(
                            anterior,
                            List.of(linea(AreaM2.de("300.00"), CondicionFiscalizada.SUBVALUADOR)),
                            nueva,
                            List.of(linea(AreaM2.de("180.00"), CondicionFiscalizada.SUBVALUADOR)));

            assertThat(diferencia.importesSinCifra())
                    .as("«no cambio nada» seria falso: lo que pasa es que no hay que comparar")
                    .isNotEmpty();
            assertThat(diferencia.cambios())
                    .noneSatisfy(
                            cambio -> assertThat(cambio.concepto()).contains("insoluto omitido"));
        }

        @Test
        @DisplayName("comparar dos que no se encadenan se rechaza")
        void compararDosQueNoSeEncadenan() {
            Liquidacion anterior = conIdentificador(primera(), 5L);
            Liquidacion ajena = conIdentificador(primera(), 99L);

            assertThatThrownBy(
                            () ->
                                    DiferenciaEntreLiquidaciones.entre(
                                            anterior, List.of(), ajena, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no reliquida");
        }
    }

    @Nested
    @DisplayName("El estado se deriva")
    class ElEstadoSeDeriva {

        @Test
        @DisplayName("sin movimientos, ABIERTA; con el ultimo, lo que ese ultimo diga")
        void elEstadoEsElDelUltimoMovimiento() {
            assertThat(EstadoDeLiquidacion.delHistorial(List.of()))
                    .isEqualTo(EstadoDeLiquidacion.ABIERTA);

            List<MovimientoDeLiquidacion> historial =
                    List.of(
                            MovimientoDeLiquidacion.apertura(1L, HOY, "emitida", OBSERVACION),
                            MovimientoDeLiquidacion.cambioDeEstado(
                                    1L, EstadoDeLiquidacion.LIQUIDADA, HOY, "cerrada", OBSERVACION),
                            MovimientoDeLiquidacion.cambioDeEstado(
                                    1L,
                                    EstadoDeLiquidacion.NOTIFICADA,
                                    HOY,
                                    "entregada",
                                    OBSERVACION));

            assertThat(EstadoDeLiquidacion.delHistorial(historial))
                    .isEqualTo(EstadoDeLiquidacion.NOTIFICADA);
        }

        @Test
        @DisplayName("la apertura solo abre en ABIERTA")
        void laAperturaSoloAbreEnAbierta() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeLiquidacion(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeLiquidacion.APERTURA,
                                            EstadoDeLiquidacion.NOTIFICADA,
                                            HOY,
                                            "motivo",
                                            null,
                                            OBSERVACION))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("«EN PROCESO» con espacio es el mismo estado que EN_PROCESO")
        void laEtiquetaDeLaPantallaSeAdmite() {
            assertThat(EstadoDeLiquidacion.porNombre("EN PROCESO"))
                    .isEqualTo(EstadoDeLiquidacion.EN_PROCESO);
        }
    }

    @Nested
    @DisplayName("Las cifras van con nombre y sin valor")
    class CifrasSinValor {

        @Test
        @DisplayName("una linea emitida hoy no lleva ningun importe")
        void unaLineaNoLlevaImportes() {
            LineaDeLiquidacion linea = linea(AreaM2.de("300.00"), CondicionFiscalizada.SUBVALUADOR);

            assertThat(linea.insolutoOmitido()).isNull();
            assertThat(linea.multaTributaria()).isNull();
            assertThat(linea.esperaSusCifras()).isTrue();
        }

        @Test
        @DisplayName("media comparacion se rechaza: la base declarada sin la hallada, o al reves")
        void mediaComparacionSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    new LineaDeLiquidacion(
                                            null,
                                            null,
                                            E2024,
                                            CONJUNTO_2024,
                                            20L,
                                            null,
                                            CondicionFiscalizada.SUBVALUADOR,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Dinero.de("1000.00"),
                                            null,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("declaro cero");
        }

        @Test
        @DisplayName("una linea sin conjunto sellado no se puede construir (AC 1)")
        void sinConjuntoSelladoNoHayLinea() {
            assertThatThrownBy(
                            () ->
                                    LineaDeLiquidacion.predialSinCifras(
                                            E2024,
                                            0L,
                                            20L,
                                            CondicionFiscalizada.CONFORME,
                                            null,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SELLADO");
        }
    }

    @Nested
    @DisplayName("La numeracion")
    class Numeracion {

        @Test
        @DisplayName("compone el numero con el ejercicio y el correlativo relleno")
        void componeElNumero() {
            assertThat(PlantillaDeNumeroDeLiquidacion.POR_OMISION.componer(E2026, 7))
                    .isEqualTo("LIQ-2026-000007");
        }

        @Test
        @DisplayName("una plantilla sin ejercicio se rechaza")
        void sinEjercicioNoHayPlantilla() {
            assertThatThrownBy(() -> new PlantillaDeNumeroDeLiquidacion("LIQ-{correlativo:6}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{ejercicio}");
        }
    }

    // ------------------------------------------------------------------

    private static LineaDeLiquidacion linea(AreaM2 hallada, CondicionFiscalizada condicion) {
        return LineaDeLiquidacion.predialSinCifras(
                E2024, CONJUNTO_2024, 20L, condicion, AreaM2.de("120.00"), hallada, null, null);
    }

    private static Liquidacion conIdentificador(Liquidacion liquidacion, long id) {
        return new Liquidacion(
                id,
                liquidacion.numero(),
                liquidacion.ejercicio(),
                liquidacion.correlativo(),
                liquidacion.actaId(),
                liquidacion.version(),
                liquidacion.liquidacionAnteriorId(),
                liquidacion.ejercicioDesde(),
                liquidacion.ejercicioHasta(),
                liquidacion.tipo(),
                liquidacion.motivoDeterminante(),
                liquidacion.fecha(),
                liquidacion.numeroNotificacion(),
                "pruebas",
                liquidacion.observacion());
    }
}
