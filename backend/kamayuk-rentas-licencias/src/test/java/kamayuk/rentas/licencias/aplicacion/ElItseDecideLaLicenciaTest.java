package kamayuk.rentas.licencias.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CertificadoItse;
import kamayuk.rentas.catastro.prueba.TerritorioEnMemoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dobles.CajaDeMentira;
import kamayuk.rentas.licencias.dobles.CatalogoEnMemoria;
import kamayuk.rentas.licencias.dobles.DerechosDeMentira;
import kamayuk.rentas.licencias.dobles.DocumentosEnMemoria;
import kamayuk.rentas.licencias.dobles.LicenciasEnMemoria;
import kamayuk.rentas.licencias.dobles.MovimientosDeLicenciaEnMemoria;
import kamayuk.rentas.licencias.dobles.PadronDeMentira;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.OrigenDeLaZona;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #416 — El ITSE decide la licencia, y no solo se consulta.
 *
 * <p>Hasta este issue {@code ComprobacionDelTerritorio.todoComprobadoYFavorable()} miraba la zona y
 * el riesgo y dejaba el ITSE fuera: con {@code /grd/itse} caido y las otras dos rutas contestando,
 * la licencia salia sin autorizacion expresa —justo lo que prohibe el AC-4 de #43—, y un giro de
 * riesgo ALTO sin ningun certificado vigente salia sin autorizacion y con {@code
 * comprobacion_territorio} nulo, sin rastro de que no habia ITSE.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>La zona y el riesgo estan <b>siempre</b> sembrados y favorables —zona CZ compatible con el
 * giro, nada no mitigable—, y lo unico que cambia de una prueba a otra es el ITSE y el nivel de
 * riesgo del giro principal. Con {@code TerritorioEnMemoria.caido()} no se podia medir: tumba las
 * tres consultas a la vez, y la zona caida ya exigia la autorizacion por su cuenta.
 *
 * <p>Y hay contraste: el mismo ITSE vacio con un giro BAJO <b>se emite</b>, y con un certificado
 * vigente el giro ALTO tambien. Sin eso, las pruebas rojas pasarian con «rechazar todo».
 *
 * <p>Se prueba el caso de uso entero —{@link EmitirLicenciaDeFuncionamiento}— con dobles en memoria
 * y sin base: lo que se mide es la decision, y la decision no toca la base.
 */
@DisplayName("#416 — el ITSE decide la licencia, no solo se consulta")
class ElItseDecideLaLicenciaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final long PREDIO = 1234L;
    private static final String DERECHO_LICENCIA = "LF-001";
    private static final String RECIBO = "001-0000123";
    private static final String GIRO = "56101";

    private final CatalogoEnMemoria catalogo = new CatalogoEnMemoria();

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("licencias.ventanilla", "PC-LICENCIAS-01", "10.1.1.20"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("(b) Solo el ITSE no contesta, y eso no es «hay ITSE»")
    class ElItseQueNoContesta {

        @Test
        @DisplayName("`/grd/itse` caido con zona y riesgo favorables: hace falta la autorizacion")
        void itseCaidoConUnGiroBajo() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().itseCaido(PREDIO);

            assertThatThrownBy(() -> emitir(territorio, RiesgoItse.BAJO, null))
                    .as(
                            "«no se pudo preguntar» NO es favorable (AC-4 de #43): la zona y el"
                                    + " riesgo contestaron, pero el ITSE no, y hasta #416 la"
                                    + " licencia salia sin que nadie lo asumiera por escrito")
                    .isInstanceOf(EmitirLicenciaDeFuncionamiento.TerritorioSinAutorizar.class)
                    .hasMessageContaining("No se pudo preguntar a `catastro`")
                    .hasMessageContaining("ITSE NO_SE_PUDO_PREGUNTAR");
        }

        @Test
        @DisplayName("y el ITSE que NO CONSTA tampoco autoriza, y el mensaje dice que no consta")
        void itseQueNoConsta() {
            // Zona y riesgo sembrados, el ITSE no: el fixture contesta NO_CONSTA solo en esa ruta.
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla();

            assertThatThrownBy(() -> emitir(territorio, RiesgoItse.BAJO, null))
                    .isInstanceOf(EmitirLicenciaDeFuncionamiento.TerritorioSinAutorizar.class)
                    .hasMessageContaining("no consta en el territorio")
                    .as(
                            "y no el de la zona sin clasificar, que mandaria a quien atiende a"
                                    + " revisar el catalogo CIIU cuando lo que falta es el predio"
                                    + " en el padron de `catastro` (AC-5)")
                    .hasMessageNotContaining("no declara en que zonas cabe");
        }

        @Test
        @DisplayName("con la autorizacion expresa se emite, y la licencia dice que no se pudo")
        void itseCaidoConAutorizacion() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().itseCaido(PREDIO);

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    emitir(
                            territorio,
                            RiesgoItse.BAJO,
                            Observacion.de("Se asume por escrito: catastro en despliegue"));

            assertThat(emitida.licencia().territorio().origen())
                    .isEqualTo(OrigenDeLaZona.TERRITORIO);
            assertThat(emitida.licencia().territorio().comprobacion())
                    .contains("ITSE: no se pudo preguntar");
        }
    }

    @Nested
    @DisplayName("(a) El ITSE contesta vacio, y el giro decide si eso se opone")
    class ElItseVacio {

        @Test
        @DisplayName("giro de riesgo ALTO sin ITSE vigente: hace falta la autorizacion")
        void giroAltoSinItse() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            assertThatThrownBy(() -> emitir(territorio, RiesgoItse.ALTO, null))
                    .as(
                            "el TUPA lista la licencia de riesgo ALTO «Con ITSE previa», y hasta"
                                    + " #416 salia sin autorizacion y sin rastro")
                    .isInstanceOf(EmitirLicenciaDeFuncionamiento.TerritorioSinAutorizar.class)
                    .hasMessageContaining("ITSE previa");
        }

        @Test
        @DisplayName("y MUY ALTO igual")
        void giroMuyAltoSinItse() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            assertThatThrownBy(() -> emitir(territorio, RiesgoItse.MUY_ALTO, null))
                    .isInstanceOf(EmitirLicenciaDeFuncionamiento.TerritorioSinAutorizar.class)
                    .hasMessageContaining("ITSE previa");
        }

        @Test
        @DisplayName("y con la autorizacion se emite, dejando dicho que no habia ITSE vigente")
        void giroAltoSinItseConAutorizacion() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    emitir(
                            territorio,
                            RiesgoItse.ALTO,
                            Observacion.de("Se asume por escrito: la ITSE esta en tramite"));

            assertThat(emitida.licencia().territorio().comprobacion())
                    .as("dentro de dos anos, algo en la licencia tiene que decir que no habia ITSE")
                    .contains("ITSE: 0 vigentes");
        }

        @Test
        @DisplayName("el CONTRASTE: el mismo ITSE vacio con un giro BAJO se emite, con rastro")
        void giroBajoSinItseSeEmite() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    emitir(territorio, RiesgoItse.BAJO, null);

            assertThat(emitida.licencia().territorio().comprobacion())
                    .as(
                            "la ITSE de un giro BAJO es posterior: no se opone, pero que no habia"
                                    + " ninguna vigente se anota igual")
                    .contains("ITSE: 0 vigentes");
        }

        @Test
        @DisplayName("y MEDIO tambien se emite: la ITSE previa es de ALTO y MUY ALTO")
        void giroMedioSinItseSeEmite() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            assertThat(emitir(territorio, RiesgoItse.MEDIO, null).licencia().numero()).isNotNull();
        }

        @Test
        @DisplayName("y el giro ALTO CON un certificado vigente se emite sin pedir nada")
        void giroAltoConItseSeEmite() {
            TerritorioEnMemoria territorio =
                    zonaYRiesgoEnRegla().conItse(PREDIO, certificadoVigente());

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    emitir(territorio, RiesgoItse.ALTO, null);

            assertThat(emitida.licencia().territorio().comprobacion())
                    .as("todo comprobado y favorable: no hay nada que anotar")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("El giro sin clasificar no decide, y queda dicho")
    class ElGiroSinClasificar {

        @Test
        @DisplayName("riesgo ITSE nulo y ningun certificado: se emite, y el motivo lo anota")
        void sinClasificarSeEmiteConRastro() {
            TerritorioEnMemoria territorio = zonaYRiesgoEnRegla().conItse(PREDIO);

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida = emitir(territorio, null, null);

            assertThat(emitida.licencia().territorio().comprobacion())
                    .contains("ITSE: 0 vigentes")
                    .contains("no declara su nivel de riesgo ITSE");
        }
    }

    // ------------------------------------------------------------------

    /**
     * Zona CZ con su ordenanza y el riesgo contestado sin nada no mitigable; el ITSE, sin tocar.
     */
    private static TerritorioEnMemoria zonaYRiesgoEnRegla() {
        return new TerritorioEnMemoria()
                .conZona(PREDIO, "CZ", "ORD-2024-01")
                .conRiesgo(PREDIO, false);
    }

    private static CertificadoItse certificadoVigente() {
        return new CertificadoItse(
                1L,
                "ITSE-2026-0001",
                "ALTO",
                "PREVIA",
                LocalDate.of(2026, 1, 10),
                LocalDate.of(2028, 1, 9),
                null);
    }

    private EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitir(
            TerritorioEnMemoria territorio,
            @Nullable RiesgoItse riesgoDelGiro,
            @Nullable Observacion autorizacion) {
        catalogo.con(
                new Ciiu(
                        null,
                        GIRO,
                        "RESTAURANTES",
                        "H",
                        riesgoDelGiro,
                        "CZ, RDM",
                        false,
                        false,
                        true,
                        Instant.parse("2026-01-02T10:00:00Z"),
                        null,
                        Observacion.de("Siembra de la prueba")));

        EmitirLicenciaDeFuncionamiento emision =
                new EmitirLicenciaDeFuncionamiento(
                        new LicenciasEnMemoria(),
                        new MovimientosDeLicenciaEnMemoria(),
                        catalogo,
                        new CajaDeMentira()
                                .con(
                                        new ReciboDeTramite(
                                                11L,
                                                RECIBO,
                                                HOY,
                                                7L,
                                                true,
                                                false,
                                                List.of(DERECHO_LICENCIA),
                                                Dinero.de("50.00"),
                                                HOY)),
                        new PadronDeMentira()
                                .con(
                                        new ResumenDeContribuyente(
                                                7L, "C-0007", "PENA GARCIA, LUIS", "DNI 1234")),
                        (predioId, fecha) -> java.util.Optional.empty(),
                        new ComprobarElTerritorio(territorio, territorio),
                        new DerechosDeTramiteParametrizados(
                                new DerechosDeMentira(DERECHO_LICENCIA, "LF-009")),
                        new EmitirDocumento(
                                new DocumentosEnMemoria(),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                (RegistroDeAuditoria registro) -> {},
                                RELOJ),
                        PlantillaDeNumeroDeLicencia.POR_OMISION,
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ);

        return emision.emitir(
                new EmitirLicenciaDeFuncionamiento.Solicitud(
                        "C-0007",
                        PREDIO,
                        "RESTAURANTE EL CEIBO",
                        "AV. GRAU 100 - SULLANA",
                        new AreaM2(new BigDecimal("80.00")),
                        TipoDeLicencia.DEFINITIVA,
                        "CZ",
                        40,
                        HOY,
                        null,
                        RECIBO,
                        List.of(GIRO),
                        GIRO,
                        null,
                        null,
                        autorizacion),
                FormatoDeDocumento.PDF,
                Observacion.de("Emision de la prueba del ITSE"));
    }
}
