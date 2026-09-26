package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El expediente del FUE y sus cinco secciones (#48, RF-113). Ningun metodo recibe la municipalidad
 * (regla 2): la filtra la politica RLS con el valor que {@code SET LOCAL} fijo al abrir la
 * transaccion.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V43 le retira a {@code
 * kamayuk_app} el privilegio de {@code UPDATE} sobre {@code licencia_edificacion} y no se lo
 * concede a ninguna tabla de seccion; {@code DELETE} nunca lo tuvo (V7). Una seccion se corrige
 * <b>completando la siguiente version</b>, y el expediente se anula con un movimiento.
 */
public interface FueRepository {

    /**
     * El siguiente correlativo de licencia de edificacion del ejercicio, reservado.
     *
     * <p>Un {@code INSERT ... ON CONFLICT DO UPDATE SET ultimo = ultimo + 1} sobre {@code
     * edificacion_correlativo}: una sola sentencia, que bloquea la fila del contador mientras la
     * actualiza. Nunca un {@code SELECT} seguido de un {@code UPDATE}.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /** Da de alta el expediente. Devuelve el FUE con su identificador. */
    FueDeEdificacion presentar(FueDeEdificacion fue);

    /** El expediente con ese numero. */
    Optional<FueDeEdificacion> porExpediente(String expediente);

    /** El expediente por su identificador interno; lo necesita la ampliacion para su original. */
    Optional<FueDeEdificacion> porId(long fueId);

    /**
     * Toma el candado del expediente hasta el fin de la transaccion (#427).
     *
     * <p>Es la frontera de consistencia de los dos numeros que dependen de el: la {@code version}
     * de una seccion y el {@code orden} del tramo de vigencia que una revalidacion le concede. Se
     * toma <b>antes de la primera lectura en que se apoya la decision</b> —si ya esta emitido, que
     * tramos tiene—, no justo antes del {@code INSERT}: la segunda peticion tiene que decidir con
     * lo que la primera dejo, no solo numerar despues de ella. No es un {@code FOR UPDATE}: V43 le
     * retiro a {@code kamayuk_app} el {@code UPDATE} sobre {@code licencia_edificacion}, y
     * PostgreSQL lo exige para bloquear una fila.
     */
    void bloquear(long fueId);

    /** El expediente cuya emision otorgo ese numero de licencia. */
    Optional<FueDeEdificacion> porNumeroDeLicencia(String numeroDeLicencia);

    /**
     * La grilla, paginada; con {@code estado}, solo los FUE que a esa fecha estan en ese estado.
     *
     * <p>El estado se filtra <b>aqui</b>, antes de paginar, con la misma tabla con que {@link
     * EstadoDelFue#derivarDe} lo deriva (#425). Filtrarlo despues sobre la pagina ya cortada daba
     * una pagina corta y un total que no era el de la relacion.
     *
     * @param estado el estado y su fecha; {@code null} si no se filtra por estado
     */
    Pagina<FueDeEdificacion> buscar(
            CriterioDeFue criterio, @Nullable EstadoALaFecha estado, Paginacion paginacion);

    // ---------- Secciones ----------

    /**
     * Guarda la siguiente version de una seccion.
     *
     * <p>La version la calcula el repositorio: {@code ultima + 1}. Y quien llama tiene que haber
     * tomado antes {@link #bloquear}: sin el, dos peticiones simultaneas elegirian la misma y la
     * segunda chocaria en {@code edificacion_*_uq} con un error que no dice que paso (#427).
     */
    TerrenoDelFue guardarTerreno(TerrenoDelFue terreno);

    ProyectoDelFue guardarProyecto(ProyectoDelFue proyecto);

    /** Guarda la valorizacion completa como una version nueva, con todas sus lineas. */
    List<EstructuraDelProyecto> guardarValorizacion(
            long fueId, List<EstructuraDelProyecto> estructuras);

    List<ProfesionalDelFue> guardarProfesionales(long fueId, List<ProfesionalDelFue> profesionales);

    List<RequisitoDelFue> guardarRequisitos(long fueId, List<RequisitoDelFue> requisitos);

    // ---------- Lectura de la version vigente de cada seccion ----------

    Optional<TerrenoDelFue> terrenoVigente(long fueId);

    Optional<ProyectoDelFue> proyectoVigente(long fueId);

    List<EstructuraDelProyecto> valorizacionVigente(long fueId);

    List<ProfesionalDelFue> profesionalesVigentes(long fueId);

    List<RequisitoDelFue> requisitosVigentes(long fueId);

    /**
     * Los terrenos vigentes de varios expedientes, en una consulta.
     *
     * <p>La grilla pinta la manzana y el lote de cada fila; con una lectura por expediente, una
     * pagina de veinte costaria veintiuna consultas, y eso no se nota en la prueba y si en el
     * padron de una provincia.
     */
    Map<Long, TerrenoDelFue> terrenosDe(Set<Long> fueIds);

    /** Los proyectos vigentes de varios expedientes, en una consulta. Los pide el reporte. */
    Map<Long, ProyectoDelFue> proyectosDe(Set<Long> fueIds);

    /**
     * Las valorizaciones vigentes de varios expedientes, en una consulta.
     *
     * <p>El reporte general pinta el valor de obra de cada fila. Leerlas de una en una convertiria
     * una pagina de veinte en veintiuna consultas, y ademas obligaria a pedir el cuadro de valores
     * unitarios veinte veces —que es peor, porque cada lectura resuelve el conjunto sellado—.
     */
    Map<Long, List<EstructuraDelProyecto>> valorizacionesDe(Set<Long> fueIds);

    /** Ese expediente ya existe. Lo decide {@code edificacion_expediente_uq}, no un SELECT. */
    final class ExpedienteDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ExpedienteDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
