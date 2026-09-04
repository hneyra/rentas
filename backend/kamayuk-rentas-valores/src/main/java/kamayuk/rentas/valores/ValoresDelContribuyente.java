package kamayuk.rentas.valores;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Los valores emitidos a un contribuyente, publicados para otros contextos acotados (ARQ-01 §4,
 * #25, RF-046).
 *
 * <p>Es la <b>primera</b> API publica de {@code valores}. Vive en el paquete raiz, no en {@code
 * .aplicacion} ni en {@code .dominio}, por el mismo motivo que {@code
 * cuentacorriente.ConsultaDeDeudaPublica}: Spring Modulith trata como interno todo lo que esta en
 * un subpaquete, asi que un {@code import} desde otro contexto de {@code valores.dominio.Valor} no
 * pasa la verificacion. Esto es exactamente lo que {@code rentas} puede ver de los valores. Sus
 * tablas, no.
 *
 * <h2>Solo lectura</h2>
 *
 * <p>Emitir, notificar, prescribir y pasar a coactiva son actos con su numeracion, su acuse y su
 * observacion, y viven en {@code RegistrarValor}, {@code RegistrarNotificacion}, {@code
 * DeclararPrescripcion} y {@code PasarACoactiva}. Aqui solo se lee.
 *
 * <h2>Por identificador y no por codigo</h2>
 *
 * <p>Al reves que {@code tesoreria.ConveniosDelContribuyente}, que pide el codigo: {@code
 * CriterioDeConsultaDeValores} filtra por identificador porque quien arma el criterio ya lo
 * resolvio contra {@code DirectorioDeContribuyentes}. Cada puerto pide la clave con la que su
 * propia consulta ya resuelve; quien llama tiene las dos de una sola lectura del padron, y traducir
 * aqui costaria una consulta de mas por seccion.
 */
public interface ValoresDelContribuyente {

    /**
     * Los valores emitidos a ese contribuyente, con su situacion mirada a esa fecha, paginados.
     *
     * <p>Todos los tipos y todos los ejercicios: filtrar por «Tipo de valor» o por «Año» es la
     * pantalla {@code consulta_valores}, que vive en este contexto. La ficha unificada dibuja lo
     * que el contribuyente tiene.
     *
     * <p>Vacia si no se le ha emitido ninguno.
     *
     * @param contribuyenteId a quien se le emitieron
     * @param aLaFecha desde que dia se mira la situacion de cada uno (regla 9): sin ella «exigible»
     *     no significa nada. <b>No</b> es la fecha de los importes, que vienen congelados con la
     *     suya
     */
    Pagina<ValorDelContribuyente> deTodoElContribuyente(
            long contribuyenteId, LocalDate aLaFecha, Paginacion paginacion);
}
