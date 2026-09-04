package kamayuk.rentas.licencias.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #51 — El anuncio, su estado derivado y sus actos, <b>sin base de datos y sin reloj</b>.
 *
 * <p>Lo que se prueba aqui es lo que no necesita PostgreSQL para probarse, y por tanto tampoco
 * deberia necesitarlo para fallar:
 *
 * <ul>
 *   <li>Que <b>el estado se deriva</b> de los movimientos y de la fecha a la que se pregunta, y no
 *       de una columna. V45 le retira a {@code anuncio} el {@code estado varchar(15) DEFAULT
 *       'VIGENTE'} que V4 le habia puesto, y aqui esta la razon: «vencido» no es un hecho del
 *       anuncio, es una relacion entre su vigencia y un dia.
 *   <li>Que la <b>vigencia que rige</b> sale del ultimo acto que la movio, no de la fila. Una
 *       renovacion prorroga sin editar nada —no podria: V45 revoca el {@code UPDATE}—.
 *   <li>Que un movimiento <b>no puede explicar un cargo a medias</b>: el ejercicio, la referencia y
 *       el importe van los tres o ninguno.
 *   <li>Que la <b>referencia del cargo lleva el ejercicio dentro</b>. Es la cadena que {@code
 *       anuncio_movimiento_cargo_uq} declara unica, asi que decidir que entra en ella es decidir
 *       cuantas veces puede cobrarse la tasa.
 *   <li>Que la <b>numeracion es una plantilla</b> (D-09 abierta) y que el numero no protege de
 *       nada: quien hace idempotente el registro es la clave del cliente.
 * </ul>
 */
@DisplayName("#51 — El anuncio y sus actos, sin base ni reloj")
class AnuncioYSusActosTest {

    private static final LocalDate AUTORIZADO = LocalDate.of(2026, 3, 16);
    private static final Instant AHORA = Instant.parse("2026-03-16T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");
    private static final Dinero TASA = Dinero.de("90.00");
    private static final Ejercicio DOS_MIL_VEINTISEIS = new Ejercicio(2026);

    // ==================================================================

    @Nested
    @DisplayName("El estado se deriva, no se guarda")
    class ElEstado {

        @Test
        @DisplayName("autorizado y dentro de su plazo: VIGENTE")
        void vigente() {
            List<MovimientoDeAnuncio> historial = List.of(autorizacion(AUTORIZADO, fin(2026)));

            assertThat(
                            EstadoDelAnuncio.derivarDe(
                                    historial,
                                    EstadoDelAnuncio.vigenciaSegun(historial, AUTORIZADO),
                                    AUTORIZADO))
                    .isEqualTo(EstadoDelAnuncio.VIGENTE);
        }

        @Test
        @DisplayName("el mismo anuncio es VIGENTE ayer y VENCIDO manana: la fecha es argumento")
        void elMismoAnuncioCambiaConLaFecha() {
            List<MovimientoDeAnuncio> historial = List.of(autorizacion(AUTORIZADO, fin(2026)));
            LocalDate ultimoDia = fin(2026);

            assertThat(estadoA(historial, ultimoDia))
                    .as("el ultimo dia de vigencia todavia rige")
                    .isEqualTo(EstadoDelAnuncio.VIGENTE);
            assertThat(estadoA(historial, ultimoDia.plusDays(1)))
                    .as("y el siguiente ya no. Un padron con fecha de corte de ayer dice VIGENTE")
                    .isEqualTo(EstadoDelAnuncio.VENCIDO);
        }

        @Test
        @DisplayName("un movimiento posterior a la fecha preguntada no cuenta")
        void elFuturoNoCuenta() {
            LocalDate cese = LocalDate.of(2026, 6, 30);
            List<MovimientoDeAnuncio> historial =
                    List.of(autorizacion(AUTORIZADO, fin(2026)), cese(cese));

            assertThat(estadoA(historial, cese.minusDays(1)))
                    .as("reimprimir el padron de mayo no puede dar el estado de julio")
                    .isEqualTo(EstadoDelAnuncio.VIGENTE);
            assertThat(estadoA(historial, cese)).isEqualTo(EstadoDelAnuncio.CESADO);
        }

        @Test
        @DisplayName("el retiro gana sobre el cese, y el cese sobre el vencimiento")
        void laPrecedencia() {
            LocalDate cese = LocalDate.of(2026, 6, 30);
            LocalDate retiro = LocalDate.of(2026, 7, 15);

            List<MovimientoDeAnuncio> cesado =
                    List.of(autorizacion(AUTORIZADO, fin(2026)), cese(cese));
            assertThat(estadoA(cesado, fin(2027)))
                    .as("cesado y ademas fuera de plazo sigue diciendo CESADO: no se renueva")
                    .isEqualTo(EstadoDelAnuncio.CESADO);

            List<MovimientoDeAnuncio> retirado =
                    List.of(autorizacion(AUTORIZADO, fin(2026)), cese(cese), retiro(retiro));
            assertThat(estadoA(retirado, retiro))
                    .as(
                            "y retirado gana: el fiscalizador necesita saber que ya no esta en la calle")
                    .isEqualTo(EstadoDelAnuncio.RETIRADO);
        }

        @Test
        @DisplayName("sin plazo declarado no vence nunca")
        void sinPlazoNoVence() {
            List<MovimientoDeAnuncio> historial = List.of(autorizacion(AUTORIZADO, null));

            assertThat(estadoA(historial, AUTORIZADO.plusYears(10)))
                    .isEqualTo(EstadoDelAnuncio.VIGENTE);
        }

        @Test
        @DisplayName("solo VIGENTE y VENCIDO admiten renovacion: ahi esta el AC 3")
        void quienAdmiteRenovacion() {
            assertThat(EstadoDelAnuncio.VIGENTE.admiteRenovacion()).isTrue();
            assertThat(EstadoDelAnuncio.VENCIDO.admiteRenovacion()).isTrue();
            assertThat(EstadoDelAnuncio.CESADO.admiteRenovacion())
                    .as("un anuncio cesado no devenga otra tasa: es la mitad del AC 3")
                    .isFalse();
            assertThat(EstadoDelAnuncio.RETIRADO.admiteRenovacion()).isFalse();
        }

        @Test
        @DisplayName("VIGENTE y VENCIDO no comparten letra en la grilla")
        void lasLetrasSeDistinguen() {
            assertThat(
                            Set.of(
                                    EstadoDelAnuncio.VIGENTE.inicial(),
                                    EstadoDelAnuncio.VENCIDO.inicial(),
                                    EstadoDelAnuncio.CESADO.inicial(),
                                    EstadoDelAnuncio.RETIRADO.inicial()))
                    .as("los dos empiezan por V; deducir la letra del nombre los confundiria")
                    .hasSize(4);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Renovar es agregar")
    class LaVigencia {

        @Test
        @DisplayName("la vigencia que rige es la del ultimo acto que la movio")
        void laProrrogaMandaSobreLaFila() {
            List<MovimientoDeAnuncio> historial =
                    List.of(
                            autorizacion(AUTORIZADO, fin(2026)),
                            renovacion(LocalDate.of(2027, 1, 15), 2027, fin(2027)));

            assertThat(EstadoDelAnuncio.vigenciaSegun(historial, LocalDate.of(2027, 2, 1)))
                    .as("la fila de `anuncio` sigue diciendo 2026: no se puede editar (V45)")
                    .isEqualTo(fin(2027));
            assertThat(estadoA(historial, LocalDate.of(2027, 6, 1)))
                    .isEqualTo(EstadoDelAnuncio.VIGENTE);
        }

        @Test
        @DisplayName("la vigencia se pregunta a una fecha: la prorroga de manana no cuenta hoy")
        void laProrrogaFuturaNoCuenta() {
            List<MovimientoDeAnuncio> historial =
                    List.of(
                            autorizacion(AUTORIZADO, fin(2026)),
                            renovacion(LocalDate.of(2027, 1, 15), 2027, fin(2027)));

            assertThat(EstadoDelAnuncio.vigenciaSegun(historial, LocalDate.of(2026, 12, 1)))
                    .isEqualTo(fin(2026));
        }

        @Test
        @DisplayName("el cese no mueve la vigencia: la termina")
        void elCeseNoMueveLaVigencia() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeAnuncio(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeAnuncio.CESE,
                                            AUTORIZADO,
                                            null,
                                            null,
                                            null,
                                            fin(2027),
                                            "Cese solicitado",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("la terminan");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("El movimiento no explica un cargo a medias")
    class ElDevengo {

        @Test
        @DisplayName("una autorizacion sin su referencia no se puede construir")
        void mediaExplicacion() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeAnuncio(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeAnuncio.AUTORIZACION,
                                            AUTORIZADO,
                                            DOS_MIL_VEINTISEIS,
                                            null,
                                            TASA,
                                            fin(2026),
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("las tres o ninguna");
        }

        @Test
        @DisplayName("y un cese CON referencia, tampoco")
        void elCeseNoDevenga() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeAnuncio(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeAnuncio.CESE,
                                            AUTORIZADO,
                                            DOS_MIL_VEINTISEIS,
                                            "ANUNCIO-AN-2026-000001-2026",
                                            TASA,
                                            null,
                                            "Cese solicitado",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no devenga tasa");
        }

        @Test
        @DisplayName("una tasa de cero no es una tasa: la clase sin tarifa se deja sin parametro")
        void laTasaDeCero() {
            assertThatThrownBy(
                            () ->
                                    MovimientoDeAnuncio.autorizacion(
                                            1L,
                                            AUTORIZADO,
                                            DOS_MIL_VEINTISEIS,
                                            "ANUNCIO-AN-2026-000001-2026",
                                            Dinero.CERO,
                                            fin(2026),
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("distinto de tarifarla en cero");
        }

        @Test
        @DisplayName("el cese y el retiro se motivan; la autorizacion y la renovacion no")
        void elMotivo() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeAnuncio(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeAnuncio.CESE,
                                            AUTORIZADO,
                                            null,
                                            null,
                                            null,
                                            null,
                                            "   ",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("se motivan");
        }

        @Test
        @DisplayName("sin observacion no se guarda (regla 10)")
        void sinObservacion() {
            assertThatThrownBy(() -> Observacion.de("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("La referencia del cargo lleva el ejercicio dentro")
    class LaReferencia {

        @Test
        @DisplayName("dos ejercicios del mismo anuncio dan dos referencias distintas")
        void unaPorEjercicio() {
            String delAlta =
                    MovimientoDeAnuncio.referenciaDelCargo("AN-2026-000007", DOS_MIL_VEINTISEIS);
            String deLaRenovacion =
                    MovimientoDeAnuncio.referenciaDelCargo("AN-2026-000007", new Ejercicio(2027));

            assertThat(delAlta).isEqualTo("ANUNCIO-AN-2026-000007-2026");
            assertThat(deLaRenovacion)
                    .as("sin el ejercicio dentro, la primera renovacion seria imposible")
                    .isNotEqualTo(delAlta);
        }

        @Test
        @DisplayName("cabe en anuncio_movimiento.referencia_cargo, y si no, se dice")
        void cabeEnLaColumna() {
            String largo = "X".repeat(MovimientoDeAnuncio.REFERENCIA_MAXIMA);
            assertThatThrownBy(
                            () ->
                                    MovimientoDeAnuncio.autorizacion(
                                            1L,
                                            AUTORIZADO,
                                            DOS_MIL_VEINTISEIS,
                                            largo + largo,
                                            TASA,
                                            fin(2026),
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("referencia_cargo");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("La numeracion es una plantilla (D-09)")
    class LaNumeracion {

        @Test
        @DisplayName("la de omision compone AN-2026-000001")
        void laDeOmision() {
            assertThat(PlantillaDeNumeroDeAnuncio.POR_OMISION.componer(DOS_MIL_VEINTISEIS, 1))
                    .isEqualTo("AN-2026-000001");
        }

        @Test
        @DisplayName("otra plantilla compone otra cosa con el mismo correlativo")
        void otraPlantilla() {
            // Dos plantillas y no una: es lo que #40 aprendio analizando el numero del expediente.
            // Con una sola, cualquier suposicion sobre donde esta el ejercicio pasa en verde.
            PlantillaDeNumeroDeAnuncio otra =
                    new PlantillaDeNumeroDeAnuncio("{correlativo:4}-{ejercicio}-AP");

            assertThat(otra.componer(DOS_MIL_VEINTISEIS, 42)).isEqualTo("0042-2026-AP");
        }

        @Test
        @DisplayName("sin {ejercicio} no es una plantilla: dos anios compartirian numero")
        void sinEjercicio() {
            assertThatThrownBy(() -> new PlantillaDeNumeroDeAnuncio("AN-{correlativo:6}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{ejercicio}");
        }

        @Test
        @DisplayName("un numero que no cabe en la columna se rechaza al componerlo")
        void noCabe() {
            PlantillaDeNumeroDeAnuncio larga =
                    new PlantillaDeNumeroDeAnuncio("AUTORIZACION-{ejercicio}-{correlativo:8}");

            assertThatThrownBy(() -> larga.componer(DOS_MIL_VEINTISEIS, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anuncio.numero");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("La autorizacion y su criterio de busqueda")
    class LaAutorizacion {

        @Test
        @DisplayName("un anuncio de area cero no ocupa nada")
        void areaCero() {
            assertThatThrownBy(() -> anuncio(AreaM2.CERO, 1, fin(2026)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("area cero");
        }

        @Test
        @DisplayName("una vigencia que termina antes de empezar nace vencida")
        void vigenciaHaciaAtras() {
            assertThatThrownBy(
                            () ->
                                    anuncio(
                                            new AreaM2(new BigDecimal("6.00")),
                                            1,
                                            AUTORIZADO.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("termina antes de empezar");
        }

        @Test
        @DisplayName("un anuncio sin caras no existe")
        void sinLados() {
            assertThatThrownBy(() -> anuncio(new AreaM2(new BigDecimal("6.00")), 0, fin(2026)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("al menos una cara");
        }

        @Test
        @DisplayName("la clase, y solo la clase, tiene llave de tasa")
        void laClaveDeLaTasa() {
            assertThat(ClaseDeAnuncio.PANTALLA_DIGITAL.claveDeLaTasa())
                    .isEqualTo("PANTALLA_DIGITAL");
            assertThat(ClaseDeAnuncio.porNombre("pantalla digital"))
                    .as("la pantalla ofrece «Pantalla digital» y el parametro se llama con guion")
                    .isEqualTo(ClaseDeAnuncio.PANTALLA_DIGITAL);
            assertThat(TipoDeAnuncio.porNombre("aviso luminoso"))
                    .isEqualTo(TipoDeAnuncio.AVISO_LUMINOSO);
        }

        @Test
        @DisplayName("nulo y vacio no significan lo mismo al filtrar por titular")
        void nuloNoEsVacio() {
            assertThat(CriterioDeAnuncios.ninguno().sinTitularPosible())
                    .as("no se filtro por titular: el padron entero es la respuesta correcta")
                    .isFalse();
            assertThat(CriterioDeAnuncios.ninguno().conTitulares(Set.of()).sinTitularPosible())
                    .as("se filtro y no hay nadie: la respuesta correcta es ninguno")
                    .isTrue();
            assertThat(CriterioDeAnuncios.ninguno().conTitulares(Set.of(7L)).sinTitularPosible())
                    .isFalse();
        }

        @Test
        @DisplayName("un intervalo que termina antes de empezar se rechaza al construirlo")
        void intervaloHaciaAtras() {
            assertThatThrownBy(
                            () ->
                                    new CriterioDeAnuncios(
                                            null, null, null, null, fin(2026), AUTORIZADO, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("termina antes de empezar");
        }

        @Test
        @DisplayName("el resumen del padron no cuenta menos de cero")
        void elResumen() {
            assertThat(ResumenDelPadron.vacio().devengado()).isEqualTo(Dinero.CERO);
            assertThatThrownBy(() -> new ResumenDelPadron(-1, Dinero.CERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // Ayudas
    // ==================================================================

    private static EstadoDelAnuncio estadoA(
            List<MovimientoDeAnuncio> historial, LocalDate aLaFecha) {
        return EstadoDelAnuncio.derivarDe(
                historial, EstadoDelAnuncio.vigenciaSegun(historial, aLaFecha), aLaFecha);
    }

    private static LocalDate fin(int ejercicio) {
        return LocalDate.of(ejercicio, 12, 31);
    }

    private static MovimientoDeAnuncio autorizacion(
            LocalDate fecha, @Nullable LocalDate vigenciaHasta) {
        return MovimientoDeAnuncio.autorizacion(
                1L,
                fecha,
                new Ejercicio(fecha.getYear()),
                MovimientoDeAnuncio.referenciaDelCargo(
                        "AN-2026-000001", new Ejercicio(fecha.getYear())),
                TASA,
                vigenciaHasta,
                AHORA,
                PORQUE);
    }

    private static MovimientoDeAnuncio renovacion(
            LocalDate fecha, int ejercicio, @Nullable LocalDate vigenciaHasta) {
        return MovimientoDeAnuncio.renovacion(
                1L,
                fecha,
                new Ejercicio(ejercicio),
                MovimientoDeAnuncio.referenciaDelCargo("AN-2026-000001", new Ejercicio(ejercicio)),
                TASA,
                vigenciaHasta,
                AHORA,
                PORQUE);
    }

    private static MovimientoDeAnuncio cese(LocalDate fecha) {
        return MovimientoDeAnuncio.cese(1L, fecha, "Cese solicitado por el titular", AHORA, PORQUE);
    }

    private static MovimientoDeAnuncio retiro(LocalDate fecha) {
        return MovimientoDeAnuncio.retiro(
                1L, fecha, "Retirado y verificado en campo", AHORA, PORQUE);
    }

    private static Anuncio anuncio(AreaM2 area, int lados, LocalDate vigenciaHasta) {
        return new Anuncio(
                null,
                "AN-2026-000001",
                7L,
                null,
                null,
                ClaseDeAnuncio.PANEL,
                TipoDeAnuncio.AVISO_LUMINOSO,
                "FACHADA",
                "ADOSADO",
                "BODEGA SAN MARTIN",
                "AV. GRAU 100",
                area,
                lados,
                1,
                AUTORIZADO,
                vigenciaHasta,
                "EXP-2026-1",
                AUTORIZADO,
                null,
                AHORA,
                null,
                PORQUE);
    }
}
