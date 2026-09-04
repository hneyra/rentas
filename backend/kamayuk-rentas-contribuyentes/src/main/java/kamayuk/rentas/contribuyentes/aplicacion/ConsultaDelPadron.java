package kamayuk.rentas.contribuyentes.aplicacion;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura del padron: la opcion {@code contribuyentes} del contrato.
 *
 * <p>Existe por lo mismo que {@code ConsultaDeVias} en catastro, y por el mismo 500: una lectura
 * <b>fuera de transaccion</b> no emite el {@code SET LOCAL app.municipalidad_id}, y la politica RLS
 * de {@code contribuyente} consulta ese parametro. Sin el, la consulta no devuelve vacio —falla con
 * «invalid input syntax for type bigint: ""», porque el {@code ::bigint} de la cadena vacia
 * revienta—.
 *
 * <p>{@code ContribuyenteController} llamaba al repositorio directamente y {@code
 * ContribuyenteRepositoryJdbc} no anota ningun metodo, asi que {@code GET /rentas/contribuyentes}
 * contestaba <b>500</b> en la marcha blanca (#486). Ninguna prueba lo veia: las del modulo llaman
 * al repositorio dentro de una transaccion de prueba, y las de capa web lo sustituyen por un doble,
 * de modo que la frontera que falla —HTTP hasta PostgreSQL, sin nada transaccional por el camino—
 * no la cruzaba ninguna.
 *
 * <p>El {@code @Transactional(readOnly = true)} de este metodo es el que abre la transaccion donde
 * {@code TenantTransactionManager} fija el tenant.
 */
@Service
public class ConsultaDelPadron {

    private final ContribuyenteRepository padron;

    public ConsultaDelPadron(ContribuyenteRepository padron) {
        this.padron = padron;
    }

    /**
     * Los contribuyentes que cumplen el criterio, paginados.
     *
     * <p>El criterio lo compone el controlador —el contrato trae el DNI y el RUC como dos filtros
     * distintos, no como un tipo y un numero—, porque esa traduccion es de la forma de la peticion
     * y no del padron. Lo que <b>no</b> puede quedarse alli es la consulta.
     */
    @Transactional(readOnly = true)
    public Pagina<Contribuyente> buscar(CriterioDeBusqueda criterio, Paginacion paginacion) {
        return padron.buscar(criterio, paginacion);
    }

    /**
     * Uno solo, por su identificador.
     *
     * <p>Lo usa la edicion para leer lo que ya hay antes de cambiarlo, y lleva su propia
     * transaccion por lo mismo que {@link #buscar}: {@code findById} tambien consulta una tabla con
     * RLS, y fuera de transaccion no hay {@code SET LOCAL} que valga.
     *
     * <p>Vacio no distingue «no existe» de «es de otra municipalidad», y esa es la respuesta
     * correcta: la politica RLS ya hizo que las dos cosas sean la misma para quien pregunta.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Contribuyente> porId(long id) {
        return padron.findById(id);
    }
}
