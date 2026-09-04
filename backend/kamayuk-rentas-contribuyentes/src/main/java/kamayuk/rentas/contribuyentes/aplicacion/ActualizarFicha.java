package kamayuk.rentas.contribuyentes.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock reloj;

    public ActualizarFicha(FichaRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Muda al contribuyente: cierra el domicilio vigente del mismo tipo y abre el nuevo, en una
     * sola transaccion.
     *
     * <p>El anterior se cierra <b>el dia antes</b> de que empiece el nuevo, no el mismo dia: si los
     * dos rigieran la misma fecha, preguntar «donde vivia ese dia» tendria dos respuestas.
     */
    @Transactional
    public Domicilio mudar(Domicilio nuevo, Observacion observacion) {
        if (!nuevo.esNuevo()) {
            throw new IllegalArgumentException(
                    "Mudar abre un domicilio nuevo; el que llega ya tiene identificador");
        }

        Optional<Domicilio> anterior =
                repositorio.domicilioVigenteA(
                        nuevo.contribuyenteId(), nuevo.tipo(), nuevo.vigenciaDesde());

        anterior.ifPresent(
                previo -> {
                    Domicilio cerrado = previo.cerradoEl(nuevo.vigenciaDesde().minusDays(1));
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

    @Transactional
    public Contacto registrarContacto(Contacto contacto, Observacion observacion) {
        Contacto guardado = repositorio.guardar(contacto);
        auditar(
                "contacto",
                guardado.id(),
                contacto.esNuevo() ? Operacion.ALTA : Operacion.MODIFICACION,
                observacion,
                null,
                "{\"tipo\":\"" + guardado.tipo() + "\",\"vigente\":" + guardado.vigente() + "}");
        return guardado;
    }

    /**
     * Da de baja un contacto. No lo borra: un gestor que ya no lo es aparece en notificaciones
     * anteriores, y explicar por que se le notifico exige que su ficha siga ahi.
     */
    @Transactional
    public Contacto darDeBajaContacto(Contacto contacto, Observacion observacion) {
        Contacto baja = repositorio.guardar(contacto.dadoDeBaja());
        auditar(
                "contacto",
                baja.id(),
                Operacion.BAJA,
                observacion,
                "{\"vigente\":true}",
                "{\"vigente\":false}");
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
                                LocalDate.now(reloj),
                                tabla,
                                String.valueOf(clave),
                                operacion,
                                observacion)
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
