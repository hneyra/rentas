package kamayuk.rentas.catastro;

import java.time.LocalDate;

/**
 * El riesgo del suelo y el ITSE de un predio (`catastro`#5).
 *
 * <h2>Un puerto con dos metodos, y no dos puertos</h2>
 *
 * <p>Son dos preguntas y dos operaciones HTTP, pero <b>un solo motivo para preguntarlas</b>: quien
 * evalua una licencia de funcionamiento necesita las dos a la vez —si el lote esta sobre riesgo no
 * mitigable no hay certificado que valga, y si lo esta sobre riesgo mitigable el certificado es
 * justo lo que decide—. Partirlo en dos puertos dejaria que un invocador pidiera una y no la otra,
 * que es la manera de autorizar sobre media respuesta. Es la misma forma que {@link
 * TitularesDelPredio}, cuyo unico puerto sirve tres operaciones.
 *
 * <h2>Publica hechos y ninguna consecuencia</h2>
 *
 * <p>Dice que zonas de riesgo cruzan el lote, si alguna es no mitigable, que fajas marginales lo
 * cruzan y que certificados ITSE estaban vigentes a una fecha. <b>No</b> dice si procede una
 * licencia: eso depende ademas del giro y del riesgo que la actividad exige, que son datos de este
 * sistema ({@code ciiu.riesgo_itse}). La decision es de {@code rentas} y el hecho es de {@code
 * catastro} (ADR-0024).
 *
 * <p><b>Ni un importe.</b> Un nivel de riesgo, un ancho de faja en metros y unas fechas de
 * vigencia. Lo que cueste el certificado es una tasa, y las tasas son de aqui.
 */
public interface RiesgoYItseDelPredio {

    /**
     * Las zonas de riesgo y las fajas marginales que cruzan el lote.
     *
     * <p><b>No recibe fecha, y hay que decir por que</b>: {@code catastro} no la admite en esta
     * operacion —resuelve con su propio reloj— y la respuesta trae dentro la que uso. De modo que
     * este puerto <b>no puede preguntar por el riesgo de un dia pasado</b>, y eso esta declarado y
     * no cerrado: cerrarlo es publicar el parametro, y eso es del dueno de {@code grd}. Lo que si
     * se conserva es la regla 9, porque {@link RiesgoDelPredio#aLaFecha()} dice con que dia se
     * contesto.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro si
     *     el predio no esta en el padron, o esta y no tiene poligono. Sin poligono la respuesta
     *     correcta no existe: «cero zonas» se leeria como «no cae en ninguna» y acabaria
     *     autorizando lo que no debe
     */
    RiesgoDelPredio riesgoDe(long predioId);

    /**
     * Los certificados ITSE vigentes a una fecha. Ninguno vencido sale.
     *
     * <p>La lista vacia <b>si</b> es un dato aqui, al reves que en {@link #riesgoDe}: un
     * certificado cuelga del predio y no de su plano, asi que «ninguno vigente ese dia» es verdad
     * aunque el lote no este levantado.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro si
     *     el predio no esta en el padron de esta municipalidad
     */
    ItseDelPredio itseVigenteEn(long predioId, LocalDate aLaFecha);
}
