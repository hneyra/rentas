package kamayuk.rentas.contribuyentes.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.FichaRepository;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que cuelga del contribuyente: donde esta, como se le ubica y quien responde con el.
 *
 * <p>El metodo que justifica esta clase es {@link #mudar}: cambiar de domicilio fiscal es <b>cerrar
 * uno y abrir otro en la misma transaccion</b>, no editar una direccion. Si fueran dos operaciones
 * separadas, entre una y otra el contribuyente tendria dos domicilios fiscales abiertos —o ninguno—
 * y una emision que corriera en ese instante notificaria mal.
 *
 * <p>El indice parcial {@code domicilio_fiscal_vigente_uq} impide el primer caso aunque el codigo
 * se equivoque; la transaccion impide el segundo. Las dos barreras son necesarias: el indice no
 * puede exigir que exista uno.
 */
@Service
public class ActualizarFicha {

    private final FichaRepository repositorio;
    private final Auditoria auditoria;

    public ActualizarFicha(FichaRepository repositorio, Auditoria auditoria) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
    }

    /**
     * Muda al contribuyente: cierra el domicilio vigente del mismo tipo y abre el nuevo, en una
     * sola transaccion.
     *
     * <p>El anterior se cierra <b>el dia antes</b> de que empiece el nuevo, no el mismo dia: si los
     * dos rigieran la misma fecha, preguntar «donde vivia ese dia» tendria dos respuestas.
     *
     * <p><b>El anterior es el tramo abierto, no el que rige en {@code vigenciaDesde}</b> (#420).
     * Hasta #420 se buscaba el que regia en esa fecha, y con una fecha anterior al tramo abierto la
     * mudanza se estrellaba: si no regia ninguno no cerraba nada y el segundo FISCAL abierto
     * chocaba con el indice (500); si regia uno ya cerrado, {@link Domicilio#cerradoEl} lanzaba
     * (500); y en el PROCESAL, sin indice, quedaban dos abiertos. Ahora una mudanza solo se anade
     * al final, y lo que no lo hace se rechaza con un mensaje que lo dice ({@link
     * Domicilio#cerradoAntesDe}).
     *
     * @throws IllegalArgumentException si {@code vigenciaDesde} no es posterior al inicio del tramo
     *     abierto del mismo tipo
     */
    @Transactional
    public Domicilio mudar(Domicilio nuevo, Observacion observacion) {
        if (!nuevo.esNuevo()) {
            throw new IllegalArgumentException(
                    "Mudar abre un domicilio nuevo; el que llega ya tiene identificador");
        }

        Optional<Domicilio> abierto =
                repositorio.tramoAbierto(nuevo.contribuyenteId(), nuevo.tipo());

        abierto.ifPresent(
                previo -> {
                    Domicilio cerrado = previo.cerradoAntesDe(nuevo);
                    repositorio.guardar(cerrado);
                    auditar(
                            "domicilio",
                            previo.id(),
                            Operacion.MODIFICACION,
                            observacion,
                            descripcion(previo),
                            descripcion(cerrado));
                });

        Domicilio guardado = repositorio.guardar(nuevo);
        auditar(
                "domicilio",
                guardado.id(),
                Operacion.ALTA,
                observacion,
                null,
                descripcion(guardado));

        return guardado;
    }

    /**
     * Alta de un contacto.
     *
     * <p><b>Solo el alta</b> (#421): hasta #421 tambien corregia, decidiendo por {@code esNuevo()},
     * y la correccion salia sin el antes. La correccion es {@link #corregirContacto}.
     *
     * @throws IllegalArgumentException si el que llega ya tiene identificador
     */
    @Transactional
    public Contacto registrarContacto(Contacto nuevo, Observacion observacion) {
        if (!nuevo.esNuevo()) {
            throw new IllegalArgumentException(
                    "Registrar un contacto es darlo de alta; el "
                            + nuevo.id()
                            + " ya existe y se corrige con corregirContacto, que audita lo que"
                            + " habia");
        }
        Contacto guardado = repositorio.guardar(nuevo);
        auditar(
                "contacto",
                guardado.id(),
                Operacion.ALTA,
                observacion,
                null,
                guardado.paraLaAuditoria());
        return guardado;
    }

    /**
     * Correccion de un contacto, auditando <b>lo que habia y lo que queda</b> (#421).
     *
     * <p>El {@code UPDATE} pisa el valor, el nombre y el documento, y {@code contacto} no tiene
     * tabla historica: si la auditoria no guarda el antes, el correo al que ya se le notifico a un
     * gestor se pierde con la primera correccion. Versionar el contacto como el domicilio —cerrar
     * uno y abrir otro— tambien lo conservaria, pero cambiaria su identidad y la de las
     * notificaciones que lo citan; eso no es este paso.
     *
     * @param antes el contacto tal como se leyo antes de corregirlo
     * @param despues el mismo contacto corregido: mismo identificador y mismo contribuyente
     * @throws IllegalArgumentException si {@code antes} no existe o {@code despues} es otro
     *     contacto
     */
    @Transactional
    public Contacto corregirContacto(Contacto antes, Contacto despues, Observacion observacion) {
        if (antes.esNuevo()
                || !Objects.equals(antes.id(), despues.id())
                || antes.contribuyenteId() != despues.contribuyenteId()) {
            throw new IllegalArgumentException(
                    "Corregir un contacto es corregir el mismo: el antes ("
                            + antes.id()
                            + ") y el despues ("
                            + despues.id()
                            + ") tienen que ser el mismo contacto del mismo contribuyente");
        }
        Contacto guardado = repositorio.guardar(despues);
        auditar(
                "contacto",
                guardado.id(),
                Operacion.MODIFICACION,
                observacion,
                antes.paraLaAuditoria(),
                guardado.paraLaAuditoria());
        return guardado;
    }

    /**
     * Da de baja un contacto. No lo borra: un gestor que ya no lo es aparece en notificaciones
     * anteriores, y explicar por que se le notifico exige que su ficha siga ahi.
     *
     * <p>Audita con la misma descripcion que el alta y la correccion (#421): hasta entonces
     * guardaba {@code {vigente:true}} y {@code {vigente:false}}, una segunda fuente de lo que se
     * audita que no decia de que contacto se trataba.
     */
    @Transactional
    public Contacto darDeBajaContacto(Contacto contacto, Observacion observacion) {
        Contacto baja = repositorio.guardar(contacto.dadoDeBaja());
        auditar(
                "contacto",
                baja.id(),
                Operacion.BAJA,
                observacion,
                contacto.paraLaAuditoria(),
                baja.paraLaAuditoria());
        return baja;
    }

    @Transactional
    public ResponsableSolidario registrarResponsable(
            ResponsableSolidario responsable, Observacion observacion) {
        ResponsableSolidario guardado = repositorio.guardar(responsable);
        auditar(
                "responsable_solidario",
                guardado.id(),
                Operacion.ALTA,
                observacion,
                null,
                descripcion(guardado));
        return guardado;
    }

    /**
     * Cierra el vinculo en esa fecha. No lo borra: la deuda anterior sigue siendo suya, y una
     * notificacion de entonces se defiende ensenando que el vinculo regia.
     */
    @Transactional
    public ResponsableSolidario cerrarResponsable(
            ResponsableSolidario responsable, LocalDate fecha, Observacion observacion) {
        ResponsableSolidario cerrado = repositorio.guardar(responsable.cerradoEl(fecha));
        auditar(
                "responsable_solidario",
                cerrado.id(),
                Operacion.BAJA,
                observacion,
                descripcion(responsable),
                descripcion(cerrado));
        return cerrado;
    }

    /**
     * La clave llega como {@code Long} y puede ser nula solo si el repositorio devolvio algo sin
     * identificador, que seria un defecto suyo; se convierte a texto igual que en los demas casos
     * de uso. {@code antes} es nulo en un alta: no habia nada antes.
     */
    private void auditar(
            String tabla,
            @Nullable Long clave,
            Operacion operacion,
            Observacion observacion,
            @Nullable String antes,
            String despues) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                tabla, String.valueOf(clave), operacion, observacion)
                        .con(antes, despues));
    }

    private static String descripcion(Domicilio domicilio) {
        return "{\"tipo\":\""
                + domicilio.tipo()
                + "\",\"direccion\":\""
                + domicilio.direccion().replace("\"", "\\\"")
                + "\",\"vigenciaDesde\":\""
                + domicilio.vigenciaDesde()
                + "\",\"vigenciaHasta\":"
                + (domicilio.vigenciaHasta() == null
                        ? "null"
                        : "\"" + domicilio.vigenciaHasta() + "\"")
                + "}";
    }

    private static String descripcion(ResponsableSolidario responsable) {
        return "{\"vinculo\":\""
                + responsable.vinculo()
                + "\",\"responsableId\":"
                + responsable.responsableId()
                + ",\"vigenciaDesde\":\""
                + responsable.vigenciaDesde()
                + "\",\"vigenciaHasta\":"
                + (responsable.vigenciaHasta() == null
                        ? "null"
                        : "\"" + responsable.vigenciaHasta() + "\"")
                + "}";
    }
}
