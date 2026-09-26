package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
import kamayuk.rentas.nucleo.dominio.MarcaYModelo;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.NormativaQueCambiaEntreLlamadas;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #361 — El vehicular resuelve el conjunto sellado <b>una vez</b>, y de esa resolucion salen la
 * tabla de valores referenciales, la alicuota, el minimo y el {@code conjunto_id} que se guarda.
 *
 * <p>Hasta #361 lo resolvia tres veces: {@code ValoresReferenciales.de} para leer la tabla, y
 * {@code RegistrarDeterminacionVehicular.calcular} otras dos —los parametros por un lado, el
 * identificador por otro—. Su propio javadoc lo prohibia («volver a preguntarselos al lector seria
 * una segunda lectura que podria caer en otro conjunto»), y ninguna prueba lo veia porque todos los
 * dobles del lector contestaban siempre el mismo conjunto.
 *
 * <p>La siembra que distingue: {@link NormativaQueCambiaEntreLlamadas}, con v1 y v2 distintos en
 * las tres cifras que el calculo usa —el valor referencial de la tabla, la alicuota y la UIT—. Sin
 * base de datos: la tabla la contesta un doble que <b>depende del conjunto</b> que se le pregunta.
 */
@DisplayName("#361 — El vehicular resuelve el conjunto una sola vez")
class DeterminacionVehicularDeUnSoloConjuntoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final IdentificadorDeConjunto V1 = IdentificadorDeConjunto.de(1L);
    private static final IdentificadorDeConjunto V2 = IdentificadorDeConjunto.de(2L);

    private static final Vehiculo EL_VEHICULO =
            new Vehiculo(
                    7L,
                    Placa.de("V1H-882"),
                    501L,
                    "TOYOTA",
                    "YARIS",
                    "M1",
                    new Ejercicio(2024),
                    new Ejercicio(2025),
                    null,
                    null,
                    EstadoVehiculo.ACTIVO);

    @Test
    @DisplayName(
            "la tabla, la alicuota y el conjunto_id guardado salen del mismo conjunto, resuelto una"
                    + " vez")
    void todoSaleDelConjuntoGuardado() {
        NormativaQueCambiaEntreLlamadas normativa =
                new NormativaQueCambiaEntreLlamadas(
                        V1, conjunto(1, "1.0", "5500.00"), V2, conjunto(2, "2.0", "5350.00"));
        TablaPorConjunto tabla = new TablaPorConjunto();
        RegistrarDeterminacionVehicular registrar =
                new RegistrarDeterminacionVehicular(
                        new UnSoloVehiculo(),
                        new SinTransferencias(),
                        new ValoresReferenciales(tabla, normativa),
                        new SinDeterminaciones(),
                        normativa,
                        registro -> {});

        RegistrarDeterminacionVehicular.Calculo calculo =
                registrar.calcular(
                        7L, EJERCICIO, true, Observacion.de("Simulacion del vehicular 2026"));

        int resoluciones = normativa.resoluciones();
        IdentificadorDeConjunto guardado =
                IdentificadorDeConjunto.de(calculo.determinacion().conjuntoId());
        ParametrosSellados delGuardado = normativa.porConjunto(guardado);
        Alicuota alicuotaDelGuardado =
                Alicuota.de(
                        delGuardado
                                .exigirNumero("VEHICULAR_ALICUOTA", null)
                                .valor()
                                .toPlainString());
        SoftAssertions.assertSoftly(
                blando -> {
                    blando.assertThat(resoluciones)
                            .as(
                                    "un calculo resuelve el conjunto UNA vez: cada resolucion de"
                                            + " mas es otra pregunta por red a normativa, y puede"
                                            + " contestar otro conjunto")
                            .isEqualTo(1);
                    blando.assertThat(tabla.preguntados)
                            .as(
                                    "la tabla de valores referenciales se lee del conjunto que la"
                                            + " fila guarda")
                            .containsOnly(guardado);
                    blando.assertThat(calculo.determinacion().baseImponible())
                            .as("la base es el valor referencial de ESE conjunto")
                            .isEqualTo(TablaPorConjunto.valorDe(guardado));
                    blando.assertThat(calculo.alicuota())
                            .as(
                                    "y la alicuota tambien: si no, el importe no se reproduce con"
                                            + " ese conjunto")
                            .isEqualTo(alicuotaDelGuardado);
                    blando.assertThat(calculo.conjunto())
                            .as("la memoria del calculo nombra el conjunto que la fila guarda")
                            .isEqualTo(delGuardado.ejercicio() + " v" + delGuardado.version());
                });
    }

    private static ParametrosSellados conjunto(int version, String alicuota, String uit) {
        return ParametrosSellados.de(EJERCICIO, version)
                .numero("VEHICULAR_ALICUOTA", null, ValorNormativo.de(alicuota))
                .numero("VEHICULAR_MINIMO_UIT", null, ValorNormativo.de("1.5"))
                .numero("UIT", null, ValorNormativo.de(uit))
                // Desde #378 el impuesto se redondea en su punto, con la politica del conjunto.
                .numero("REDONDEO", "IMPUESTO_VEHICULAR", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_VEHICULAR", "HALF_UP")
                .construir();
    }

    // ---------------------------------------------------------------- dobles

    /** La tabla del MEF, con una cifra DISTINTA en cada conjunto: v1 y v2 no valen lo mismo. */
    private static final class TablaPorConjunto implements ValorReferencialRepository {

        private final List<IdentificadorDeConjunto> preguntados = new ArrayList<>();

        static Dinero valorDe(IdentificadorDeConjunto conjunto) {
            return conjunto.equals(V1) ? Dinero.de("100000.00") : Dinero.de("50000.00");
        }

        @Override
        public Optional<ValorReferencial> buscar(
                IdentificadorDeConjunto conjunto,
                String marca,
                String modelo,
                int anio,
                @Nullable String categoria) {
            preguntados.add(conjunto);
            return Optional.of(
                    new ValorReferencial(
                            EJERCICIO,
                            marca,
                            modelo,
                            new Ejercicio(anio),
                            valorDe(conjunto),
                            "ficticio de prueba"));
        }

        @Override
        public List<String> categorias(IdentificadorDeConjunto conjunto) {
            return List.of("M1");
        }

        @Override
        public List<MarcaYModelo> catalogo(IdentificadorDeConjunto conjunto) {
            return List.of();
        }
    }

    private static final class UnSoloVehiculo implements VehiculoRepository {

        @Override
        public Optional<Vehiculo> findByPlaca(Placa placa) {
            return EL_VEHICULO.placa().equals(placa) ? Optional.of(EL_VEHICULO) : Optional.empty();
        }

        @Override
        public Optional<Vehiculo> findById(long id) {
            return id == 7L ? Optional.of(EL_VEHICULO) : Optional.empty();
        }

        @Override
        public Pagina<VehiculoEncontrado> buscar(
                CriterioDeVehiculo criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("El calculo no busca vehiculos");
        }

        @Override
        public Vehiculo save(Vehiculo vehiculo) {
            throw new UnsupportedOperationException("El calculo no guarda vehiculos");
        }

        @Override
        public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
            return List.of();
        }
    }

    /** Ningun vehiculo cambio de manos: el titular de hoy es el del 1 de enero (#329). */
    private static final class SinTransferencias implements TransferenciaRepository {

        @Override
        public Transferencia insertar(Transferencia transferencia) {
            throw new UnsupportedOperationException("El calculo no registra transferencias");
        }

        @Override
        public Optional<Transferencia> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<Transferencia> historicoDePredio(long predioId) {
            return List.of();
        }

        @Override
        public List<Transferencia> historicoDeVehiculo(long vehiculoId) {
            return List.of();
        }

        @Override
        public List<Long> vehiculosQueTransfirioDesde(long transferenteId, LocalDate fecha) {
            return List.of();
        }

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return Optional.empty();
        }
    }

    /** Se simula: nada se asienta, y si algo lo intenta, la prueba lo dice. */
    private static final class SinDeterminaciones implements DeterminacionRepository {

        @Override
        public Optional<Determinacion> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.of();
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return Optional.empty();
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return List.of();
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            throw new UnsupportedOperationException("Una simulacion no asienta");
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            throw new UnsupportedOperationException("Una simulacion no asienta");
        }
    }
}
