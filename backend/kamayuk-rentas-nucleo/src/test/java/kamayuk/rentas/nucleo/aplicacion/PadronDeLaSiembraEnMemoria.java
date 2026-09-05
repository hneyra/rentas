package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import org.jspecify.annotations.Nullable;

/**
 * Los cinco colaboradores que la siembra necesita, en memoria: el padron de contribuyentes, el de
 * predios con su titularidad, el vehicular, el registro de transferencias y el libro.
 *
 * <p>Sin base de datos <b>a proposito</b>. Lo que PostgreSQL garantiza —el indice unico de la
 * placa, el disparador diferido de la titularidad, la particion del libro por ejercicio— ya tiene
 * sus pruebas contra el motor de verdad ({@code RegistrarTransferenciaTest}, {@code
 * PadronVehicularTest}). Lo que se verifica con este doble es lo que los importadores agregan: que
 * los codigos del archivo se resuelvan a identificadores, que una fila mala no arrastre a la que la
 * sigue, y que la unidad de una obligacion se resuelva <b>a la fecha valor</b> y no a la de hoy.
 *
 * <p>La titularidad se guarda por tramos con su vigencia, y no como «la ultima»: es la unica forma
 * de que {@link #de(long, LocalDate)} pueda responder distinto para marzo y para julio, que es
 * precisamente la propiedad que las pruebas de la cadena de transferencias miden.
 */
final class PadronDeLaSiembraEnMemoria
        implements DirectorioDeContribuyentes,
                PrediosDelContribuyente,
                GestorDeTitularidad,
                VehiculoRepository,
                GeneradorDeCargos {

    /** Un tramo de titularidad: quien tiene cuanto de que predio, entre dos fechas. */
    record Cuota(
            long id,
            long predioId,
            long contribuyenteId,
            Porcentaje porcentaje,
            LocalDate desde,
            @Nullable LocalDate hasta) {

        boolean vigenteEl(LocalDate fecha) {
            return !fecha.isBefore(desde) && (hasta == null || !fecha.isAfter(hasta));
        }
    }

    /** Un cargo tal como lo recibio el libro, para poder afirmar contra que unidad se asento. */
    record Cargo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen) {}

    private final Map<String, ResumenDeContribuyente> contribuyentes = new LinkedHashMap<>();
    private final Map<Long, String> codigoDelPredio = new HashMap<>();
    private final List<Cuota> cuotas = new ArrayList<>();
    private final Map<Long, Vehiculo> vehiculos = new LinkedHashMap<>();
    private final List<Transferencia> transferencias = new ArrayList<>();
    private final List<Cargo> cargos = new ArrayList<>();

    private long siguienteId = 1;

    // --- siembra del propio doble ---------------------------------------

    long sembrarContribuyente(String codigo, String nombre) {
        long id = siguienteId++;
        contribuyentes.put(codigo, new ResumenDeContribuyente(id, codigo, nombre, "0000000" + id));
        return id;
    }

    long sembrarPredio(String codigoCatastral, long titularId, LocalDate desde) {
        long predioId = siguienteId++;
        codigoDelPredio.put(predioId, codigoCatastral);
        cuotas.add(new Cuota(siguienteId++, predioId, titularId, Porcentaje.total(), desde, null));
        return predioId;
    }

    long sembrarVehiculo(Placa placa, long contribuyenteId) {
        long id = siguienteId++;
        vehiculos.put(
                id,
                new Vehiculo(
                        id,
                        placa,
                        contribuyenteId,
                        "MARCA",
                        "MODELO",
                        null,
                        new Ejercicio(2020),
                        new Ejercicio(2021),
                        null,
                        null,
                        kamayuk.rentas.nucleo.dominio.EstadoVehiculo.ACTIVO));
        return id;
    }

    // --- lo que las pruebas preguntan -----------------------------------

    /** Las cuotas de titularidad todavia abiertas, de cualquier predio. */
    List<Cuota> cuotasVivas() {
        return cuotas.stream().filter(cuota -> cuota.hasta() == null).toList();
    }

    List<Cuota> cuotasDe(long predioId) {
        return cuotas.stream().filter(cuota -> cuota.predioId() == predioId).toList();
    }

    List<Transferencia> transferenciasRegistradas() {
        return List.copyOf(transferencias);
    }

    /**
     * El registro de transferencias, aparte del resto del doble: {@code TransferenciaRepository} y
     * {@code VehiculoRepository} declaran los dos un {@code findById(long)} con la misma firma y
     * distinto tipo de vuelta, asi que una sola clase no puede implementar los dos.
     */
    TransferenciaRepository registroDeTransferencias() {
        return new TransferenciaRepository() {
            @Override
            public Transferencia insertar(Transferencia transferencia) {
                transferencias.add(transferencia);
                return transferencia;
            }

            @Override
            public Optional<Transferencia> findById(long id) {
                return Optional.empty();
            }

            @Override
            public List<Transferencia> historicoDePredio(long predioId) {
                return transferencias.stream()
                        .filter(t -> t.predioId() != null && t.predioId() == predioId)
                        .toList();
            }

            @Override
            public Optional<Long> contribuyentePorCodigo(String codigo) {
                return porCodigo(codigo).map(ResumenDeContribuyente::id);
            }
        };
    }

    List<Cargo> cargosAsentados() {
        return List.copyOf(cargos);
    }

    List<Vehiculo> padronVehicular() {
        return List.copyOf(vehiculos.values());
    }

    // --- DirectorioDeContribuyentes -------------------------------------

    @Override
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        return List.copyOf(contribuyentes.values());
    }

    @Override
    public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
        return Optional.ofNullable(contribuyentes.get(codigo));
    }

    @Override
    public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
        Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
        for (ResumenDeContribuyente resumen : contribuyentes.values()) {
            if (ids.contains(resumen.id())) {
                encontrados.put(resumen.id(), resumen);
            }
        }
        return encontrados;
    }

    @Override
    public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
        return Optional.empty();
    }

    // --- PrediosDelContribuyente ----------------------------------------

    @Override
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        List<PredioDelContribuyente> suyos = new ArrayList<>();
        for (Cuota cuota : cuotas) {
            if (cuota.contribuyenteId() == contribuyenteId && cuota.vigenteEl(fecha)) {
                suyos.add(
                        new PredioDelContribuyente(
                                cuota.predioId(),
                                codigoDelPredio.getOrDefault(cuota.predioId(), ""),
                                "URBANO",
                                "Direccion de prueba",
                                cuota.porcentaje()));
            }
        }
        return List.copyOf(suyos);
    }

    // --- GestorDeTitularidad --------------------------------------------

    @Override
    public Optional<CuotaDeTitularidad> vigenteDe(
            long predioId, long contribuyenteId, LocalDate fecha) {
        for (Cuota cuota : cuotas) {
            if (cuota.predioId() == predioId
                    && cuota.contribuyenteId() == contribuyenteId
                    && cuota.vigenteEl(fecha)) {
                return Optional.of(
                        new CuotaDeTitularidad(
                                cuota.id(), predioId, contribuyenteId, cuota.porcentaje()));
            }
        }
        return Optional.empty();
    }

    @Override
    public CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {

        int posicion = posicionDe(titularidadAnteriorId);
        Cuota anterior = cuotas.get(posicion);
        java.math.BigDecimal remanente =
                anterior.porcentaje().valor().subtract(porcentajeTransferido.valor());
        if (remanente.signum() < 0) {
            throw new IllegalArgumentException(
                    "No se puede transferir mas de lo que se tiene: "
                            + porcentajeTransferido
                            + " de "
                            + anterior.porcentaje());
        }

        cuotas.set(
                posicion,
                new Cuota(
                        anterior.id(),
                        anterior.predioId(),
                        anterior.contribuyenteId(),
                        anterior.porcentaje(),
                        anterior.desde(),
                        fecha.minusDays(1)));

        Cuota nueva =
                new Cuota(
                        siguienteId++,
                        anterior.predioId(),
                        adquirienteId,
                        porcentajeTransferido,
                        fecha,
                        null);
        cuotas.add(nueva);

        if (remanente.signum() > 0) {
            cuotas.add(
                    new Cuota(
                            siguienteId++,
                            anterior.predioId(),
                            anterior.contribuyenteId(),
                            new Porcentaje(remanente),
                            fecha,
                            null));
        }
        return new CuotaDeTitularidad(
                nueva.id(), nueva.predioId(), adquirienteId, porcentajeTransferido);
    }

    private int posicionDe(long titularidadId) {
        for (int i = 0; i < cuotas.size(); i++) {
            if (cuotas.get(i).id() == titularidadId) {
                return i;
            }
        }
        throw new IllegalArgumentException("No hay titularidad " + titularidadId);
    }

    // --- VehiculoRepository ---------------------------------------------

    @Override
    public Optional<Vehiculo> findByPlaca(Placa placa) {
        return vehiculos.values().stream().filter(v -> v.placa().equals(placa)).findFirst();
    }

    @Override
    public Optional<Vehiculo> findById(long id) {
        return Optional.ofNullable(vehiculos.get(id));
    }

    @Override
    public Pagina<VehiculoEncontrado> buscar(CriterioDeVehiculo criterio, Paginacion paginacion) {
        return new Pagina<>(List.of(), paginacion.pagina(), paginacion.tamano(), 0);
    }

    @Override
    public Vehiculo save(Vehiculo vehiculo) {
        if (!vehiculo.esNuevo()) {
            long id = java.util.Objects.requireNonNull(vehiculo.id());
            vehiculos.put(id, vehiculo);
            return vehiculo;
        }
        if (findByPlaca(vehiculo.placa()).isPresent()) {
            throw new org.springframework.dao.DuplicateKeyException(
                    "vehiculo_placa_uq: " + vehiculo.placa());
        }
        long id = siguienteId++;
        Vehiculo guardado =
                new Vehiculo(
                        id,
                        vehiculo.placa(),
                        vehiculo.contribuyenteId(),
                        vehiculo.marca(),
                        vehiculo.modelo(),
                        vehiculo.categoria(),
                        vehiculo.anioFabricacion(),
                        vehiculo.anioInscripcion(),
                        vehiculo.numeroMotor(),
                        vehiculo.numeroSerie(),
                        vehiculo.estado());
        vehiculos.put(id, guardado);
        return guardado;
    }

    @Override
    public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
        return List.of();
    }

    // --- GeneradorDeCargos ----------------------------------------------

    @Override
    public void generarCargo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        cargos.add(
                new Cargo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        periodo,
                        predioId,
                        vehiculoId,
                        monto,
                        fechaValor,
                        documentoOrigen));
    }

    @Override
    public void generarGastoDelProcedimiento(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        throw new UnsupportedOperationException("La siembra no asienta gastos del procedimiento");
    }
}
