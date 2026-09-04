package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.CertificadoRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCertificados;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla «Certificados emitidos» de la opcion {@code certificados} (#54, RF-115).
 *
 * <h2>Lleva su propia transaccion, y hace falta</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} es lo que abre la transaccion donde {@code
 * TenantTransactionManager} emite el {@code SET LOCAL app.municipalidad_id} que las politicas RLS
 * consultan. Un controlador que llamara al repositorio directamente leeria sin contexto, y eso no
 * devuelve vacio: <b>falla</b>, con un mensaje que no se parece a su causa. Es el defecto que la
 * marcha blanca de seguridad destapo en {@code GET /catastro/vias}.
 *
 * <h2>«A la fecha», tambien aqui</h2>
 *
 * <p>Un certificado <b>caduca</b>, asi que su estado depende del dia al que se pregunte y la fecha
 * entra como argumento y viaja en la respuesta. Sin ella, una grilla impresa hoy y otra impresa la
 * semana que viene podrian contradecirse sin que ninguna dijera de cuando es (regla 9, RNF-075).
 */
@Service
public class ConsultaDeCertificados {

    /**
     * Cuantos contribuyentes se resuelven como mucho al filtrar por nombre.
     *
     * <p>El filtro busca por aproximacion, asi que «GARCIA» puede encontrar cientos. El tope evita
     * armar un {@code IN} de tamano ilimitado; quien busque un solicitante concreto escribe mas.
     */
    private static final int SOLICITANTES_MAXIMOS = 200;

    private final CertificadoRepository certificados;
    private final DirectorioDeContribuyentes contribuyentes;

    public ConsultaDeCertificados(
            CertificadoRepository certificados, DirectorioDeContribuyentes contribuyentes) {
        this.certificados = certificados;
        this.contribuyentes = contribuyentes;
    }

    /** La grilla, paginada, con el estado de cada fila derivado a {@code aLaFecha}. */
    @Transactional(readOnly = true)
    public Pagina<CertificadoEnConsulta> buscar(
            CriterioDeCertificados criterio,
            @Nullable String nombreDelSolicitante,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        CriterioDeCertificados conSolicitantes =
                conSolicitantesResueltos(criterio, nombreDelSolicitante);
        if (conSolicitantes.sinSolicitantePosible()) {
            // Se filtro por solicitante y no hay ninguno que se parezca. Devolver la pagina entera
            // aqui convertiria un nombre inexistente en «todos los certificados», que es el defecto
            // que la consulta de fichas ya cometio una vez y que #44 volvio a cazar.
            return Pagina.vacia(paginacion);
        }

        Pagina<Certificado> pagina = certificados.buscar(conSolicitantes, paginacion);
        if (pagina.estaVacia()) {
            return Pagina.vacia(paginacion);
        }

        Set<Long> solicitantes = new HashSet<>();
        for (Certificado certificado : pagina.contenido()) {
            solicitantes.add(certificado.contribuyenteId());
        }
        Map<Long, ResumenDeContribuyente> padron = contribuyentes.porIds(solicitantes);

        return pagina.mapear(
                certificado ->
                        new CertificadoEnConsulta(
                                certificado, aLaFecha, padron.get(certificado.contribuyenteId())));
    }

    /** Un certificado por su numero, con su solicitante resuelto. */
    @Transactional(readOnly = true)
    public Optional<CertificadoEnConsulta> porNumero(String numero, LocalDate aLaFecha) {
        return certificados
                .porNumero(numero)
                .map(
                        certificado ->
                                new CertificadoEnConsulta(
                                        certificado,
                                        aLaFecha,
                                        contribuyentes
                                                .porIds(Set.of(certificado.contribuyenteId()))
                                                .get(certificado.contribuyenteId())));
    }

    // ------------------------------------------------------------------

    /**
     * El criterio con el filtro por nombre ya traducido a identificadores.
     *
     * <p>La traduccion se hace <b>aqui</b> y no en el repositorio porque el padron es de otro
     * contexto: {@code licencias} no puede unir {@code certificado} con {@code contribuyente} en un
     * {@code JOIN} sin cruzar el limite que Spring Modulith vigila.
     */
    private CriterioDeCertificados conSolicitantesResueltos(
            CriterioDeCertificados criterio, @Nullable String nombreDelSolicitante) {
        String buscado = nombreDelSolicitante == null ? "" : nombreDelSolicitante.strip();
        if (buscado.isEmpty()) {
            return criterio;
        }
        Set<Long> encontrados = new HashSet<>();
        for (ResumenDeContribuyente resumen :
                contribuyentes.buscar(buscado, SOLICITANTES_MAXIMOS)) {
            encontrados.add(resumen.id());
        }
        return criterio.conSolicitantes(encontrados);
    }

    // ------------------------------------------------------------------

    /**
     * Un certificado tal como la pantalla lo pinta.
     *
     * @param certificado la fila
     * @param aLaFecha el dia al que se derivo su estado (regla 9)
     * @param solicitante el resumen del padron; nulo si el contribuyente ya no esta
     */
    public record CertificadoEnConsulta(
            Certificado certificado,
            LocalDate aLaFecha,
            @Nullable ResumenDeContribuyente solicitante) {

        /** VIGENTE o CADUCADO, a la fecha de la consulta. */
        public String estado() {
            return certificado.estadoA(aLaFecha);
        }

        /** El nombre del solicitante, o vacio si el padron ya no lo tiene. */
        public String nombreDelSolicitante() {
            ResumenDeContribuyente resumen = solicitante;
            return resumen == null ? "" : resumen.nombre();
        }

        /** El codigo del solicitante, o vacio si el padron ya no lo tiene. */
        public String codigoDelSolicitante() {
            ResumenDeContribuyente resumen = solicitante;
            return resumen == null ? "" : resumen.codigo();
        }
    }
}
