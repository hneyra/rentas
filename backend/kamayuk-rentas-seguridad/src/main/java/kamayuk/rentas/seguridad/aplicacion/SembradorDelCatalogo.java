package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra el CATALOGO de este sistema para una municipalidad recien implantada: sus modulos en
 * {@code modulo_sistema} y sus opciones en {@code acceso} (RF-122). Nada mas.
 *
 * <h2>Lo que era y lo que es (ADR-0039, etapa 5)</h2>
 *
 * <p>Hasta la etapa 4 esto se llamaba {@code SembradorDeLaCopiaLocal} y escribia <b>seis</b>
 * tablas: las dos del catalogo y ademas {@code grupo}, {@code usuario}, {@code miembro} y {@code
 * permiso} —el grupo de administracion, el primer administrador, su afiliacion y sus siete
 * privilegios—. Esas cuatro son la autorizacion, y su dueño es {@code identidad}: mientras esta
 * clase las escribiera habia <b>dos origenes para la misma tabla</b>, y el de aqui solo agregaba
 * ({@code ON CONFLICT … DO NOTHING}) — que es exactamente lo que hace que una revocacion decidida
 * en {@code identidad} no se note. Desde la etapa 5 el arranque en frio no se siembra: llega por el
 * buzon, y lo trae la pasada del consumidor con que termina {@link ImplantarMunicipalidad}.
 *
 * <p><b>El catalogo si se siembra aqui, y no es una excepcion a lo anterior</b>: {@code
 * modulo_sistema} y {@code acceso} no dicen a quien se le concede nada — dicen <b>que hay</b> en
 * este sistema, o sea que pantallas existen. Ese catalogo lo decide el dueño de cada sistema
 * (ADR-0039 §«Lo que cuesta», punto 5) y sin el las opciones de {@code rentas} no tendrian fila
 * sobre la que colgar un permiso: los {@code PERMISO_FIJADO} que el buzon trae quedarian pendientes
 * por su dependencia para siempre.
 *
 * <h2>El nombre del grupo ya no vive aqui</h2>
 *
 * <p>La constante {@code GRUPO_DE_ADMINISTRACION} se retira con los cuatro {@code INSERT}: el grupo
 * lo crea {@code identidad} y su nombre llega dentro del evento. Conservarla habria dejado en este
 * repositorio una segunda opinion sobre como se llama un grupo que este sistema ya no crea, y la
 * que se quedaria vieja seria justo esta.
 *
 * <h2>Idempotente y solo agrega</h2>
 *
 * <p>Se puede ejecutar en cada despliegue: lo que ya existe se queda como esta y lo que falta se
 * crea. Nunca borra: una opcion retirada del catalogo conserva su fila porque los permisos que
 * cuelgan de ella son constancia de quien pudo hacer que (RNF-051).
 */
@Service
public class SembradorDelCatalogo extends RepositorioJdbc {

    private final Auditoria auditoria;
    private final Clock reloj;

    public SembradorDelCatalogo(JdbcClient jdbc, Auditoria auditoria, Clock reloj) {
        super(jdbc);
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Deja el catalogo de este sistema sembrado para la municipalidad del contexto.
     *
     * <p><b>Una sola transaccion</b>, y por dos motivos distintos. El primero es de negocio: un
     * catalogo sembrado a medias deja opciones sin fila, o sea pantallas a las que nadie puede dar
     * permiso, y no se nota hasta que alguien las busca. El segundo es tecnico y se paga en cuanto
     * se olvida: las dos tablas llevan RLS con {@code FORCE} y sus politicas leen {@code
     * app.municipalidad_id}, que el gestor de transacciones fija con {@code SET LOCAL} <b>al abrir
     * la transaccion</b>; leerlas fuera de una no devuelve vacio, revienta.
     *
     * @return cuantos accesos se crearon; 0 en un despliegue donde no cambio el catalogo
     */
    @Transactional
    public int sembrar(Observacion porQue) {
        List<CatalogoDeOpciones.Opcion> opciones = CatalogoDeOpciones.leer();
        if (opciones.isEmpty()) {
            throw new IllegalStateException(
                    "El catalogo de opciones vino vacio. Sembrar cero accesos dejaria el sistema"
                            + " sin ninguna opcion configurable, y en silencio");
        }

        int creados = 0;
        for (CatalogoDeOpciones.Opcion opcion : opciones) {
            creados += crearAccesoSiFalta(opcion, moduloId(opcion));
        }

        // Solo si se creo algo. Un despliegue que no cambia el catalogo no tiene nada que
        // asentar, y una fila de auditoria por despliegue convierte la bitacora en un registro de
        // reinicios — que es lo contrario de lo que ADR-0008 quiere que se pueda leer ahi.
        if (creados == 0) {
            return 0;
        }
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj), "acceso", "catalogo", Operacion.ALTA, porQue)
                        .con(
                                null,
                                "{\"accesosCreados\":"
                                        + creados
                                        + ",\"opcionesDelCatalogo\":"
                                        + opciones.size()
                                        + "}"));
        return creados;
    }

    /** Crea el modulo si falta y devuelve su identificador. */
    private long moduloId(CatalogoDeOpciones.Opcion opcion) {
        jdbc().sql(
                        "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :codigo, :nombre)"
                                + " ON CONFLICT (municipalidad_id, codigo) DO NOTHING")
                .param("codigo", opcion.moduloCodigo())
                .param("nombre", opcion.moduloNombre())
                .update();

        return jdbc().sql("SELECT id FROM modulo_sistema WHERE codigo = :codigo")
                .param("codigo", opcion.moduloCodigo())
                .query(Long.class)
                .single();
    }

    private int crearAccesoSiFalta(CatalogoDeOpciones.Opcion opcion, long moduloId) {
        return jdbc().sql(
                        "INSERT INTO acceso (municipalidad_id, modulo_id, tipo, codigo, nombre)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :modulo, 'OPCION_MENU', :codigo, :nombre)"
                                + " ON CONFLICT (municipalidad_id, codigo) DO NOTHING")
                .param("modulo", moduloId)
                .param("codigo", opcion.codigo())
                .param("nombre", opcion.nombre())
                .update();
    }
}
