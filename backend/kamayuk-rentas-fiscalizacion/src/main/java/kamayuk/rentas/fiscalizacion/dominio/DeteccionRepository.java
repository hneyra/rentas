package kamayuk.rentas.fiscalizacion.dominio;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * El cruce del padrón de predios con las declaraciones juradas de un ejercicio ({@code
 * fisc_omisos}, RF-055), resuelto <b>en una consulta</b>.
 *
 * <h2>Por qué esto es un repositorio y no una composición de puertos</h2>
 *
 * <p>Hasta #545 la detección se componía en Java: {@code catastro.PadronDePredios} traía una página
 * del padrón y {@code rentas.DeclaracionesDelEjercicio} decía cuáles de esos predios habían
 * declarado. Esa forma no puede filtrar por condición, y no por descuido: la condición <b>se
 * deriva</b> del cruce, así que sólo se conoce después de traer la página, y filtrar entonces
 * descarta filas ya paginadas —el sobre sigue contando el conjunto sin filtrar y la respuesta dice
 * «cero de veinticinco»—.
 *
 * <p>Un derivado que hay que poder filtrar se escribe <b>una sola vez y en SQL</b>, con la misma
 * expresión en el {@code SELECT} y en el {@code WHERE}. Es exactamente lo que #397 decidió para el
 * «Estado» de la infracción administrativa, y por el mismo motivo: dos copias del {@code CASE}
 * divergen, y la que se lee en pantalla acaba no siendo la que filtró.
 *
 * <h2>Y por qué esa consulta vive en {@code fiscalizacion}</h2>
 *
 * <p>Porque el derivado es {@link CondicionFiscalizada}, que es vocabulario de este contexto y de
 * ningún otro.
 *
 * <ul>
 *   <li>En {@code catastro} no puede: tendría que leer {@code declaracion_jurada}, que es de {@code
 *       rentas}, y {@code rentas} ya depende de {@code catastro} —es el ciclo que el javadoc de
 *       {@code ConsultaDeConciliacion} ya dejó descartado por escrito—.
 *   <li>En {@code rentas} tampoco: la condición no es suya, así que la única forma de alojarla allí
 *       sería inventarle un vocabulario neutro y traducirlo aquí, y esa traducción es una segunda
 *       copia de la regla — justo lo que se está evitando.
 *   <li>Queda {@code fiscalizacion}, que ya depende de los dos (ARQ-01 §2) y cuyo tipo de dominio
 *       —{@link FilaDeOmisos}— es lo que la consulta produce. Es la misma decisión que #366 tomó al
 *       revés: allí el endpoint se mudó al módulo que ya dependía de los dos lados.
 * </ul>
 *
 * <p><b>Lo que eso cuesta, dicho:</b> la implementación lee cuatro tablas ajenas —{@code predio},
 * {@code sector} y {@code ficha_catastral} de catastro, {@code declaracion_jurada} de rentas—. Las
 * cuatro comparten la misma política RLS y las cuatro se leen y no se escriben; la frontera de
 * escritura de este contexto sigue siendo la única que era, {@code TransferirARentas}. Lo que
 * sostiene que la transcripción SQL de la regla no se separe de {@link ComparacionHalladoDeclarado}
 * es una prueba que las compara caso por caso, no la buena voluntad de quien la escribió.
 *
 * <p><b>Los titulares no entran en esa consulta</b>: salen del puerto público {@code
 * catastro.TitularesDelPredio}, en una lectura por página. Existiendo el puerto, leer {@code
 * titularidad} aquí sería cruzar la frontera sin necesidad.
 */
public interface DeteccionRepository {

    /**
     * La página de predios detectados, con el filtro de condición ya aplicado al conjunto.
     *
     * <p>{@code totalElementos} cuenta <b>lo filtrado</b>: si la condición pedida no tiene ninguna
     * fila, el sobre dice cero y no veinticinco.
     *
     * <p>Las filas salen <b>sin titulares</b>: los resuelve {@code DeteccionDeOmisos} con una sola
     * lectura por página. Un predio sin titular vigente sale igual, y ése es el punto: un predio
     * que nadie reclama es exactamente el que hay que fiscalizar.
     */
    Pagina<FilaDeOmisos> detectar(CriterioDeDeteccion criterio, Paginacion paginacion);
}
