package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Quien es titular de un predio a una fecha, publicado para otros contextos acotados (ADR-0015
 * §2.4, #366).
 *
 * <p>Vive en el paquete raiz por lo mismo que los demas puertos de este modulo: Spring Modulith
 * trata como interno todo lo que esta en un subpaquete.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para que la fila de la consulta de fichas pueda <b>enlazar</b> con la ficha de su titular. La
 * grilla muestra el nombre y nada mas —{@link FichaDelPadron} no lleva identificador a proposito—,
 * asi que hoy el operador salta al padron y busca por nombre, con la homonimia que eso invita. La
 * resolucion se hace al clic, de un predio cada vez, con permiso del padron y con rastro: es la
 * opcion (b) de #366, y ADR-0015 §2.4 dice por que no es un campo mas del listado.
 *
 * <p>Este puerto es la mitad de catastro de esa resolucion. La otra mitad —el codigo y el nombre
 * del contribuyente— es de {@code contribuyentes}, y quien las junta es {@code rentas}: es el unico
 * de los tres que puede depender de los otros dos sin cerrar un ciclo, porque {@code catastro} ya
 * depende de {@code contribuyentes} y {@code contribuyentes} no depende de nadie (ARQ-01 §2). El
 * patron es el de {@code ConsultaPrediosController} y el de {@code ConsultaDeConciliacion}.
 *
 * <p>Es la inversa de {@link PrediosDelContribuyente}, que responde «que predios tiene esta
 * persona»: puertos pequenos, uno por pregunta.
 *
 * <h2>Lo que este puerto no da</h2>
 *
 * <p>No da la titularidad entera —ni sus fechas, ni el documento que la sustenta, ni el
 * identificador de la fila, que es lo que {@link GestorDeTitularidad} publica para quien transfiere
 * y aqui no hace falta—, no da nada del predio y no escribe.
 */
public interface TitularesDelPredio {

    /**
     * Las cuotas de titularidad <b>vigentes en esa fecha</b>. Vacia si el predio no tiene titular a
     * esa fecha.
     *
     * <p>La fecha entra como argumento y no se lee del reloj (regla 9): la titularidad de marzo no
     * es la de setiembre, y atender en 2029 una reclamacion de 2027 con el titular de hoy senala a
     * quien ya no era propietario.
     *
     * <p><b>Son varias, no una.</b> Un predio puede tener varios titulares a la vez —dos conyuges,
     * una sucesion, un condominio—, cada uno con su porcentaje, y devolver «el titular» obligaria a
     * elegir uno y a callar los demas. Los porcentajes vigentes no exceden 100 pero tampoco tienen
     * que sumarlo: la titularidad parcialmente identificada es un caso corriente del padron (DAT-01
     * §4.2).
     *
     * <p>Una lista vacia <b>no distingue</b> «el predio no existe» de «no tiene titular a esa
     * fecha». Quien necesite separarlos lo pregunta con {@link #estaEnElPadron(long)}, que es una
     * pregunta distinta y contesta lo mismo para un predio inventado y para uno de otra
     * municipalidad — de modo que separarlos no convierte esta lectura en un detector de predios
     * ajenos.
     */
    List<TitularDelPredio> de(long predioId, LocalDate fecha);

    /**
     * Si ese identificador es una fila del padron de predios de esta municipalidad (#680).
     *
     * <h2>Por que vive aqui y no en un puerto propio</h2>
     *
     * <p>Aunque «¿de quien es?» y «¿existe?» sean dos preguntas —y este modulo prefiera puertos
     * pequenos, uno por pregunta—, esta <b>solo existe para interpretar la respuesta vacia de
     * {@link #de(long, LocalDate)}</b>. Separarlas en dos puertos dejaria que dos implementaciones
     * distintas contestaran sobre el mismo predio y se contradijeran —«no tiene titular» y «no
     * existe» a la vez—, que es exactamente la respuesta incoherente que #680 existe para hacer
     * imposible.
     *
     * <h2>Lo que no distingue, a proposito</h2>
     *
     * <p>Un identificador inventado y el de un predio de otra municipalidad contestan lo mismo:
     * {@code false}. Bajo RLS el segundo no es una fila de este padron, y contestar distinto seria
     * publicar que existe en algun sitio.
     *
     * <p><b>Un predio dado de baja sigue estando en el padron.</b> Aqui no se pregunta si esta
     * activo sino si el identificador apunta a una fila: su deuda existe, se lee y se tiene que
     * poder mover, y confundir «de baja» con «no existe» dejaria esa deuda sin forma de corregirse
     * —el mismo defecto que #660 midio para el otro lado del movimiento—.
     *
     * <p>No lleva fecha, y es la unica lectura de este puerto que no la lleva: un predio no deja de
     * existir en una fecha. Lo que cambia con la fecha es de quien es, y eso lo contesta {@link
     * #de(long, LocalDate)}.
     */
    boolean estaEnElPadron(long predioId);

    /**
     * Lo mismo para un lote de predios, en una sola lectura (#545).
     *
     * <p>Existe por lo mismo que {@code DirectorioDeContribuyentes.porIds} existe al lado de {@code
     * porCodigo}: quien recorre un padron pagina a pagina —la deteccion de omisos de {@code
     * fisc_omisos}— necesita los titulares de las veinte filas que ya trajo, y preguntarlos con
     * {@link #de} en un bucle serian veinte consultas por pagina. Eso no se nota en una prueba y si
     * en el padron de una provincia.
     *
     * <p>Que este metodo exista <b>no reabre lo que ADR-0015 §2.4 cerro</b>: lo que aquel decidio
     * es que el identificador del titular no salga por HTTP en un <b>listado</b>, y esto no sale
     * por HTTP —es una lectura entre contextos, la misma que {@link #de} ya permitia—. Quien lo
     * publique despues sigue teniendo que decidir que enseña.
     *
     * <p>Un predio <b>sin titular vigente a esa fecha no aparece en el mapa</b>. No es lo mismo que
     * no existir, y quien pregunte tiene que tratar los dos casos igual: {@link #de} ya devuelve
     * lista vacia para los dos a proposito.
     */
    Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha);
}
