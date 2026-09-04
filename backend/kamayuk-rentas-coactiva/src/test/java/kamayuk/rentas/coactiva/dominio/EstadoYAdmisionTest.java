package kamayuk.rentas.coactiva.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #40 — El estado derivado, el criterio de admision y la deuda con su fecha, sin base y sin reloj.
 *
 * <p>Las tres son funciones puras (regla 6): entran los datos, sale el resultado. Que se puedan
 * probar asi es lo que garantiza que la grilla, la pantalla de historial y el informe de
 * importacion respondan lo mismo, porque las tres llaman a la misma funcion.
 */
@DisplayName("#40 — Estado, admision y deuda del expediente")
class EstadoYAdmisionTest {

    private static final LocalDate DIA = LocalDate.of(2026, 6, 1);
    private static final Instant AHORA = Instant.parse("2026-06-01T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    @Nested
    @DisplayName("El estado se deriva del historial, y de ningun otro sitio")
    class DelHistorial {

        @Test
        @DisplayName("sin movimientos, INICIADO: la carpeta dice que todavia no le paso nada")
        void sinMovimientos() {
            assertThat(EstadoDelExpediente.delHistorial(List.of()))
                    .isEqualTo(EstadoDelExpediente.INICIADO);
        }

        @Test
        @DisplayName("es el ultimo movimiento que llevo estado, no el ultimo movimiento")
        void esElUltimoConEstado() {
            List<MovimientoDelExpediente> historial =
                    List.of(
                            apertura(),
                            estado(EstadoDelExpediente.REC1_EMITIDA),
                            estado(EstadoDelExpediente.MEDIDA_CAUTELAR),
                            // Un cambio de direccion NO mueve el procedimiento: cambiar donde se
                            // notifica no cambia en que punto esta.
                            direccion("JR. NUEVO 100"));

            assertThat(EstadoDelExpediente.delHistorial(historial))
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
        }

        @Test
        @DisplayName("un estado anterior no resucita: el orden del historial manda")
        void elOrdenManda() {
            List<MovimientoDelExpediente> historial =
                    List.of(
                            apertura(),
                            estado(EstadoDelExpediente.SUSPENDIDO),
                            estado(EstadoDelExpediente.REC2_EMITIDA));

            assertThat(EstadoDelExpediente.delHistorial(historial))
                    .isEqualTo(EstadoDelExpediente.REC2_EMITIDA);
        }
    }

    @Nested
    @DisplayName("El vocabulario del prototipo, del manual y del dominio son el mismo estado")
    class Vocabularios {

        @Test
        @DisplayName("el codigo del manual, la etiqueta de la pantalla y el nombre coinciden")
        void tresFormasUnEstado() {
            assertThat(EstadoDelExpediente.porNombre("031"))
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(EstadoDelExpediente.porNombre("031 — MEDIDA CAUTELAR"))
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(EstadoDelExpediente.porNombre("MEDIDA_CAUTELAR"))
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(EstadoDelExpediente.porNombre("CON MEDIDA CAUTELAR"))
                    .as("asi lo llama el filtro «Estado» de coactiva_expedientes")
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
        }

        @Test
        @DisplayName("los seis codigos del desplegable del manual se resuelven")
        void losSeisCodigos() {
            assertThat(EstadoDelExpediente.porNombre("011"))
                    .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
            assertThat(EstadoDelExpediente.porNombre("012"))
                    .isEqualTo(EstadoDelExpediente.REC1_NOTIFICADA);
            assertThat(EstadoDelExpediente.porNombre("021"))
                    .isEqualTo(EstadoDelExpediente.REC2_EMITIDA);
            assertThat(EstadoDelExpediente.porNombre("041"))
                    .isEqualTo(EstadoDelExpediente.SUSPENDIDO);
            assertThat(EstadoDelExpediente.porNombre("051"))
                    .isEqualTo(EstadoDelExpediente.CONCLUIDO);
        }

        @Test
        @DisplayName("lo que no es ninguno se rechaza, en vez de caer en uno parecido")
        void loDesconocidoSeRechaza() {
            assertThatThrownBy(() -> EstadoDelExpediente.porNombre("ARCHIVADO"))
                    .as("el vocabulario de V3 se retiro con la columna que nadie escribio")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("011");
        }
    }

    @Nested
    @DisplayName("Que valores admite el expediente, y por que rechaza los demas")
    class Admision {

        @Test
        @DisplayName("con pase y sin expediente previo, entra")
        void conPaseEntra() {
            assertThat(AdmisionEnCoactiva.rechazo("COACTIVA", true, false)).isEmpty();
            assertThat(AdmisionEnCoactiva.admite("COACTIVA", true, false)).isTrue();
        }

        @Test
        @DisplayName("sin notificar: el expediente seria nulo (Ley 26979, art. 14)")
        void sinNotificar() {
            assertThat(AdmisionEnCoactiva.rechazo("EMITIDO", false, false))
                    .contains(MotivoDeRechazo.SIN_NOTIFICAR);
        }

        @Test
        @DisplayName("notificado pero con el plazo corriendo: todavia se puede reclamar")
        void plazoVigente() {
            assertThat(AdmisionEnCoactiva.rechazo("NOTIFICADO", false, false))
                    .contains(MotivoDeRechazo.PLAZO_VIGENTE);
        }

        @Test
        @DisplayName("exigible pero sin pase: la importacion empieza donde el pase termina")
        void exigibleSinPase() {
            assertThat(AdmisionEnCoactiva.rechazo("EXIGIBLE", false, false))
                    .as(
                            "es el caso que separa #39 de #40: el plazo vencio, pero nadie ha"
                                    + " dispuesto todavia el pase a coactiva")
                    .contains(MotivoDeRechazo.SIN_PASE_A_COACTIVA);
        }

        @Test
        @DisplayName("estado COACTIVA sin el movimiento: no hay de donde sacar el sustento")
        void coactivaSinMovimiento() {
            assertThat(AdmisionEnCoactiva.rechazo("COACTIVA", false, false))
                    .contains(MotivoDeRechazo.SIN_PASE_A_COACTIVA);
        }

        @Test
        @DisplayName("pagado, anulado o prescrito: no hay deuda que cobrar")
        void noCobrable() {
            assertThat(AdmisionEnCoactiva.rechazo("PAGADO", true, false))
                    .contains(MotivoDeRechazo.NO_COBRABLE);
            assertThat(AdmisionEnCoactiva.rechazo("ANULADO", true, false))
                    .contains(MotivoDeRechazo.NO_COBRABLE);
            assertThat(AdmisionEnCoactiva.rechazo("PRESCRITO", true, false))
                    .contains(MotivoDeRechazo.NO_COBRABLE);
        }

        @Test
        @DisplayName("ya en un expediente: gana sobre cualquier otro motivo, y reintentar lo dice")
        void yaEnUnExpediente() {
            assertThat(AdmisionEnCoactiva.rechazo("COACTIVA", true, true))
                    .contains(MotivoDeRechazo.YA_EN_UN_EXPEDIENTE);
            assertThat(AdmisionEnCoactiva.rechazo("EMITIDO", false, true))
                    .as("el segundo intento se lee como «ya estaba», no como un error nuevo")
                    .contains(MotivoDeRechazo.YA_EN_UN_EXPEDIENTE);
        }

        @Test
        @DisplayName("todo motivo se puede leer: el informe va por valor, no «3 de 7»")
        void todoMotivoSeExplica() {
            for (MotivoDeRechazo motivo : MotivoDeRechazo.values()) {
                assertThat(motivo.descripcion()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("Ninguna cifra del expediente sin su fecha")
    class Deuda {

        @Test
        @DisplayName("el total es la deuda materia de cobranza mas las costas, nunca otra suma")
        void elTotalEsLaSuma() {
            DeudaDelExpediente deuda =
                    DeudaDelExpediente.ninguna(DIA)
                            .mas(
                                    Dinero.de("100.00"),
                                    Dinero.de("10.00"),
                                    Dinero.de("5.50"),
                                    Dinero.de("1.00"));

            assertThat(deuda.materiaDeCobranza()).isEqualTo(Dinero.de("116.50"));
            assertThat(deuda.total()).isEqualTo(Dinero.de("116.50"));
            assertThat(deuda.actualizadaA()).isEqualTo(DIA);
        }

        @Test
        @DisplayName("las costas van a cero y con nombre: son #42, y no se inventan (regla 5)")
        void lasCostasVanACero() {
            assertThat(DeudaDelExpediente.ninguna(DIA).costas())
                    .as(
                            "el sumando existe desde ahora para que la pantalla no cambie de forma"
                                    + " cuando #42 lo llene; el importe sale del arancel aprobado")
                    .isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("sumar dos obligaciones conserva la fecha: no hay medias fechas")
        void sumarConservaLaFecha() {
            DeudaDelExpediente deuda =
                    DeudaDelExpediente.ninguna(DIA)
                            .mas(Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO)
                            .mas(Dinero.de("50.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThat(deuda.insoluto()).isEqualTo(Dinero.de("150.00"));
            assertThat(deuda.actualizadaA()).isEqualTo(DIA);
        }
    }

    @Nested
    @DisplayName("Un movimiento incoherente no se construye")
    class MovimientosInvalidos {

        @Test
        @DisplayName("un cambio de estado no puede traer una direccion pegada")
        void estadoConDireccion() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDelExpediente(
                                            null,
                                            1,
                                            TipoDeMovimientoDelExpediente.ESTADO,
                                            EstadoDelExpediente.SUSPENDIDO,
                                            "JR. NUEVO 100",
                                            DIA,
                                            "motivo",
                                            null,
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("direccion referencial");
        }

        @Test
        @DisplayName("un cambio de direccion no mueve el estado del procedimiento")
        void direccionConEstado() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDelExpediente(
                                            null,
                                            1,
                                            TipoDeMovimientoDelExpediente.DIRECCION,
                                            EstadoDelExpediente.CONCLUIDO,
                                            "JR. NUEVO 100",
                                            DIA,
                                            "motivo",
                                            null,
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un expediente nace INICIADO: el estado de su apertura no se elige")
        void aperturaSoloIniciado() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDelExpediente(
                                            null,
                                            1,
                                            TipoDeMovimientoDelExpediente.APERTURA,
                                            EstadoDelExpediente.MEDIDA_CAUTELAR,
                                            null,
                                            DIA,
                                            "motivo",
                                            null,
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INICIADO");
        }

        @Test
        @DisplayName("sin motivo no hay acto: una fila sin causal no explica nada (RNF-052)")
        void sinMotivo() {
            assertThatThrownBy(
                            () ->
                                    MovimientoDelExpediente.cambioDeEstado(
                                            1,
                                            EstadoDelExpediente.SUSPENDIDO,
                                            DIA,
                                            "  ",
                                            null,
                                            null,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("motivo");
        }

        @Test
        @DisplayName("el documento de respaldo va entero o no va")
        void documentoAMedias() {
            assertThatThrownBy(
                            () ->
                                    MovimientoDelExpediente.cambioDeEstado(
                                            1,
                                            EstadoDelExpediente.SUSPENDIDO,
                                            DIA,
                                            "pago total",
                                            DIA,
                                            null,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entero");
        }
    }

    @Nested
    @DisplayName("Un informe de importacion no miente sobre su expediente")
    class Informe {

        @Test
        @DisplayName("sin nada importado no hay expediente: su numero seria un hueco")
        void sinNadaImportado() {
            InformeDeImportacion informe =
                    InformeDeImportacion.sinNadaQueImportar(
                            List.of(
                                    new ValorRechazado(
                                            "OP-2026-000001", MotivoDeRechazo.PLAZO_VIGENTE)));

            assertThat(informe.abrioExpediente()).isFalse();
            assertThat(informe.importados()).isEmpty();
            assertThat(informe.rechazados()).hasSize(1);
            assertThatThrownBy(informe::expedienteAbierto).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("un expediente sin valores no se puede construir")
        void expedienteVacio() {
            assertThatThrownBy(() -> new InformeDeImportacion(unExpediente(), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vacio");
        }

        @Test
        @DisplayName("valores importados sin expediente tampoco")
        void valoresSinExpediente() {
            assertThatThrownBy(
                            () ->
                                    new InformeDeImportacion(
                                            null,
                                            List.of(new ValorDelExpediente(1, DIA)),
                                            List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------

    private static ExpedienteCoactivo unExpediente() {
        return new ExpedienteCoactivo(
                1L,
                "EXP-2026-000001",
                new kamayuk.rentas.dominio.Ejercicio(2026),
                1,
                7,
                "R. MENDOZA CRUZ",
                null,
                DIA,
                null,
                null,
                AHORA,
                "prueba",
                PORQUE);
    }

    private static MovimientoDelExpediente apertura() {
        return MovimientoDelExpediente.apertura(1, DIA, "importacion", AHORA, PORQUE);
    }

    private static MovimientoDelExpediente estado(EstadoDelExpediente nuevo) {
        return MovimientoDelExpediente.cambioDeEstado(
                1, nuevo, DIA, "motivo de prueba", null, null, AHORA, PORQUE);
    }

    private static MovimientoDelExpediente direccion(String nueva) {
        return MovimientoDelExpediente.cambioDeDireccion(
                1, nueva, DIA, "no ubicado en el domicilio fiscal", AHORA, PORQUE);
    }
}
