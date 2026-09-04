package kamayuk.rentas.licencias.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #54 — El certificado, su numeracion y lo que los padrones cuentan, <b>sin base ni reloj</b>.
 *
 * <p>Aqui viven las invariantes que no necesitan PostgreSQL: que la vigencia se pregunte a una
 * fecha, que la numeracion por tipo no produzca dos certificados con el mismo numero, y que los dos
 * resumenes rechacen las situaciones que un recuento mal hecho produce. Lo que si necesita el motor
 * —RLS, el {@code REVOKE}, la derivacion del estado en SQL y la carrera del correlativo— esta en
 * {@code CertificadosYPadronesJdbcTest}.
 */
@DisplayName("#54 — El certificado y los padrones, sin base")
class CertificadoYSuVigenciaTest {

    private static final LocalDate EMISION = LocalDate.of(2026, 3, 16);

    @Nested
    @DisplayName("La vigencia se pregunta a una fecha (reglas 6 y 9)")
    class LaVigencia {

        @Test
        @DisplayName("un certificado vigente hoy esta caducado el dia siguiente a su vencimiento")
        void vigenteYCaducado() {
            Certificado certificado = certificado(EMISION, EMISION.plusMonths(36));

            assertThat(certificado.vigenteA(EMISION)).isTrue();
            assertThat(certificado.vigenteA(EMISION.plusMonths(36))).isTrue();
            assertThat(certificado.vigenteA(EMISION.plusMonths(36).plusDays(1))).isFalse();
            assertThat(certificado.estadoA(EMISION.plusMonths(36).plusDays(1)))
                    .isEqualTo("CADUCADO");
        }

        @Test
        @DisplayName("antes de emitirse no estaba vigente: no existia")
        void antesDeEmitirse() {
            Certificado certificado = certificado(EMISION, EMISION.plusMonths(12));

            assertThat(certificado.vigenteA(EMISION.minusDays(1)))
                    .as("un certificado no vale el dia anterior a expedirse")
                    .isFalse();
        }

        @Test
        @DisplayName("uno que caducaria antes de emitirse no se puede construir")
        void naceVencido() {
            assertThatThrownBy(() -> certificado(EMISION, EMISION.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nace vencido");
        }
    }

    @Nested
    @DisplayName("Las llaves de los parametros sellados")
    class LasLlaves {

        @Test
        @DisplayName("cada tipo nombra su concepto del TUPA y su vigencia, y son dos llaves")
        void dosLlavesPorTipo() {
            assertThat(TipoDeCertificado.NUMERACION.claveDelDerecho())
                    .isEqualTo("DERECHO_CERTIFICADO_NUMERACION");
            assertThat(TipoDeCertificado.NUMERACION.claveDeLaVigencia())
                    .isEqualTo("VIGENCIA_CERTIFICADO_NUMERACION");

            for (TipoDeCertificado tipo : TipoDeCertificado.values()) {
                assertThat(tipo.claveDelDerecho())
                        .as("la llave del derecho y la de la vigencia no pueden coincidir")
                        .isNotEqualTo(tipo.claveDeLaVigencia());
            }
        }

        @Test
        @DisplayName("ninguna llave lleva una cifra dentro (regla 5)")
        void ningunaLlaveLlevaCifra() {
            for (TipoDeCertificado tipo : TipoDeCertificado.values()) {
                assertThat(tipo.claveDelDerecho()).doesNotMatch(".*[0-9].*");
                assertThat(tipo.claveDeLaVigencia())
                        .as("cuantos meses vale lo dice el conjunto sellado, no este enum")
                        .doesNotMatch(".*[0-9].*");
            }
        }
    }

    @Nested
    @DisplayName("La numeracion es POR TIPO")
    class LaNumeracion {

        @Test
        @DisplayName("dos tipos con el mismo correlativo componen numeros distintos")
        void dosTiposNoChocan() {
            PlantillaDeNumeroDeCertificado plantilla = PlantillaDeNumeroDeCertificado.POR_OMISION;
            Ejercicio ejercicio = new Ejercicio(2026);

            String numeracion = plantilla.componer(TipoDeCertificado.NUMERACION, ejercicio, 1);
            String zonificacion =
                    plantilla.componer(TipoDeCertificado.ZONIFICACION_VIAS, ejercicio, 1);

            assertThat(numeracion).isEqualTo("CN-2026-000001");
            assertThat(zonificacion)
                    .as("el correlativo es por tipo: sin {tipo} los dos serian el mismo numero")
                    .isNotEqualTo(numeracion);
        }

        @Test
        @DisplayName("una plantilla sin {tipo} no se puede construir")
        void sinTipoNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new PlantillaDeNumeroDeCertificado(
                                            "CERT-{ejercicio}-{correlativo:6}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{tipo}");
        }

        @Test
        @DisplayName("con otra plantilla el numero cambia y sigue cabiendo en la columna")
        void otraPlantilla() {
            PlantillaDeNumeroDeCertificado otra =
                    new PlantillaDeNumeroDeCertificado("{correlativo:4}-{ejercicio}-{tipo}");

            assertThat(otra.componer(TipoDeCertificado.JURISDICCION, new Ejercicio(2027), 42))
                    .isEqualTo("0042-2027-J");
        }

        @Test
        @DisplayName("un numero que no cabe en varchar(20) se rechaza al componerlo")
        void elNumeroQueNoCabe() {
            PlantillaDeNumeroDeCertificado larga =
                    new PlantillaDeNumeroDeCertificado(
                            "CERTIFICADO-MUNICIPAL-{tipo}-{ejercicio}-{correlativo:6}");

            assertThatThrownBy(
                            () ->
                                    larga.componer(
                                            TipoDeCertificado.NUMERACION, new Ejercicio(2026), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("20");
        }
    }

    @Nested
    @DisplayName("Lo que los resumenes rechazan")
    class LosResumenes {

        @Test
        @DisplayName("un reparto de estados que no suma el total se rechaza")
        void elRepartoQueNoSuma() {
            assertThatThrownBy(() -> new ResumenDelPadronDeLicencias(10, 4, 3, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("repartirse entre los tres estados");
        }

        @Test
        @DisplayName("mas vigentes que emitidas es un recuento sobre dos poblaciones distintas")
        void masVigentesQueEmitidas() {
            assertThatThrownBy(
                            () ->
                                    FilaDelResumenAnual.con(
                                            new Ejercicio(2026),
                                            3,
                                            0,
                                            0,
                                            4,
                                            Dinero.de("100.00"),
                                            LocalDate.of(2026, 12, 31)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mas vigentes que emitidas");
        }

        @Test
        @DisplayName("o hay cifra o hay motivo, y exactamente uno de los dos (#48)")
        void oCifraOMotivo() {
            assertThatThrownBy(
                            () ->
                                    new FilaDelResumenAnual(
                                            new Ejercicio(2026),
                                            1,
                                            0,
                                            0,
                                            1,
                                            Dinero.de("100.00"),
                                            "y ademas un motivo",
                                            LocalDate.of(2026, 12, 31)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactamente uno");

            assertThatThrownBy(
                            () ->
                                    new FilaDelResumenAnual(
                                            new Ejercicio(2026),
                                            1,
                                            0,
                                            0,
                                            1,
                                            null,
                                            null,
                                            LocalDate.of(2026, 12, 31)))
                    .as("una fila sin cifra y sin motivo deja al lector adivinando")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("la fila sin derecho lleva su motivo y ningun cero")
        void sinDerechoNoEsCero() {
            FilaDelResumenAnual fila =
                    FilaDelResumenAnual.sinDerecho(
                            new Ejercicio(2024),
                            5,
                            1,
                            2,
                            4,
                            "Falta el parametro TUPA:DERECHO_LICENCIA_FUNCIONAMIENTO",
                            LocalDate.of(2024, 12, 31));

            assertThat(fila.derechoDeTramite())
                    .as("un cero se leeria como un año en el que no se cobro nada (#48)")
                    .isNull();
            assertThat(fila.derechoNoDisponible()).contains("DERECHO_LICENCIA_FUNCIONAMIENTO");
        }
    }

    // ------------------------------------------------------------------

    private static Certificado certificado(LocalDate emision, LocalDate vigenciaHasta) {
        return new Certificado(
                null,
                "CZ-2026-000001",
                TipoDeCertificado.ZONIFICACION_VIAS,
                7L,
                9L,
                "200601010150010101000001",
                "AV. GRAU 100",
                "EXP-1",
                emision,
                vigenciaHasta,
                11L,
                Dinero.de("35.00"),
                emision,
                21L,
                "CERTIFICADO-2026-000001",
                new ParametrosUrbanisticos("RDM", "3 pisos", "30 %", "3 m", "1.5 (a+r)"),
                null,
                Instant.parse("2026-03-16T10:00:00Z"),
                "prueba",
                Observacion.de("Se emite para la prueba"));
    }
}
