package kamayuk.rentas.indicadores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.dobles.ModulosDeMentira;
import kamayuk.rentas.indicadores.dominio.FrenteDeTrabajo;
import kamayuk.rentas.indicadores.dominio.FrenteParado;
import kamayuk.rentas.indicadores.dominio.TrabajoParado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El trabajo parado por modulo: se compone de lo que otros publican (#549, RF-130).
 *
 * <p>Los cuatro puertos son dobles en memoria de las <b>APIs publicas</b> de sanciones, valores,
 * coactiva y rentas, no de sus repositorios. Que las cifras cuadren con lo que la grilla de cada
 * modulo dice —AC 2.4— se prueba donde vive el SQL, contra PostgreSQL de verdad: en {@code
 * PapeletasSinNotificarJdbcTest} y sus tres gemelas.
 */
@DisplayName("#549 — El trabajo parado, por modulo")
class ConsultaDeTrabajoParadoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 13);
    private static final Instant AHORA = Instant.parse("2026-08-13T14:05:31Z");

    private final ModulosDeMentira modulos =
            new ModulosDeMentira()
                    .conPapeletas(1842, "788976.00")
                    .conValores(412)
                    .conExpedientes(388)
                    .conPredios(23);

    private final ConsultaDeTrabajoParado consulta =
            new ConsultaDeTrabajoParado(modulos, modulos, modulos, modulos);

    private TrabajoParado todos() {
        return consulta.del(EJERCICIO, HOY, AHORA, EnumSet.allOf(FrenteDeTrabajo.class));
    }

    @Nested
    @DisplayName("AC 2.1 — cada frente con su modulo, su recuento y su fecha")
    class CadaFrente {

        @Test
        @DisplayName("los cuatro frentes salen, en el orden del enumerado")
        void losCuatroFrentes() {
            assertThat(todos().frentes())
                    .extracting(FrenteParado::frente)
                    .containsExactly(
                            FrenteDeTrabajo.TRANSITO,
                            FrenteDeTrabajo.VALORES,
                            FrenteDeTrabajo.COACTIVA,
                            FrenteDeTrabajo.CATASTRO);
        }

        @Test
        @DisplayName("cada uno dice de que modulo es y que esta parado")
        void cadaUnoDiceDeQueModuloEs() {
            FrenteParado coactiva = frente(FrenteDeTrabajo.COACTIVA);

            assertThat(coactiva.frente().modulo()).isEqualTo("Coactiva");
            assertThat(coactiva.frente().queEstaParado())
                    .isEqualTo("expedientes importados sin REC-1");
            assertThat(coactiva.cuantos()).isEqualTo(388);
        }

        @Test
        @DisplayName("todos llevan su fecha, y el conjunto ademas su hora")
        void todosLlevanSuFecha() {
            TrabajoParado parado = todos();

            assertThat(parado.fechaCalculo()).isEqualTo(HOY);
            assertThat(parado.calculadoEn()).isEqualTo(AHORA);
            assertThat(parado.frentes())
                    .isNotEmpty()
                    .allSatisfy(f -> assertThat(f.actualizadoA()).isEqualTo(HOY));
        }

        @Test
        @DisplayName("el padron se concilia contra el ejercicio pedido, y a la fecha pedida")
        void elPadronSeConciliaContraElEjercicioPedido() {
            // Con otro ejercicio la cifra seguiria siendo plausible: declarar 2024 no
            // concilia 2026, y el padron afecto se rehace cada anio (ADR-0015).
            consulta.del(new Ejercicio(2025), HOY, AHORA, EnumSet.allOf(FrenteDeTrabajo.class));

            assertThat(modulos.ejercicioDeLosPredios()).isEqualTo(new Ejercicio(2025));
            assertThat(modulos.fechaDeLosPredios()).isEqualTo(HOY);
            assertThat(modulos.fechaDeLosValores())
                    .as("la situacion de un valor se mira a una fecha, no «ahora»")
                    .isEqualTo(HOY);
        }
    }

    @Nested
    @DisplayName("AC 2.2 — sin cifrar no es cero")
    class SinCifrarNoEsCero {

        @Test
        @DisplayName("el frente que el modulo sabe cifrar trae su importe")
        void elFrenteCifradoTraeSuImporte() {
            FrenteParado transito = frente(FrenteDeTrabajo.TRANSITO);

            assertThat(transito.estaCifrado()).isTrue();
            assertThat(transito.importe()).isEqualTo(Dinero.de("788976.00"));
        }

        @Test
        @DisplayName("los tres que no se pueden cifrar traen el importe NULO, no cero")
        void losQueNoSeCifranTraenNulo() {
            // Un «S/ 0.00» junto a «412 valores» se lee como «esos 412 no valen nada»,
            // que es exactamente lo contrario de lo que pasa (el defecto de #51 con la
            // tasa por omision).
            for (FrenteDeTrabajo cual :
                    Set.of(
                            FrenteDeTrabajo.VALORES,
                            FrenteDeTrabajo.COACTIVA,
                            FrenteDeTrabajo.CATASTRO)) {
                FrenteParado sinCifrar = frente(cual);
                assertThat(sinCifrar.importe()).as("el importe de %s", cual).isNull();
                assertThat(sinCifrar.estaCifrado()).isFalse();
                assertThat(sinCifrar.cuantos())
                        .as("y aun asi trae su recuento: sin cifrar no es sin contar")
                        .isPositive();
            }
        }

        @Test
        @DisplayName("un importe que de verdad es cero SIGUE trayendo la cifra")
        void unImporteDeVerdadCeroSigueTrayendoLaCifra() {
            // Es la otra mitad del AC 2.2: cero papeletas impuestas suman S/ 0.00, y eso
            // es un hecho que se puede afirmar. Si los dos casos se publicaran igual, la
            // interfaz no podria dibujarlos distinto.
            ModulosDeMentira vacio =
                    new ModulosDeMentira().conPapeletas(0, "0.00").conValores(0).conPredios(0);
            TrabajoParado parado =
                    new ConsultaDeTrabajoParado(vacio, vacio, vacio, vacio)
                            .del(EJERCICIO, HOY, AHORA, EnumSet.allOf(FrenteDeTrabajo.class));

            FrenteParado transito = frente(parado, FrenteDeTrabajo.TRANSITO);
            assertThat(transito.cuantos()).isZero();
            assertThat(transito.estaCifrado()).isTrue();
            assertThat(transito.importe()).isEqualTo(Dinero.CERO);

            assertThat(frente(parado, FrenteDeTrabajo.VALORES).importe()).isNull();
        }
    }

    @Nested
    @DisplayName("AC 2.3 — el frente sin permiso ni se consulta ni se publica")
    class ElFrenteSinPermiso {

        @Test
        @DisplayName("un perfil sin Coactiva no recibe ese frente, y tampoco uno vacio")
        void unPerfilSinCoactivaNoRecibeEseFrente() {
            TrabajoParado parado =
                    consulta.del(
                            EJERCICIO,
                            HOY,
                            AHORA,
                            EnumSet.of(FrenteDeTrabajo.TRANSITO, FrenteDeTrabajo.VALORES));

            assertThat(parado.frentes())
                    .extracting(FrenteParado::frente)
                    .containsExactly(FrenteDeTrabajo.TRANSITO, FrenteDeTrabajo.VALORES);
        }

        @Test
        @DisplayName("y no se llega a consultar: el modulo no recibe la pregunta")
        void niSeLlegaAConsultar() {
            // «No sale» y «sale y se descarta» son indistinguibles en la respuesta. Lo
            // que las separa es que la consulta no se haga: un frente que el perfil no
            // puede ver no es solo una fila que no se dibuja, es trabajo que el motor no
            // hace en la pantalla que todo el mundo abre al entrar.
            consulta.del(EJERCICIO, HOY, AHORA, EnumSet.of(FrenteDeTrabajo.TRANSITO));

            assertThat(modulos.preguntados()).containsExactly("TRANSITO");
        }

        @Test
        @DisplayName("un perfil sin ninguna de las cuatro recibe la lista vacia, no un error")
        void unPerfilSinNingunaRecibeLaListaVacia() {
            TrabajoParado parado =
                    consulta.del(EJERCICIO, HOY, AHORA, EnumSet.noneOf(FrenteDeTrabajo.class));

            assertThat(parado.frentes()).isEmpty();
            assertThat(modulos.preguntados()).isEmpty();
            assertThat(parado.fechaCalculo())
                    .as("y sigue diciendo a que dia contesta")
                    .isEqualTo(HOY);
        }
    }

    // ------------------------------------------------------------------

    private FrenteParado frente(FrenteDeTrabajo cual) {
        return frente(todos(), cual);
    }

    private static FrenteParado frente(TrabajoParado parado, FrenteDeTrabajo cual) {
        return parado.frentes().stream()
                .filter(f -> f.frente() == cual)
                .findFirst()
                .orElseThrow(() -> new AssertionError("El panel no publica el frente " + cual));
    }
}
