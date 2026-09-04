package kamayuk.rentas.rentas.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las declaraciones juradas. Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p><b>No hay {@code eliminar}.</b> {@link #insertar} es el unico punto de alta; {@link #marcar}
 * es el unico {@code UPDATE}, y toca solo {@code estado} —nunca el numero, la fecha ni el tipo—.
 * Una rectificatoria es otra fila, nunca una edicion (regla 4). Desde V54 eso no depende de que
 * esta clase se acuerde: {@code sgtm_app} tiene el privilegio de {@code UPDATE} sobre la columna
 * {@code estado} y sobre ninguna otra.
 */
public interface DeclaracionJuradaRepository {

    Optional<DeclaracionJurada> findById(long id);

    /** Por numero y ejercicio, que es como la busca quien atiende (contrato de {@code djNro}). */
    Optional<DeclaracionJurada> porNumero(String numero, Ejercicio ejercicio);

    /**
     * Las declaraciones presentadas por un contribuyente, de la mas reciente a la mas antigua,
     * paginadas (#25, RF-046).
     *
     * <p>Existe para la pestaña de declaraciones juradas de la consulta unificada, que es la unica
     * pantalla que pregunta «que ha declarado esta persona» en vez de «que dice la declaracion
     * numero tal». Se apoya en {@code dj_contribuyente_ix} (V2), que ya indexa exactamente esta
     * pregunta.
     *
     * <p>Trae <b>todas</b>, incluidas las {@code SUSTITUIDA}s por una rectificatoria: una
     * declaracion sustituida no desaparece del expediente, y esconderla dejaria sin explicar por
     * que la vigente dice lo que dice. Cada fila lleva su estado.
     */
    Pagina<DeclaracionJurada> deContribuyente(long contribuyenteId, Paginacion paginacion);

    /**
     * Las declaraciones <b>vigentes</b> de esos predios en ese ejercicio, para {@link
     * kamayuk.rentas.rentas.DeclaracionesDelEjercicio} (#49, RF-055).
     *
     * <p>Vigente es {@code PRESENTADA} u {@code OBSERVADA}: una {@code SUSTITUIDA} por una
     * rectificatoria no es lo que el contribuyente declara hoy, y compararla contra lo hallado
     * acusaria de subvaluacion a quien ya corrigio. Una {@code ANULADA} tampoco cuenta.
     *
     * <p>Por lote y no una por una: la deteccion de omisos recorre paginas del padron, y preguntar
     * predio a predio seria una consulta por fila. Se apoya en {@code dj_ejercicio_predio_ix}
     * (V39), que indexa exactamente esta pregunta.
     *
     * <p>Los predios sin declaracion no aparecen en la lista devuelta.
     */
    java.util.List<DeclaracionJurada> vigentesDePredios(
            java.util.Collection<Long> predioIds, Ejercicio ejercicio);

    /**
     * Cuales de esos predios estan <b>conciliados</b> con el padron de rentas en ese ejercicio
     * (ADR-0015 §1, #344).
     *
     * <p>El predicado, entero: un predio esta conciliado a un ejercicio cuando existe una
     * declaracion jurada de ese ejercicio, con {@code predio_id} igual al del predio, en estado
     * {@code PRESENTADA} u {@code OBSERVADA} ({@link EstadoDeDeclaracion#nombresDeLasVigentes()}).
     *
     * <p><b>Por {@code predio_id}, nunca por {@code ficha_catastral_id}.</b> La segunda columna
     * (V19) es <i>nullable</i> por diseño —«nulo si el predio no tiene ficha registrada todavia, o
     * si el tipo no es predial»—, su clave foranea va {@code NOT VALID} y toda fila anterior a V19
     * la tiene nula: derivar de ella produce <b>falsos omisos</b>, o sea acusar de omiso a quien
     * declaro. {@code ficha_catastral_id} contesta otra pregunta —«que version declaro»—, que es
     * detalle de la declaracion y no predicado de pertenencia al padron afecto.
     *
     * <p>Devuelve <b>identificadores de predio</b> y no declaraciones: quien pregunta por la
     * conciliacion no tiene por que recibir el numero de la DJ, su tipo, sus importes ni quien la
     * presento (ADR-0015 §2.2). Y devuelve un conjunto, asi que un predio con dos declaraciones
     * vigentes —o una sustituida y su rectificatoria— aparece <b>una vez</b>, no dos.
     *
     * <p>Los predios sin declaracion no aparecen en el conjunto devuelto.
     */
    java.util.Set<Long> prediosConDeclaracionVigente(
            java.util.Collection<Long> predioIds, Ejercicio ejercicio);

    /**
     * <b>Todos</b> los predios con declaracion vigente del ejercicio, sin acotar por una lista
     * (#631).
     *
     * <p>Es el conjunto que la grilla de conciliacion le pasa a catastro para que <b>pagine y
     * cuente lo filtrado</b>: hasta #631 el filtro se aplicaba sobre la pagina ya devuelta y {@code
     * totalElementos} seguia siendo el del padron entero —«722 paginas, 14 422 elementos» y cero
     * filas en todas—.
     *
     * <p>Con el mismo predicado que su hermana, y por el mismo motivo que aquella lo toma de {@link
     * EstadoDeDeclaracion#nombresDeLasVigentes()}: dos copias del estado que hace vigente una
     * declaracion divergen, y la que se lee en la grilla seria la que nadie compara.
     */
    java.util.Set<Long> prediosConDeclaracionVigente(Ejercicio ejercicio);

    /**
     * El siguiente correlativo del ejercicio, ya reservado (#365).
     *
     * <p>Se lee y se incrementa en <b>una</b> sentencia, nunca con {@code SELECT} y despues {@code
     * UPDATE}: diez peticiones simultaneas leerian las diez el mismo numero. La atomicidad es la
     * fuente de la no-repeticion; {@code dj_numero_uq} (V54) es la red que la respalda.
     *
     * <p>La primera peticion de un ejercicio arranca <b>por encima del mayor numero historico de
     * ese año</b>: el correlativo no se siembra en la migracion porque el migrador no tiene
     * contexto de tenant y {@code declaracion_jurada} tiene RLS con {@code FORCE} (DAT-01 §0,
     * cuarto hallazgo).
     *
     * <p>Devuelve el correlativo <b>desnudo</b>. Componer el numero es cosa de {@link
     * PlantillaDeNumeroDeDeclaracion}, para que el dia que D-09 cambie el formato el correlativo
     * siga siendo el mismo.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /** Inserta la declaracion y devuelve la fila guardada, con su {@code id} y su usuario. */
    DeclaracionJurada insertar(DeclaracionJurada declaracion);

    /**
     * Dobla la fila al estado indicado: el unico {@code UPDATE}, y solo toca {@code estado}.
     *
     * <p>Quien llama ya paso por la maquina de estados del dominio —{@code sustituida()}, {@code
     * observada()}, {@code anulada()}—, que es quien decide si el acto es legal. Este metodo
     * escribe; no juzga. El disparador de V54 es el que juzga cuando dos peticiones simultaneas
     * leyeron las dos el mismo estado anterior.
     */
    DeclaracionJurada marcar(long id, EstadoDeDeclaracion nuevo);
}
