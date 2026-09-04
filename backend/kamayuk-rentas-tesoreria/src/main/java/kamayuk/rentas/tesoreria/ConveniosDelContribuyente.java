package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Los convenios de fraccionamiento de un contribuyente, publicados para otros contextos acotados
 * (ARQ-01 §4, #25, RF-046).
 *
 * <p>Es la <b>primera</b> API publica de {@code tesoreria}. Vive en el paquete raiz, no en {@code
 * .aplicacion} ni en {@code .dominio}, por el mismo motivo que {@code
 * cuentacorriente.ConsultaDeDeudaPublica} y {@code contribuyentes.DirectorioDeContribuyentes}:
 * Spring Modulith trata como interno todo lo que esta en un subpaquete, asi que un {@code import}
 * desde otro contexto de {@code tesoreria.dominio.Convenio} no pasa la verificacion. Esto es
 * exactamente lo que {@code rentas} puede ver de la caja. Sus tablas, no.
 *
 * <h2>Solo lectura, y solo del contribuyente</h2>
 *
 * <p>No hay aqui ni registrar, ni formalizar, ni quebrar: eso son actos con su acta, su recibo y su
 * observacion, y viven en {@code RegistrarPreconvenio}, {@code FormalizarConvenio} y {@code
 * CerrarConvenio}. Publicar una escritura seria abrir un segundo camino al convenio sin nada de
 * eso.
 *
 * <p>Tampoco hay «todos los convenios»: el listado completo es la pantalla {@code
 * consulta_convenios}, que vive en {@code tesoreria} y no necesita cruzar ningun limite. Lo que
 * este puerto responde es la pestaña «Fraccionamientos» de la ficha de <b>un</b> contribuyente.
 *
 * <h2>Ningun importe recalculado</h2>
 *
 * <p>Lo que sale es lo que {@code ConsultaDeConvenios} ya compone —la deuda acogida congelada y el
 * saldo del cronograma a la fecha—, no una cifra que este puerto vuelva a sumar. Quien consuma esto
 * no puede obtener un numero distinto del que da {@code GET /tesoreria/convenios}: es la misma
 * consulta.
 */
public interface ConveniosDelContribuyente {

    /**
     * Los convenios de ese contribuyente, mirados a esa fecha, paginados.
     *
     * <p>Vacia si el contribuyente no tiene ninguno, o si el codigo no existe en esta
     * municipalidad: un padron sin esa fila no es una peticion mal formada. Quien necesita
     * distinguir «no existe» de «no tiene» resuelve el contribuyente antes, contra {@code
     * DirectorioDeContribuyentes}, que es lo que hace la consulta unificada para poder responder
     * 404.
     *
     * @param codigoContribuyente el codigo del titular, tal como lo teclea la pantalla
     * @param aLaFecha la fecha con la que se responde lo que depende de hoy —cuantas cuotas han
     *     vencido y cuanto queda por cobrar—. Entra como argumento y no sale de un {@code now()}
     *     (regla 6, regla 9): dos filas de la misma pagina tienen que estar calculadas al mismo dia
     */
    Pagina<ConvenioDelContribuyente> deTodoElContribuyente(
            String codigoContribuyente, LocalDate aLaFecha, Paginacion paginacion);
}
