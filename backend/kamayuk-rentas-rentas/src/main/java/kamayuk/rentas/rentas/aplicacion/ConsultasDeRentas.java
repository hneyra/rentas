package kamayuk.rentas.rentas.aplicacion;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.rentas.dominio.Beneficio;
import kamayuk.rentas.rentas.dominio.BeneficioRepository;
import kamayuk.rentas.rentas.dominio.CriterioDeBeneficio;
import kamayuk.rentas.rentas.dominio.TransferenciaRepository;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecturas del modulo que ninguna clase de aplicacion cubria, cada una <b>dentro de su
 * transaccion</b> (#486).
 *
 * <p>Tres controladores llamaban al repositorio <b>directamente</b>, y ningun {@code
 * RepositoryJdbc} anota {@code @Transactional} —ni tiene por que: la transaccion es del caso de
 * uso—. Sin ella no se emite el {@code SET LOCAL app.municipalidad_id} y la politica RLS de estas
 * tablas lo consulta: la peticion <b>revienta</b> con «invalid input syntax for type bigint: ""»,
 * no devuelve vacio.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2).
 */
@Service
public class ConsultasDeRentas {

    private final CuotaDeArbitrioRepository arbitrios;
    private final BeneficioRepository beneficios;
    private final TransferenciaRepository transferencias;
    private final kamayuk.rentas.rentas.dominio.DeclaracionJuradaRepository declaraciones;

    public ConsultasDeRentas(
            CuotaDeArbitrioRepository arbitrios,
            BeneficioRepository beneficios,
            TransferenciaRepository transferencias,
            kamayuk.rentas.rentas.dominio.DeclaracionJuradaRepository declaraciones) {
        this.arbitrios = arbitrios;
        this.beneficios = beneficios;
        this.transferencias = transferencias;
        this.declaraciones = declaraciones;
    }

    /** Las cuotas de arbitrios que pide el criterio. */
    @Transactional(readOnly = true)
    public Pagina<CuotaDeArbitrio> arbitrios(CriterioDeArbitrio criterio, Paginacion paginacion) {
        return arbitrios.buscar(criterio, paginacion);
    }

    /** Las campanas de beneficio que pide el criterio. */
    @Transactional(readOnly = true)
    public Pagina<Beneficio> beneficios(CriterioDeBeneficio criterio, Paginacion paginacion) {
        return beneficios.buscar(criterio, paginacion);
    }

    /** La declaracion jurada por su numero y ejercicio. */
    @Transactional(readOnly = true)
    public Optional<kamayuk.rentas.rentas.dominio.DeclaracionJurada> declaracionPorNumero(
            String numero, kamayuk.rentas.dominio.Ejercicio ejercicio) {
        return declaraciones.porNumero(numero, ejercicio);
    }

    /**
     * El identificador del contribuyente por su codigo.
     *
     * <p>Vacio no es una peticion mal formada: es un padron sin ese contribuyente. Es la consulta
     * que mas engana de todas —una sola fila, hecha «de paso» antes de componer el criterio—, y
     * fuera de transaccion falla igual que la mas grande.
     */
    @Transactional(readOnly = true)
    public Optional<Long> contribuyentePorCodigo(String codigo) {
        return transferencias.contribuyentePorCodigo(codigo);
    }
}
