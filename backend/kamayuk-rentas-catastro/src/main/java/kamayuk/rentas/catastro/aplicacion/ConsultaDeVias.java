package kamayuk.rentas.catastro.aplicacion;

import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura del catalogo vial: la opcion {@code calles} del contrato.
 *
 * <p>Existe por lo que {@code RepositorioJdbc} advierte y aqui costo un 500 en el despliegue: una
 * lectura <b>fuera de transaccion</b> no emite el {@code SET LOCAL app.municipalidad_id}, y la
 * politica RLS de {@code via} consulta ese parametro. Sin el, la consulta no devuelve vacio —falla
 * con «invalid input syntax for type bigint: ""»—. {@code ViaController} llamaba al repositorio
 * directamente, asi que {@code GET /catastro/vias} se rompia en cuanto alguien tenia permiso para
 * llegar a el; hasta entonces el guardia de acceso lo tapaba con un 403.
 *
 * <p>El {@code @Transactional(readOnly = true)} de este metodo es el que abre la transaccion donde
 * {@code TenantTransactionManager} fija el tenant. Es el mismo patron que {@code ConsultaDeFichas}
 * y el resto de lecturas del contexto.
 */
@Service
public class ConsultaDeVias {

    private final ViaRepository vias;

    public ConsultaDeVias(ViaRepository vias) {
        this.vias = vias;
    }

    @Transactional(readOnly = true)
    public Pagina<Via> listar(Paginacion paginacion) {
        return listar(CriterioDeVia.todas(), paginacion);
    }

    /**
     * El catalogo vial acotado por el criterio (#565).
     *
     * <p>Hasta aqui esta lectura recibia solo la paginacion, asi que elegir una via desde una
     * pantalla obligaba a traerse el catalogo entero —1 110 vias en Catacaos, tres peticiones de
     * 500— y buscar en el cliente.
     */
    @Transactional(readOnly = true)
    public Pagina<Via> listar(CriterioDeVia criterio, Paginacion paginacion) {
        return vias.buscar(criterio, paginacion);
    }

    /**
     * La via de un codigo, para que la edicion sepa que id esta tocando.
     *
     * <p>Lleva su propia transaccion por lo mismo que {@link #listar}: sin ella no hay {@code SET
     * LOCAL} y la politica RLS de {@code via} no tiene contexto de tenant.
     */
    @Transactional(readOnly = true)
    public Optional<Via> buscarPorCodigo(String codigo) {
        return vias.findByCodigo(codigo);
    }
}
