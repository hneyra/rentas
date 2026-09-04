package kamayuk.rentas.contribuyentes.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta y mantenimiento del contribuyente.
 *
 * <p>Sigue la plantilla de {@code RegistrarVia}: la {@link Observacion} esta en la firma, la
 * auditoria va en la misma transaccion y el reloj se inyecta. Ningun argumento es la municipalidad
 * (regla 2).
 *
 * <p>Lo propio de este caso de uso es la <b>comprobacion de duplicados antes de escribir</b>. La
 * tabla ya tiene las dos restricciones de unicidad, y son la barrera de verdad; esto se hace de
 * todos modos porque un choque de indice llega como un error de base de datos que no le dice a
 * quien atiende cual de los dos campos repitio —ni con quien—.
 */
@Service
public class RegistrarContribuyente {

    private final ContribuyenteRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarContribuyente(
            ContribuyenteRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Transactional
    public Contribuyente registrar(Contribuyente contribuyente, Observacion observacion) {
        rechazarDuplicados(contribuyente);

        Contribuyente guardado = repositorio.save(contribuyente);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "contribuyente",
                                String.valueOf(guardado.id()),
                                contribuyente.esNuevo() ? Operacion.ALTA : Operacion.MODIFICACION,
                                observacion)
                        .con(null, descripcion(guardado)));

        return guardado;
    }

    /**
     * Da de baja. No borra: el codigo del contribuyente aparece en recibos ya emitidos y en
     * asientos del libro, que no se tocan (RNF-051).
     */
    @Transactional
    public Contribuyente darDeBaja(long id, Observacion observacion) {
        Contribuyente existente =
                repositorio.findById(id).orElseThrow(() -> new ContribuyenteInexistente(id));

        Contribuyente baja = repositorio.save(existente.dadoDeBaja());

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "contribuyente",
                                String.valueOf(id),
                                Operacion.BAJA,
                                observacion)
                        .con(descripcion(existente), descripcion(baja)));

        return baja;
    }

    private void rechazarDuplicados(Contribuyente contribuyente) {
        Optional<Contribuyente> porCodigo = repositorio.findByCodigo(contribuyente.codigo());
        if (esOtro(porCodigo, contribuyente)) {
            throw new CodigoRepetido(contribuyente.codigo().valor());
        }
        Optional<Contribuyente> porDocumento =
                repositorio.findByDocumento(contribuyente.documento());
        if (esOtro(porDocumento, contribuyente)) {
            throw new DocumentoRepetido(contribuyente.documento().tipo().name());
        }
    }

    /**
     * Existe alguien con ese dato y no es este mismo contribuyente.
     *
     * <p>Un contribuyente recien leido de la base siempre tiene identificador; el que puede no
     * tenerlo es el que llega a guardarse, y por eso la comparacion va en ese sentido.
     */
    private static boolean esOtro(Optional<Contribuyente> hallado, Contribuyente contribuyente) {
        if (hallado.isEmpty()) {
            return false;
        }
        Long idHallado = hallado.get().id();
        return idHallado != null && !idHallado.equals(contribuyente.id());
    }

    private static String descripcion(Contribuyente contribuyente) {
        return "{\"codigo\":\""
                + contribuyente.codigo()
                + "\",\"tipoPersona\":\""
                + contribuyente.tipoPersona()
                + "\",\"nombreRazonSocial\":\""
                + contribuyente.nombreRazonSocial().replace("\"", "\\\"")
                + "\",\"activo\":"
                + contribuyente.activo()
                + "}";
    }

    /** Ya hay otro contribuyente con ese codigo en esta municipalidad. */
    public static final class CodigoRepetido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CodigoRepetido(String codigo) {
            super("Ya hay otro contribuyente con el codigo " + codigo + " en esta municipalidad");
        }
    }

    /**
     * Ya hay otro contribuyente con ese documento. El mensaje <b>no dice quien</b>: seria revelar
     * que una persona esta en el padron a quien solo teclea documentos.
     */
    public static final class DocumentoRepetido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DocumentoRepetido(String tipo) {
            super(
                    "Ya hay otro contribuyente registrado con ese "
                            + tipo
                            + " en esta municipalidad");
        }
    }

    /** Se pidio dar de baja a alguien que no existe, o que es de otra municipalidad. */
    public static final class ContribuyenteInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(long id) {
            super("No hay ningun contribuyente con identificador " + id + " en esta municipalidad");
        }
    }
}
