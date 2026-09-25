package kamayuk.rentas.indicadores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.dobles.CajaDeMentira;
import kamayuk.rentas.indicadores.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * #450 — El panel de Inicio no espera a {@code caja} con una conexion de la base tomada.
 *
 * <p>Hasta #450 {@code PanelDeRecaudacion.del} era {@code @Transactional(readOnly = true)} entero,
 * y la cuarta cifra —el avance del dia— es un {@code GET} a {@code caja} con 30 s de espera. Con
 * {@code caja} lenta, cada apertura de Inicio retenia una de las diez conexiones del pool durante
 * esa espera, y la undecima peticion de cualquier pantalla —el guardia de acceso incluido— salia
 * con 500. El {@code catch} de {@code CajaInalcanzable} cubria que {@code caja} se cayera, no que
 * tardara.
 *
 * <p><b>La siembra que distingue es el vecino que anota</b>: los dobles de siempre contestan al
 * instante, y con eso cualquier frontera de transaccion pasa. Este anota si habia una transaccion
 * activa en el momento en que se le pregunto. El gestor de transacciones es de mentira —no hay base
 * en este modulo— pero la frontera la pone el {@link TransactionInterceptor} de verdad leyendo las
 * anotaciones de produccion, que es lo que se quiere medir.
 */
@DisplayName("#450 — El panel no espera a `caja` con una conexion tomada")
class ElPanelNoEsperaACajaConLaConexionTomadaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 13);
    private static final Instant AHORA = Instant.parse("2026-08-13T14:05:31Z");

    private final GestorQueCuenta gestor = new GestorQueCuenta();
    private final LibroQueAnota libro =
            new LibroQueAnota(
                    new LibroDeMentira()
                            .conRecaudado("PREDIAL", EJERCICIO, 3, "500.00", 4)
                            .conCargado("PREDIAL", "1000.00", 10)
                            .conPendiente("PREDIAL", "200.00", 3));
    private final CajaQueAnota caja = new CajaQueAnota();

    @Test
    @DisplayName("a `caja` se le pregunta sin ninguna transaccion abierta")
    void aCajaSeLePreguntaFueraDeLaTransaccion() {
        panel().del(EJERCICIO, HOY, AHORA);

        assertThat(caja.preguntas())
                .as(
                        "la pregunta a `caja` se hizo UNA vez, y en ese momento no habia ninguna"
                                + " transaccion —ni conexion— tomada: una caja lenta no puede"
                                + " retener el pool de rentas")
                .containsExactly(false);
    }

    @Test
    @DisplayName("y las tres lecturas del libro siguen siendo una sola foto")
    void lasTresLecturasDelLibroSonUnaSolaFoto() {
        panel().del(EJERCICIO, HOY, AHORA);

        assertThat(libro.lecturas())
                .as(
                        "lo recaudado, lo cargado y la cartera se leen DENTRO de una transaccion:"
                                + " sin ella no hay SET LOCAL y RLS no se puede evaluar")
                .containsExactly(true, true, true);
        assertThat(gestor.abiertas())
                .as(
                        "y las tres en la MISMA: con tres, cada cifra saldria de un instante"
                                + " distinto y la cartera podria recoger un pago que lo recaudado"
                                + " todavia no cuenta")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private PanelDeRecaudacion panel() {
        return envolver(
                new PanelDeRecaudacion(
                        envolver(new LecturaDelLibroParaElPanel(libro, libro)), caja));
    }

    @SuppressWarnings("unchecked")
    private <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /**
     * Un gestor que no habla con ninguna base y cuenta cuantas transacciones abrio.
     *
     * <p>Lo que importa de el es que {@link AbstractPlatformTransactionManager} marca la
     * transaccion como activa en {@link TransactionSynchronizationManager} exactamente igual que el
     * {@code TenantTransactionManager} de produccion: esa marca es lo que los dobles anotan.
     */
    private static final class GestorQueCuenta extends AbstractPlatformTransactionManager {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private int abiertas;

        int abiertas() {
            return abiertas;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaccion) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaccion, TransactionDefinition definicion) {
            abiertas++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus estado) {
            // Nada que confirmar: no hay base.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus estado) {
            // Nada que deshacer: no hay base.
        }
    }

    /** El libro de siempre, que ademas anota si cada lectura corrio dentro de una transaccion. */
    private static final class LibroQueAnota implements RecaudacionDelLibro, CarteraDelLibro {

        private final LibroDeMentira libro;
        private final List<Boolean> lecturas = new ArrayList<>();

        LibroQueAnota(LibroDeMentira libro) {
            this.libro = libro;
        }

        List<Boolean> lecturas() {
            return lecturas;
        }

        private void anotar() {
            lecturas.add(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @Override
        public RecaudadoEnElLibro recaudadoPor(
                Collection<String> tributos, LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
            anotar();
            return libro.recaudadoPor(tributos, desde, hasta, aLaFecha);
        }

        @Override
        public RecaudadoEnElLibro recaudadoDeTodos(
                LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
            anotar();
            return libro.recaudadoDeTodos(desde, hasta, aLaFecha);
        }

        @Override
        public CargadoEnElLibro cargadoPorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
            anotar();
            return libro.cargadoPorTributo(ejercicio, aLaFecha);
        }

        @Override
        public CarteraPendiente pendientePorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
            anotar();
            return libro.pendientePorTributo(ejercicio, aLaFecha);
        }
    }

    /** La caja de siempre, que ademas anota si se le pregunto con una transaccion abierta. */
    private static final class CajaQueAnota implements AvanceDeCaja {

        private final CajaDeMentira caja = new CajaDeMentira().con("310.00", "10.00");
        private final List<Boolean> preguntas = new ArrayList<>();

        List<Boolean> preguntas() {
            return preguntas;
        }

        @Override
        public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
            preguntas.add(TransactionSynchronizationManager.isActualTransactionActive());
            return caja.delDia(dia, aLaFecha);
        }
    }
}
