package kamayuk.rentas.tesoreria.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Las ventanillas de la municipalidad. Ningun metodo recibe la municipalidad (regla 2): la filtra
 * la politica RLS con el valor que {@code SET LOCAL} fijo al abrir la transaccion.
 */
public interface CajaRepository {

    /** La caja con ese codigo, si existe en esta municipalidad. */
    Optional<Caja> porCodigo(String codigo);

    /**
     * El catalogo de ventanillas de esta municipalidad, paginado (#618).
     *
     * <p>Hasta aqui este puerto solo sabia resolver <b>una</b> caja de la que ya se supiera el
     * codigo o el identificador, y no habia ninguna forma de <b>enumerarlas</b>. El efecto no era
     * teorico: cinco pantallas de Tesoreria piden el codigo de la caja antes de poder pedir nada
     * —dos en el cuerpo del cobro, dos para resolver el turno y una como filtro—, asi que quien
     * atiende tenia que saberselo de memoria.
     *
     * <p>Devuelve <b>todas</b>, activas y dadas de baja: por que, en {@link CajaEnConsulta}.
     */
    Pagina<CajaEnConsulta> listar(Paginacion paginacion);

    /**
     * La caja con ese identificador, si existe en esta municipalidad.
     *
     * <p>La necesita quien parte de un recibo y no de un codigo tecleado: el duplicado y la
     * anulacion (#34) llegan con el numero impreso y de ahi salen identificadores, no codigos.
     */
    Optional<Caja> porId(long id);

    /**
     * Da de alta la ventanilla y devuelve la fila guardada, con su identificador.
     *
     * <p>No hay {@code UPDATE} ni {@code DELETE}: una caja que ya no se usa se da de baja con su
     * columna {@code activa} (RNF-051), y eso es trabajo de la pantalla que el manual no dibuja. Lo
     * que hacia falta era poder <b>crearla</b>: sin una sola caja, {@code AbrirCaja} falla con
     * {@code CajaInexistente} y una instalacion recien implantada no puede cobrar (#430).
     */
    Caja insertar(Caja caja);
}
