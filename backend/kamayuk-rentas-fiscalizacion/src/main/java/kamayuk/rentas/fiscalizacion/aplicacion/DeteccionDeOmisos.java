package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeDeteccion;
import kamayuk.rentas.fiscalizacion.dominio.DeteccionRepository;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Omisos y subvaluadores: el cruce del padrón de predios contra las declaraciones juradas de un
 * ejercicio ({@code fisc_omisos}, RF-055).
 *
 * <h2>El cruce es una consulta, y el filtro acota el conjunto (#545)</h2>
 *
 * <p>Hasta #545 este caso de uso componía el cruce en Java: una página del padrón por {@code
 * catastro.PadronDePredios} y las declaraciones de esos predios por {@code
 * rentas.DeclaracionesDelEjercicio}. Esa forma no puede filtrar por condición —la condición se
 * deriva del cruce, así que sólo se conoce después de traer la página— y lo que hacía era descartar
 * filas ya paginadas: {@code ?condicion=SUBVALUADOR} contestaba «cero filas, de veinticinco, en
 * nueve páginas». Ahora el cruce lo resuelve {@link DeteccionRepository} en una consulta, el filtro
 * entra en el {@code WHERE} y el sobre cuenta lo filtrado. El porqué de dónde vive esa consulta
 * está en el javadoc del puerto.
 *
 * <p>Lo que queda aquí es lo que no es del motor: <b>resolver los titulares</b> de la página por el
 * puerto público de catastro, en una lectura y no una por fila.
 *
 * <h2>Omiso no es extemporáneo, y ése es el AC 3</h2>
 *
 * <p>Quien presentó su declaración fuera de plazo <b>declaró</b>: su condición sale de comparar lo
 * declarado con lo verificado como la de cualquiera, y lo que le corresponde por el retraso es la
 * multa del art. 176 —otra consecuencia, otro procedimiento—. Confundirlas produce determinaciones
 * de oficio sobre contribuyentes que sí presentaron su declaración, y ésas se anulan en
 * reclamación.
 *
 * <p>La distinción no se decide aquí ni en el SQL sin más: {@link ComparacionHalladoDeclarado} es
 * la función pura que la define, y la consulta es su transcripción. Que las dos no se separen lo
 * sostiene una prueba que las compara caso por caso, no un comentario.
 *
 * <h2>El «valor catastral verificado» no existe todavía</h2>
 *
 * <p>La pantalla pide cuatro importes —valor catastral, valor declarado, diferencia e impuesto
 * omitido— y los cuatro salen del cuadro de valores unitarios, la tabla de depreciación y el
 * arancel: <b>D-02a</b>, sin firmar (#198). Salen en {@code null}, con su nombre.
 *
 * <p>Lo que sí se compara es la <b>superficie</b>, y de la única forma que es reproducible: el área
 * de la versión de ficha que la declaración jurada <b>referencia</b> —{@code
 * declaracion_jurada.ficha_catastral_id}, desde #28— frente al área de la ficha que el catastro
 * tiene vigente hoy. Si entre una y otra el catastro inscribió una ampliación que el contribuyente
 * nunca declaró, la diferencia sale sola.
 */
@Service
public class DeteccionDeOmisos {

    private final DeteccionRepository deteccion;
    private final TitularesDelPredio titulares;

    public DeteccionDeOmisos(DeteccionRepository deteccion, TitularesDelPredio titulares) {
        this.deteccion = deteccion;
        this.titulares = titulares;
    }

    /**
     * La página de predios detectados.
     *
     * <p>{@code @Transactional(readOnly = true)}: sin transacción no hay {@code SET LOCAL}, y sin
     * él la política RLS <b>falla</b> en vez de devolver filas.
     *
     * @param ejercicio qué ejercicio se examina
     * @param sectorCodigo filtro opcional de sector
     * @param condicion filtro opcional de condición; {@code null} trae todas las filas, también las
     *     conformes, porque la pantalla ofrece «Todas»
     * @param aLaFecha a qué día se resuelven la titularidad y la ficha vigentes (regla 9)
     */
    @Transactional(readOnly = true)
    public Pagina<FilaDeOmisos> detectar(
            Ejercicio ejercicio,
            @Nullable String sectorCodigo,
            @Nullable CondicionFiscalizada condicion,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        Objects.requireNonNull(ejercicio, "La deteccion necesita el ejercicio que examina");
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");

        Pagina<FilaDeOmisos> pagina =
                deteccion.detectar(
                        new CriterioDeDeteccion(ejercicio, sectorCodigo, condicion, aLaFecha),
                        paginacion);
        if (pagina.estaVacia()) {
            return pagina;
        }

        // Una sola lectura de titulares por pagina, no una por fila.
        Set<Long> predios = new LinkedHashSet<>();
        for (FilaDeOmisos fila : pagina.contenido()) {
            predios.add(fila.predioId());
        }
        Map<Long, List<TitularDelPredio>> porPredio = titulares.deVarios(predios, aLaFecha);

        List<FilaDeOmisos> filas = new ArrayList<>();
        for (FilaDeOmisos fila : pagina.contenido()) {
            filas.add(fila.conTitulares(identificadoresDe(porPredio.get(fila.predioId()))));
        }
        return new Pagina<>(filas, pagina.pagina(), pagina.tamano(), pagina.totalElementos());
    }

    /**
     * Los identificadores de las cuotas, en el orden que catastro las da: mayor porcentaje primero.
     *
     * <p>Un predio sin titular vigente no está en el mapa y sale con la lista vacía. Sale igual en
     * la detección, y eso es #545: un predio que nadie reclama es exactamente el que hay que
     * fiscalizar, y antes se caía de la lista sin que la respuesta lo dijera.
     */
    private static List<Long> identificadoresDe(@Nullable List<TitularDelPredio> cuotas) {
        if (cuotas == null || cuotas.isEmpty()) {
            return List.of();
        }
        List<Long> identificadores = new ArrayList<>();
        for (TitularDelPredio cuota : cuotas) {
            identificadores.add(cuota.contribuyenteId());
        }
        return List.copyOf(identificadores);
    }
}
