package kamayuk.rentas.contribuyentes.aplicacion;

import java.util.Objects;
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
 * auditoria va en la misma transaccion. Ningun argumento es la municipalidad (regla 2), y tampoco
 * el reloj: la fecha de la fila de auditoria la pone {@code AuditoriaJdbc}, que es quien decide su
 * ejercicio (#398).
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

    public RegistrarContribuyente(ContribuyenteRepository repositorio, Auditoria auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    /**
     * Alta de un contribuyente que todavia no esta en el padron.
     *
     * <p><b>Solo el alta</b> (#421). Hasta #421 este metodo tambien corregia —decidia cual de las
     * dos cosas hacer por {@code esNuevo()}— y la correccion salia auditada como un alta: sin el
     * antes, porque este metodo no lo recibia. La correccion es {@link #modificar}.
     *
     * @throws IllegalArgumentException si el que llega ya tiene identificador
     */
    @Transactional
    public Contribuyente registrar(Contribuyente nuevo, Observacion observacion) {
        if (!nuevo.esNuevo()) {
            throw new IllegalArgumentException(
                    "Registrar da de alta; el contribuyente "
                            + nuevo.id()
                            + " ya esta en el padron y se corrige con modificar, que audita lo que"
                            + " habia");
        }
        rechazarDuplicados(nuevo);

        Contribuyente guardado = repositorio.save(nuevo);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "contribuyente",
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, guardado.paraLaAuditoria()));

        return guardado;
    }

    /**
     * Correccion de un contribuyente que ya esta en el padron, auditando <b>lo que habia y lo que
     * queda</b> (#421).
     *
     * <p>El {@code UPDATE} sobrescribe la fila entera y {@code contribuyente} no tiene tabla
     * historica, asi que la fila de auditoria es el unico sitio donde el valor anterior sobrevive
     * (DAT-02 §1: «ante una modificacion se guarda el registro original»). Por eso el antes es un
     * argumento y no algo que este metodo pueda omitir: quien corrige ya lo tiene en la mano,
     * porque lo leyo para saber que conservar de lo que no vino.
     *
     * @param antes la fila tal como se leyo antes de corregirla
     * @param despues la misma fila corregida: mismo identificador
     * @throws IllegalArgumentException si {@code antes} no esta en el padron o {@code despues} es
     *     otra fila
     */
    @Transactional
    public Contribuyente modificar(
            Contribuyente antes, Contribuyente despues, Observacion observacion) {
        if (antes.esNuevo() || !Objects.equals(antes.id(), despues.id())) {
            throw new IllegalArgumentException(
                    "Modificar corrige una fila del padron: el antes ("
                            + antes.id()
                            + ") y el despues ("
                            + despues.id()
                            + ") tienen que ser el mismo contribuyente");
        }
        rechazarDuplicados(despues);

        Contribuyente guardado = repositorio.save(despues);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "contribuyente",
                                String.valueOf(guardado.id()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(antes.paraLaAuditoria(), guardado.paraLaAuditoria()));

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
                                "contribuyente", String.valueOf(id), Operacion.BAJA, observacion)
                        .con(existente.paraLaAuditoria(), baja.paraLaAuditoria()));

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
