package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.rentas.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.rentas.dominio.Transferencia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * La siembra de la municipalidad de demostracion: padron vehicular, transferencias y saldo inicial
 * del libro, <b>sin base de datos</b>.
 *
 * <p>Lo que PostgreSQL garantiza ya tiene sus pruebas contra el motor de verdad. Lo que se verifica
 * aqui es lo que los tres importadores agregan, y son tres propiedades distintas:
 *
 * <ol>
 *   <li><b>Los codigos del archivo se resuelven a identificadores</b>, y una fila que nombre algo
 *       que no existe se rechaza en vez de escribir con un identificador inventado.
 *   <li><b>Una fila mala no arrastra a la que la sigue.</b> Es la misma propiedad que {@code
 *       ImportarVias} demuestra para el catalogo vial, y depende de que ningun importador lleve
 *       {@code @Transactional} sobre el bucle.
 *   <li><b>La unidad de una obligacion se resuelve a la fecha valor</b>, no a la de hoy (regla 9).
 *       Es la que menos se ve y la que mas cuesta: con la titularidad resuelta «a la ultima», una
 *       deuda de enero se le asienta a quien compro el predio en junio.
 * </ol>
 */
@DisplayName("Siembra de la municipalidad de demostracion")
class SiembraDeDemostracionTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Observacion PORQUE = Observacion.de("Siembra de la demostracion");

    private PadronDeLaSiembraEnMemoria padron;
    private ReferenciasDeLaSiembra referencias;
    private List<RegistroDeAuditoria> asientos;

    @BeforeEach
    void preparar() {
        padron = new PadronDeLaSiembraEnMemoria();
        referencias = new ReferenciasDeLaSiembra(padron, padron, padron);
        asientos = new ArrayList<>();
    }

    private Auditoria auditoria() {
        return asientos::add;
    }

    // ==================================================================

    @Nested
    @DisplayName("Padron vehicular")
    class Vehiculos {

        private static final String ENCABEZADO =
                "placa,codigoContribuyente,marca,modelo,categoria,anioFabricacion,"
                        + "anioInscripcion\n";

        private ImportarVehiculos importar;

        @BeforeEach
        void preparar() {
            importar =
                    new ImportarVehiculos(
                            new RegistrarVehiculo(padron, auditoria(), RELOJ), referencias);
        }

        @Test
        @DisplayName("una fila entra con su propietario resuelto por codigo")
        void unaFilaEntraConSuPropietarioResueltoPorCodigo() {
            long marina = padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "ZLG-701,C-000001,VOLVO,FH 440,CAMION,2019,2019\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(informe.rechazadas()).isEmpty();
            assertThat(padron.padronVehicular())
                    .singleElement()
                    .satisfies(
                            vehiculo -> {
                                assertThat(vehiculo.placa()).isEqualTo(Placa.de("ZLG-701"));
                                assertThat(vehiculo.contribuyenteId()).isEqualTo(marina);
                            });
        }

        @Test
        @DisplayName("la fila de un contribuyente que no existe se rechaza nombrando el codigo")
        void laFilaDeUnContribuyenteQueNoExisteSeRechazaNombrandoElCodigo() {
            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "ZLG-701,C-999999,VOLVO,FH 440,CAMION,2019,2019\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isZero();
            assertThat(informe.rechazadas())
                    .singleElement()
                    .satisfies(rechazada -> assertThat(rechazada.motivo()).contains("C-999999"));
            assertThat(padron.padronVehicular()).isEmpty();
        }

        @Test
        @DisplayName("una placa repetida se rechaza sola y la fila siguiente entra")
        void unaPlacaRepetidaSeRechazaSolaYLaFilaSiguienteEntra() {
            padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "ZLG-701,C-000001,VOLVO,FH 440,CAMION,2019,2019\n"
                                            + "ZLG-701,C-000001,HYUNDAI,HD 78,CAMION,2022,2023\n"
                                            + "ZLG-702,C-000001,TOYOTA,HIACE,MINIBUS,2024,2025\n"),
                            PORQUE);

            assertThat(informe.totalFilas()).isEqualTo(3);
            assertThat(informe.nuevas()).isEqualTo(2);
            assertThat(informe.rechazadas())
                    .singleElement()
                    .satisfies(rechazada -> assertThat(rechazada.fila()).isEqualTo(3));
            assertThat(padron.padronVehicular()).hasSize(2);
        }

        @Test
        @DisplayName("sin anio de inscripcion se toma el de fabricacion")
        void sinAnioDeInscripcionSeTomaElDeFabricacion() {
            padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

            importar.importar(
                    new StringReader(ENCABEZADO + "ZPB-330,C-000001,BAJAJ,RE 4S,MOTOTAXI,2024,\n"),
                    PORQUE);

            assertThat(padron.padronVehicular())
                    .singleElement()
                    .satisfies(
                            vehiculo ->
                                    assertThat(vehiculo.anioInscripcion())
                                            .isEqualTo(vehiculo.anioFabricacion()));
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Transferencias")
    class Transferencias {

        private static final String ENCABEZADO =
                "objeto,codigoPredial,placa,codTransferente,codAdquiriente,tipoTransferencia,"
                        + "fechaTransferencia,valorTransferencia,porcentajeTransferido,"
                        + "afectaAlcabala,documentoOrigen\n";

        private static final String CODIGO_PREDIAL = "20010401004001000000000";

        private ImportarTransferencias importar;

        @BeforeEach
        void preparar() {
            importar =
                    new ImportarTransferencias(
                            new RegistrarTransferencia(
                                    padron.registroDeTransferencias(), padron, padron, auditoria()),
                            referencias);
        }

        @Test
        @DisplayName("una transferencia parcial deja dos cuotas vivas: la del 40 % y el remanente")
        void unaTransferenciaParcialDejaDosCuotasVivas() {
            long segundo = padron.sembrarContribuyente("C-000014", "DEMO Querevalu Eche Segundo");
            padron.sembrarContribuyente("C-000010", "DEMO Ojeda Rivas Carmen");
            long predio = padron.sembrarPredio(CODIGO_PREDIAL, segundo, LocalDate.of(2026, 1, 1));

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "PREDIO,"
                                            + CODIGO_PREDIAL
                                            + ",,C-000014,C-000010,COMPRA_VENTA,2026-03-18,"
                                            + "54000.00,40.00,true,ESC-DEMO-0002\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(padron.cuotasDe(predio))
                    .filteredOn(cuota -> cuota.hasta() == null)
                    .extracting(cuota -> cuota.porcentaje().toString())
                    .containsExactlyInAnyOrder(
                            Porcentaje.de("40.00").toString(), Porcentaje.de("60.00").toString());
        }

        @Test
        @DisplayName(
                "una cadena de dos ventas deja al titular que rige EN CADA FECHA, no al ultimo")
        void unaCadenaDeDosVentasDejaAlTitularQueRigeEnCadaFecha() {
            long zoila = padron.sembrarContribuyente("C-000013", "DEMO Nunura Vilela Zoila");
            long manuel = padron.sembrarContribuyente("C-000009", "DEMO Chero Silupu Manuel");
            long carmen = padron.sembrarContribuyente("C-000010", "DEMO Ojeda Rivas Carmen");
            String codigo = "20010401003001000000000";
            padron.sembrarPredio(codigo, zoila, LocalDate.of(2026, 1, 1));

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "PREDIO,"
                                            + codigo
                                            + ",,C-000013,C-000009,COMPRA_VENTA,2026-02-10,"
                                            + "96000.00,100.00,true,ESC-DEMO-0001\n"
                                            + "PREDIO,"
                                            + codigo
                                            + ",,C-000009,C-000010,COMPRA_VENTA,2026-06-05,"
                                            + "101000.00,100.00,true,ESC-DEMO-0004\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(2);
            assertThat(referencias.predioDe(zoila, codigo, LocalDate.of(2026, 1, 15))).isPresent();
            assertThat(referencias.predioDe(manuel, codigo, LocalDate.of(2026, 3, 1))).isPresent();
            assertThat(referencias.predioDe(manuel, codigo, LocalDate.of(2026, 7, 1))).isEmpty();
            assertThat(referencias.predioDe(carmen, codigo, LocalDate.of(2026, 7, 1))).isPresent();
        }

        @Test
        @DisplayName("un predio que a esa fecha no es del transferente se rechaza")
        void unPredioQueAEsaFechaNoEsDelTransferenteSeRechaza() {
            long segundo = padron.sembrarContribuyente("C-000014", "DEMO Querevalu Eche Segundo");
            padron.sembrarContribuyente("C-000010", "DEMO Ojeda Rivas Carmen");
            padron.sembrarPredio(CODIGO_PREDIAL, segundo, LocalDate.of(2026, 1, 1));

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "PREDIO,"
                                            + CODIGO_PREDIAL
                                            + ",,C-000010,C-000014,COMPRA_VENTA,2026-03-18,"
                                            + "54000.00,40.00,true,ESC-DEMO-0002\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isZero();
            assertThat(informe.rechazadas())
                    .singleElement()
                    .satisfies(
                            rechazada ->
                                    assertThat(rechazada.motivo())
                                            .contains("no es titular del predio"));
            assertThat(padron.transferenciasRegistradas()).isEmpty();
        }

        @Test
        @DisplayName("una fila VEHICULO se acepta con codTransferente VACIO, y a proposito")
        void unaFilaVehiculoSeAceptaConCodTransferenteVacio() {
            long marina = padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");
            long manuel = padron.sembrarContribuyente("C-000009", "DEMO Chero Silupu Manuel");
            padron.sembrarVehiculo(Placa.de("ZTR-101"), marina);

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "VEHICULO,,ZTR-101,,C-000009,COMPRA_VENTA,2026-03-30,"
                                            + "14500.00,100.00,true,CT-DEMO-0006\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(padron.transferenciasRegistradas())
                    .singleElement()
                    .satisfies(
                            transferencia -> {
                                assertThat(transferencia.objeto())
                                        .isEqualTo(ObjetoDeTransferencia.VEHICULO);
                                assertThat(transferencia.transferenteId()).isEqualTo(marina);
                                assertThat(transferencia.adquirienteId()).isEqualTo(manuel);
                            });
            assertThat(padron.padronVehicular())
                    .singleElement()
                    .satisfies(v -> assertThat(v.contribuyenteId()).isEqualTo(manuel));
        }

        @Test
        @DisplayName("una fila mala no arrastra a la que la sigue")
        void unaFilaMalaNoArrastraALaQueLaSigue() {
            long segundo = padron.sembrarContribuyente("C-000014", "DEMO Querevalu Eche Segundo");
            padron.sembrarContribuyente("C-000010", "DEMO Ojeda Rivas Carmen");
            padron.sembrarPredio(CODIGO_PREDIAL, segundo, LocalDate.of(2026, 1, 1));

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "PREDIO,20010499999999999999999,,C-000014,C-000010,"
                                            + "COMPRA_VENTA,2026-03-18,1.00,10.00,true,X\n"
                                            + "PREDIO,"
                                            + CODIGO_PREDIAL
                                            + ",,C-000014,C-000010,COMPRA_VENTA,2026-03-18,"
                                            + "54000.00,40.00,true,ESC-DEMO-0002\n"),
                            PORQUE);

            assertThat(informe.rechazadas()).hasSize(1);
            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(padron.transferenciasRegistradas())
                    .extracting(Transferencia::documentoOrigen)
                    .containsExactly("ESC-DEMO-0002");
        }

        /**
         * El archivo tampoco puede inventarse el tipo del acto (#542).
         *
         * <p>Hasta #542 {@code tipoTransferencia} era texto libre: este mismo archivo escribia
         * {@code COMPRAVENTA} y entraba, de modo que la siembra dejaba en la tabla una palabra que
         * ninguna consulta por {@code COMPRA_VENTA} encuentra.
         */
        @Test
        @DisplayName("un tipo que no es del vocabulario se rechaza nombrando el valor")
        void unTipoQueNoEsDelVocabularioSeRechaza() {
            long segundo = padron.sembrarContribuyente("C-000014", "DEMO Querevalu Eche Segundo");
            padron.sembrarContribuyente("C-000010", "DEMO Ojeda Rivas Carmen");
            padron.sembrarPredio(CODIGO_PREDIAL, segundo, LocalDate.of(2026, 1, 1));

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "PREDIO,"
                                            + CODIGO_PREDIAL
                                            + ",,C-000014,C-000010,COMPRAVENTA,2026-03-18,"
                                            + "54000.00,40.00,true,ESC-DEMO-0002\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isZero();
            assertThat(informe.rechazadas()).hasSize(1);
            assertThat(informe.rechazadas().getFirst().motivo())
                    .contains("Tipo de transferencia desconocido: 'COMPRAVENTA'");
            assertThat(padron.transferenciasRegistradas()).isEmpty();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Saldo inicial del libro")
    class Deuda {

        private static final String ENCABEZADO =
                "codigoContribuyente,tributo,ejercicio,periodo,codigoPredial,placa,monto,"
                        + "fechaValor,documentoOrigen,referenciaExterna\n";

        private static final String CODIGO_PREDIAL = "20010401001001000000000";

        private ImportarDeudaDeDemostracion importar;

        @BeforeEach
        void preparar() {
            importar = new ImportarDeudaDeDemostracion(padron, referencias);
        }

        @Test
        @DisplayName("una obligacion anual entra sin periodo y sin unidad")
        void unaObligacionAnualEntraSinPeriodoYSinUnidad() {
            long marina = padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "C-000001,PREDIAL,2026,0,,,148.30,2026-02-28,"
                                            + "SALDO-INICIAL-DEMO,\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(padron.cargosAsentados())
                    .singleElement()
                    .satisfies(
                            cargo -> {
                                assertThat(cargo.contribuyenteId()).isEqualTo(marina);
                                assertThat(cargo.tributo()).isEqualTo("PREDIAL");
                                assertThat(cargo.periodo()).isNull();
                                assertThat(cargo.predioId()).isNull();
                                assertThat(cargo.vehiculoId()).isNull();
                                assertThat(cargo.monto()).isEqualTo(Dinero.de("148.30"));
                            });
        }

        @Test
        @DisplayName("la unidad se resuelve a la FECHA VALOR, no a la de hoy")
        void laUnidadSeResuelveALaFechaValorNoALaDeHoy() {
            long zoila = padron.sembrarContribuyente("C-000013", "DEMO Nunura Vilela Zoila");
            long manuel = padron.sembrarContribuyente("C-000009", "DEMO Chero Silupu Manuel");
            long predio = padron.sembrarPredio(CODIGO_PREDIAL, zoila, LocalDate.of(2026, 1, 1));
            new ImportarTransferencias(
                            new RegistrarTransferencia(
                                    padron.registroDeTransferencias(), padron, padron, auditoria()),
                            referencias)
                    .importar(
                            new StringReader(
                                    "objeto,codigoPredial,placa,codTransferente,codAdquiriente,"
                                            + "tipoTransferencia,fechaTransferencia,"
                                            + "valorTransferencia,porcentajeTransferido,"
                                            + "afectaAlcabala,documentoOrigen\n"
                                            + "PREDIO,"
                                            + CODIGO_PREDIAL
                                            + ",,C-000013,C-000009,COMPRA_VENTA,2026-06-05,"
                                            + "96000.00,100.00,true,ESC-DEMO-0001\n"),
                            PORQUE);

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            // enero: el predio todavia era de C-000013
                                            + "C-000013,ARBITRIO,2026,1,"
                                            + CODIGO_PREDIAL
                                            + ",,36.50,2026-01-31,SALDO-INICIAL-DEMO,\n"
                                            // y en enero NO era de C-000009
                                            + "C-000009,ARBITRIO,2026,2,"
                                            + CODIGO_PREDIAL
                                            + ",,36.50,2026-01-31,SALDO-INICIAL-DEMO,\n"),
                            PORQUE);

            assertThat(informe.nuevas()).isEqualTo(1);
            assertThat(informe.rechazadas())
                    .singleElement()
                    .satisfies(
                            rechazada ->
                                    assertThat(rechazada.motivo())
                                            .contains("no es titular del predio"));
            assertThat(padron.cargosAsentados())
                    .singleElement()
                    .satisfies(
                            cargo -> {
                                assertThat(cargo.contribuyenteId()).isEqualTo(zoila);
                                assertThat(cargo.predioId()).isEqualTo(predio);
                            });
            assertThat(manuel).isNotEqualTo(zoila);
        }

        @Test
        @DisplayName("la deuda de un vehiculo se asienta contra su identificador, no su placa")
        void laDeudaDeUnVehiculoSeAsientaContraSuIdentificador() {
            long transportes = padron.sembrarContribuyente("C-000007", "DEMO Transportes La Legua");
            long vehiculo = padron.sembrarVehiculo(Placa.de("ZLG-702"), transportes);

            importar.importar(
                    new StringReader(
                            ENCABEZADO
                                    + "C-000007,VEHICULAR,2026,0,,ZLG-702,382.00,2026-02-28,"
                                    + "SALDO-INICIAL-DEMO,\n"),
                    PORQUE);

            assertThat(padron.cargosAsentados())
                    .singleElement()
                    .satisfies(
                            cargo -> {
                                assertThat(cargo.vehiculoId()).isEqualTo(vehiculo);
                                assertThat(cargo.predioId()).isNull();
                            });
        }

        @Test
        @DisplayName("un periodo fuera de rango se rechaza y la fila siguiente entra")
        void unPeriodoFueraDeRangoSeRechazaYLaFilaSiguienteEntra() {
            padron.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

            InformeDeImportacion informe =
                    importar.importar(
                            new StringReader(
                                    ENCABEZADO
                                            + "C-000001,PREDIAL,2026,13,,,10.00,2026-02-28,X,\n"
                                            + "C-000001,PREDIAL,2026,4,,,20.00,2026-11-30,X,\n"),
                            PORQUE);

            assertThat(informe.rechazadas())
                    .singleElement()
                    .satisfies(
                            rechazada ->
                                    assertThat(rechazada.motivo()).contains("Periodo fuera de"));
            assertThat(padron.cargosAsentados())
                    .singleElement()
                    .satisfies(cargo -> assertThat(cargo.periodo()).isEqualTo(4));
        }
    }
}
