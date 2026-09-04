package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El listado de predios del catastro, con sus filtros.
 *
 * <p><b>Existe por la frontera transaccional, no por la logica</b>: el metodo no hace mas que
 * delegar, y lo que aporta es el {@code @Transactional(readOnly = true)} donde se fija el tenant.
 * Sin el, la consulta corre sin el {@code SET LOCAL app.municipalidad_id} que la politica RLS exige
 * y falla con «invalid input syntax for type bigint: ""». Es el mismo motivo por el que existe
 * {@link ConsultaDeVias}, que se descubrio cuando alguien con permiso llego por primera vez a
 * {@code GET /catastro/vias} y no antes.
 *
 * <p>No recibe el identificador de municipalidad (regla 2).
 */
@Service
public class ConsultaDePredios {

    private final CatastroRepository catastro;

    public ConsultaDePredios(CatastroRepository catastro) {
        this.catastro = catastro;
    }

    @Transactional(readOnly = true)
    public Pagina<PredioDelCatastro> buscar(FiltroDePredios filtro, Paginacion paginacion) {
        return catastro.predios(filtro, paginacion);
    }

    /**
     * Quien ocupa el predio a una fecha (#490, #31).
     *
     * <p><b>A una fecha, no «los ultimos»</b> (regla 9): quien ocupaba el predio en marzo no es
     * necesariamente quien lo ocupa hoy, y una determinacion de arbitrios de marzo se explica con
     * el de marzo.
     *
     * <p>Existe porque terminar una ocupacion exige decir cual, y ninguna lectura publicaba ese
     * identificador. Lleva su transaccion por lo mismo que {@link #buscar}: {@code inquilino} tiene
     * RLS, y fuera de transaccion no hay {@code SET LOCAL} que valga.
     */
    @Transactional(readOnly = true)
    public List<Inquilino> inquilinosDe(long predioId, LocalDate fecha) {
        return catastro.inquilinosDe(predioId, fecha);
    }
}
