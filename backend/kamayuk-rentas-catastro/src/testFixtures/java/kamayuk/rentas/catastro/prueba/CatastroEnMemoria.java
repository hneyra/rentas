package kamayuk.rentas.catastro.prueba;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FichasDelPadron;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * <b>FIXTURE DE PRUEBA</b>: el catastro de otro sistema, sembrado en memoria (P5C).
 *
 * <h2>Por que existe, y por que aqui</h2>
 *
 * <p>Desde P5C las quince tablas de {@code catastro} no estan en esta base (`V6`). Lo que las
 * pruebas de {@code rentas} necesitaban de ellas no era la tabla sino <b>la premisa</b>: «este
 * predio existe, tiene esta ficha y este titular». Esta clase es donde se escribe esa premisa.
 *
 * <p>Vive en los {@code testFixtures} del modulo del <b>puerto</b> y no en cada consumidor porque
 * es la misma premisa para los cuatro que la necesitan, y repetirla cuatro veces es repetir la que
 * un dia se corrige a medias.
 *
 * <h2>Lo que NO es</h2>
 *
 * <p>No es un catastro. No versiona fichas, no comprueba que los porcentajes no excedan 100 y no
 * sabe lo que es una transferencia parcial — todo eso son reglas de {@code catastro} y sus 425
 * pruebas viven alli, contra PostgreSQL. Aqui solo hace falta que el puerto conteste algo coherente
 * para que la prueba de {@code rentas} pueda medir lo suyo.
 *
 * <p>Y NO devuelve vacio cuando no se ha sembrado nada: devolver una lista vacia es una respuesta
 * legitima —«este contribuyente no tiene predios»— asi que la prueba tiene que sembrar lo que
 * espera. Lo que no se puede es que un olvido de siembra se lea como un dato.
 */
public final class CatastroEnMemoria {

    /**
     * Todo lo sembrado se llavea por MUNICIPALIDAD, y los puertos resuelven la suya leyendo {@link
     * TenantContext} (P5C).
     *
     * <p>No es un adorno: varias pruebas de {@code rentas} miden el aislamiento —«desde B no se ve
     * el predio de A»— y con un fixture ciego al inquilino esa asercion pasaria por casualidad. En
     * produccion lo que separa las dos respuestas es el token que el cliente reenvia; aqui, el
     * contexto, que es lo que ese token acaba fijando.
     *
     * <p>Sin contexto fijado usa el cubo {@code 0}, para las pruebas que no tienen mas que una
     * municipalidad y no lo fijan.
     */
    private static long municipalidadActual() {
        // `TenantContext.actual()` lanza si no hay contexto —y hace bien: una lectura sin
        // municipalidad no puede devolver nada—. Aqui se pregunta primero, porque varias pruebas
        // que solo tienen una municipalidad no lo fijan y para ellas el cubo `0` sirve. Cazar la
        // excepcion en su lugar seria un `catch (RuntimeException)`, que el escaner prohibe.
        return TenantContext.actualSiHay().map(MunicipalidadId::valor).orElse(0L);
    }

    private static long clave(long id) {
        return municipalidadActual() * 1_000_000_000L + id;
    }

    private long claveDeSiembra(long id) {
        return sembrandoEn * 1_000_000_000L + id;
    }

    private long sembrandoEn;

    /** Siembra lo que sigue en esta municipalidad. Por omision, el cubo {@code 0}. */
    public CatastroEnMemoria en(long municipalidadId) {
        this.sembrandoEn = municipalidadId;
        return this;
    }

    private final Map<Long, List<CuotaConVigencia>> titulares = new LinkedHashMap<>();
    private final Map<Long, List<PredioDelContribuyente>> predios = new LinkedHashMap<>();
    private final Map<Long, CaracteristicasDelPredio> caracteristicas = new LinkedHashMap<>();
    private final Map<Long, Long> fichaVigente = new LinkedHashMap<>();
    private final Map<Long, AreaM2> areaDeLaFicha = new LinkedHashMap<>();
    private final List<FichaDelPadron> grilla = new ArrayList<>();

    // ---------------------------------------------------------------- siembra

    public CatastroEnMemoria conTitular(long predioId, long contribuyenteId, String porcentaje) {
        return conTitularEntre(
                predioId, contribuyenteId, "PROPIETARIO_UNICO", porcentaje, LocalDate.MIN, null);
    }

    /**
     * Un titular con su VIGENCIA, para las pruebas que preguntan a dos fechas.
     *
     * <p>El fixture resuelve por fecha a proposito. Lo que las pruebas de {@code rentas} miden con
     * eso no es la resolucion —esa es de {@code catastro} y sus pruebas viven alli— sino que la
     * fecha <b>viaje</b>: que el caso de uso pase la que le pidieron y no la del reloj, que es lo
     * que #366 midio y lo que #24 ya habia medido con los domicilios.
     */
    public CatastroEnMemoria conTitularEntre(
            long predioId,
            long contribuyenteId,
            String condicion,
            String porcentaje,
            LocalDate desde,
            @Nullable LocalDate hasta) {
        titulares
                .computeIfAbsent(claveDeSiembra(predioId), id -> new ArrayList<>())
                .add(
                        new CuotaConVigencia(
                                new TitularDelPredio(
                                        contribuyenteId, condicion, Porcentaje.de(porcentaje)),
                                desde,
                                hasta));
        return this;
    }

    private record CuotaConVigencia(
            TitularDelPredio cuota, LocalDate desde, @Nullable LocalDate hasta) {

        boolean rigeEn(LocalDate fecha) {
            return !desde.isAfter(fecha) && (hasta == null || !hasta.isBefore(fecha));
        }
    }

    private List<TitularDelPredio> vigentes(long predioId, LocalDate fecha) {
        List<TitularDelPredio> suyos = new ArrayList<>();
        for (CuotaConVigencia cuota : titulares.getOrDefault(clave(predioId), List.of())) {
            if (cuota.rigeEn(fecha)) {
                suyos.add(cuota.cuota());
            }
        }
        return List.copyOf(suyos);
    }

    public CatastroEnMemoria conPredioDe(
            long contribuyenteId,
            long predioId,
            String codigo,
            String direccion,
            String porcentaje) {
        predios.computeIfAbsent(claveDeSiembra(contribuyenteId), id -> new ArrayList<>())
                .add(
                        new PredioDelContribuyente(
                                predioId, codigo, "URBANO", direccion, Porcentaje.de(porcentaje)));
        return this;
    }

    public CatastroEnMemoria conCaracteristicas(
            long predioId, @Nullable String uso, @Nullable String sector, @Nullable AreaM2 area) {
        caracteristicas.put(
                claveDeSiembra(predioId), new CaracteristicasDelPredio(uso, sector, area));
        return this;
    }

    /** Una fila de la grilla de fichas, tal como `catastro` la publica. */
    public CatastroEnMemoria conFilaDeLaGrilla(FichaDelPadron fila) {
        grilla.add(fila);
        return this;
    }

    public CatastroEnMemoria conFichaVigente(long predioId, long fichaId, AreaM2 area) {
        fichaVigente.put(claveDeSiembra(predioId), fichaId);
        areaDeLaFicha.put(claveDeSiembra(fichaId), area);
        return this;
    }

    // ---------------------------------------------------------------- los puertos

    public TitularesDelPredio titulares() {
        return new TitularesDelPredio() {
            @Override
            public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
                return vigentes(predioId, fecha);
            }

            @Override
            public boolean estaEnElPadron(long predioId) {
                return titulares.containsKey(clave(predioId))
                        || caracteristicas.containsKey(clave(predioId))
                        || fichaVigente.containsKey(clave(predioId));
            }

            @Override
            public Map<Long, List<TitularDelPredio>> deVarios(
                    Collection<Long> predioIds, LocalDate fecha) {
                Map<Long, List<TitularDelPredio>> encontrados = new LinkedHashMap<>();
                for (Long predioId : predioIds) {
                    List<TitularDelPredio> suyos = vigentes(predioId, fecha);
                    if (!suyos.isEmpty()) {
                        encontrados.put(predioId, suyos);
                    }
                }
                return Map.copyOf(encontrados);
            }
        };
    }

    public PrediosDelContribuyente predios() {
        return (contribuyenteId, fecha) ->
                List.copyOf(predios.getOrDefault(contribuyenteId, List.of()));
    }

    public LectorDeCaracteristicas caracteristicas() {
        return (predioId, fecha) -> Optional.ofNullable(caracteristicas.get(clave(predioId)));
    }

    public LectorDeFichas fichas() {
        return new LectorDeFichas() {
            @Override
            public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
                return Optional.ofNullable(fichaVigente.get(clave(predioId)));
            }

            @Override
            public Optional<AreaM2> areaDeLaVersion(long fichaId) {
                return Optional.ofNullable(areaDeLaFicha.get(clave(fichaId)));
            }
        };
    }

    /**
     * La grilla de fichas, con la acotacion por predio HONRADA (#631).
     *
     * <p>Que la honre no es un adorno del fixture: lo que las pruebas de {@code rentas} miden aqui
     * es que la conciliacion <b>componga bien</b> la acotacion —«solo los que declararon» o «solo
     * los que no»— y la mande. Un fixture que la ignorara dejaria esa composicion sin medir.
     *
     * <p>Lo que ya NO se mide aqui es que la pagina y el {@code count(*)} de catastro salgan del
     * mismo WHERE: esa consulta vive en `catastro` desde P5C y sus pruebas tambien. Lo que si sigue
     * midiendose contra PostgreSQL de verdad es el RECUENTO de la conciliacion, que lee
     * `predio_ref` y `ficha_ref` en esta base.
     */
    public FichasDelPadron grilla() {
        return (criterio, aLaFecha, paginacion) -> {
            if (criterio.acotacion().noPuedeTraerNada()) {
                return Pagina.vacia(paginacion);
            }
            List<FichaDelPadron> filas = new ArrayList<>();
            for (FichaDelPadron fila : grilla) {
                if (pasa(fila, criterio)) {
                    filas.add(fila);
                }
            }
            int desde = Math.min(paginacion.pagina() * paginacion.tamano(), filas.size());
            int hasta = Math.min(desde + paginacion.tamano(), filas.size());
            return Pagina.de(List.copyOf(filas.subList(desde, hasta)), paginacion, filas.size());
        };
    }

    private static boolean pasa(FichaDelPadron fila, BusquedaDeFichas criterio) {
        AcotacionPorPredio acotacion = criterio.acotacion();
        boolean acotada =
                switch (acotacion.modo()) {
                    case TODOS -> true;
                    case SOLO_ESTOS -> acotacion.predios().contains(fila.predioId());
                    case TODOS_MENOS_ESTOS -> !acotacion.predios().contains(fila.predioId());
                };
        if (!acotada) {
            return false;
        }
        if (criterio.codRefCatastral() != null
                && !fila.codigoReferenciaCatastral().startsWith(criterio.codRefCatastral())) {
            return false;
        }
        return criterio.tipo() == null || criterio.tipo().equals(fila.tipo());
    }

    public LectorDeFichasEconomicas fichasEconomicas() {
        return (predioId, fecha) -> Optional.ofNullable(fichaVigente.get(clave(predioId)));
    }

    /**
     * La titularidad que se puede transferir.
     *
     * <p>Cierra la cuota anterior y abre la del adquiriente, que es lo que el puerto promete; NO
     * comprueba que el total no exceda 100, porque eso es un disparador diferido de {@code
     * catastro} y su prueba vive alli.
     */
    public GestorDeTitularidad titularidad() {
        return new GestorDeTitularidad() {
            @Override
            public Optional<CuotaDeTitularidad> vigenteDe(
                    long predioId, long contribuyenteId, LocalDate fecha) {
                return vigentes(predioId, fecha).stream()
                        .filter(cuota -> cuota.contribuyenteId() == contribuyenteId)
                        .findFirst()
                        .map(
                                cuota ->
                                        new CuotaDeTitularidad(
                                                predioId * 1_000 + contribuyenteId,
                                                predioId,
                                                contribuyenteId,
                                                cuota.porcentaje()));
            }

            @Override
            public CuotaDeTitularidad transferir(
                    long titularidadAnteriorId,
                    long adquirienteId,
                    Porcentaje porcentajeTransferido,
                    LocalDate fecha,
                    String documentoOrigen,
                    Observacion observacion) {
                long predioId = titularidadAnteriorId / 1_000;
                conTitular(predioId, adquirienteId, porcentajeTransferido.valor().toPlainString());
                return new CuotaDeTitularidad(
                        predioId * 1_000 + adquirienteId,
                        predioId,
                        adquirienteId,
                        porcentajeTransferido);
            }
        };
    }

    /** La versión que la transferencia de fiscalizacion deja inscrita. */
    public TransferenciaDeFiscalizacion transferenciaFiscal(
            AreaM2 areaAnterior, String usoAnterior) {
        return (predioId, desde, documentoOrigen, areaHallada, usoHallado, observacion) -> {
            long anterior = fichaVigente.getOrDefault(clave(predioId), 1L);
            long nueva = anterior + 1;
            fichaVigente.put(clave(predioId), nueva);
            AreaM2 area = areaHallada == null ? areaAnterior : areaHallada;
            areaDeLaFicha.put(clave(nueva), area);
            return new VersionTransferida(
                    anterior,
                    nueva,
                    2,
                    areaAnterior,
                    area,
                    usoAnterior,
                    usoHallado == null ? usoAnterior : usoHallado);
        };
    }
}
