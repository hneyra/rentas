package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Los expedientes coactivos y los valores que agrupan (V33, #40).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): la filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V33 le retira a {@code
 * kamayuk_app} el privilegio de {@code UPDATE} sobre {@code expediente_coactivo} y sobre {@code
 * expediente_valor}, y V7 nunca le dio {@code DELETE}. Lo que le pasa a un expediente se agrega, en
 * {@code expediente_movimiento}.
 */
public interface ExpedienteRepository {

    /**
     * Abre el expediente.
     *
     * @param expediente el expediente a guardar; {@link ExpedienteCoactivo#esNuevo()} tiene que ser
     *     verdadero
     * @return el mismo expediente, con su {@code id} asignado
     */
    ExpedienteCoactivo abrir(ExpedienteCoactivo expediente);

    /**
     * El siguiente correlativo de ese ejercicio, unico y sin huecos bajo concurrencia real.
     *
     * <p>Lo garantiza un {@code UPDATE} atomico contra {@code expediente_correlativo}, no una
     * lectura seguida de una escritura desde Java. D-09 decide el formato del numero final —con que
     * ceros, si se reinicia—; lo que aqui se garantiza es que la secuencia no se repita ni salte, y
     * eso lo garantiza la base.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    Optional<ExpedienteCoactivo> porNumero(String numero);

    Optional<ExpedienteCoactivo> porId(long id);

    /**
     * Mete el valor en el expediente.
     *
     * @throws ValorYaEnUnExpediente si ese valor ya vive en un expediente. <b>Lo decide la base</b>
     *     —{@code expediente_valor_unico_uq}, V33— y no un {@code SELECT} previo: dos peticiones
     *     simultaneas pasarian las dos por cualquier comprobacion escrita en Java, y el obligado
     *     acabaria con dos procedimientos por la misma deuda
     */
    ValorDelExpediente importar(long expedienteId, long valorId, LocalDate fechaImportacion);

    /** Los valores del expediente, en el orden en que entraron. */
    List<ValorDelExpediente> valoresDe(long expedienteId);

    /**
     * Cuales de esos valores ya viven en algun expediente.
     *
     * <p>Es lo que permite <b>explicar</b> el rechazo antes de intentar la insercion, no lo que lo
     * impide: el que lo impide es el indice unico. Sin esta consulta el informe diria «choque de
     * clave unica» donde tiene que decir «ya esta en un expediente».
     */
    Set<Long> yaEnUnExpediente(Collection<Long> valorIds);

    /**
     * La grilla de {@code coactiva_expedientes} (RF-100): la cabecera de cada expediente con lo que
     * la pantalla muestra y la cabecera no guarda.
     *
     * <p><b>El filtro por estado se resuelve en SQL, no despues de paginar</b>, con la misma regla
     * que {@link EstadoDelExpediente#delHistorial}: el ultimo movimiento que lleve estado.
     */
    Pagina<ExpedienteEnConsulta> consultar(CriterioDeExpedientes criterio, Paginacion paginacion);

    /**
     * Cuantos expedientes cumplen ese criterio, <b>sin traerse ninguno</b> (#549).
     *
     * <p>Es el {@code count(*)} que {@link #consultar} ya ejecuta para poder paginar, con el mismo
     * {@code FROM} y el mismo {@code WHERE} —incluida la derivacion del estado desde el ultimo
     * movimiento, que no es una columna—. Tenerlo aqui y no escrito aparte es lo que impide que
     * «sin REC-1» signifique una cosa en la grilla del modulo y otra en el panel de la pantalla de
     * aterrizaje (AC 2.4 de #549).
     */
    long contar(CriterioDeExpedientes criterio);

    /**
     * Cuantos expedientes hay <b>en cada etapa</b>, sin traerse ninguno (#272).
     *
     * <p>Es el mismo {@code FROM} y el mismo {@code WHERE} de {@link #consultar} y {@link #contar}
     * —la derivacion del estado desde el ultimo movimiento incluida— con un {@code GROUP BY} por
     * encima. Que sea el mismo es lo que impide que «sin REC» signifique una cosa en la grilla del
     * modulo, otra en el panel de aterrizaje y una tercera en el panel del propio modulo: son tres
     * lectores de la misma derivacion, no tres copias suyas.
     *
     * <p><b>Una sola consulta.</b> Siete {@code count(*)} —uno por etapa— darian las mismas cifras
     * y no serian el mismo instante: entre el primero y el septimo cabe un cambio de estado, y el
     * resumen saldria sin cuadrar consigo mismo.
     *
     * @return las etapas que tienen al menos un expediente; las demas no aparecen, y es {@link
     *     ResumenDeLaCartera} quien las completa con cero
     */
    Map<EstadoDelExpediente, Long> contarPorEstado(CriterioDeExpedientes criterio);

    /** Ese valor ya estaba en un expediente coactivo. */
    final class ValorYaEnUnExpediente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ValorYaEnUnExpediente(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
