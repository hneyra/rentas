package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra la copia local de la autorizacion para una municipalidad recien implantada: el catalogo
 * de opciones en {@code modulo_sistema} y {@code acceso} (RF-122), el grupo de administracion, el
 * primer administrador, su afiliacion y sus siete privilegios sobre cada opcion.
 *
 * <h2>Lo que era y lo que es (ADR-0039, etapa 4)</h2>
 *
 * <p>Hasta la etapa 4 esto era {@code SembradorDeAccesos} —solo el catalogo— y el resto lo hacia la
 * implantacion a traves de los once casos de uso de administracion. Esos casos de uso se fueron a
 * {@code identidad}, que es el dueño de la autorizacion, y esta copia se llena por el buzon. Lo que
 * queda aqui es exactamente lo que catastro, normativa y caja ya tenian: una siembra del arranque
 * en frio, con SQL directo y sin caso de uso, para que la municipalidad tenga con que entrar ANTES
 * de que el consumidor traiga lo que `identidad` diga. Es una de las dos escrituras legitimas de
 * estas cuatro tablas fuera de `identidad` —la otra es el aplicador del buzon— y las dos estan
 * declaradas en {@code escritoresDeLaAutorizacionConMotivo()} con su fecha de fin: esta, la etapa
 * 5, en la que la siembra desaparece y todo llega por el buzon.
 *
 * <h2>Idempotente y solo agrega</h2>
 *
 * <p>Se puede ejecutar en cada despliegue: lo que ya existe se queda como esta —con los permisos
 * que alguien haya configurado despues— y lo que falta se crea. Nunca borra: retirarle permisos al
 * administrador porque alguien relanzo el despliegue seria peor que no tener el procedimiento, y
 * los permisos que cuelgan de un acceso retirado son constancia de quien pudo hacer que (RNF-051).
 *
 * <h2>El unico grupo</h2>
 *
 * <p>{@value #GRUPO_DE_ADMINISTRACION}, con el catalogo entero y los siete privilegios. El grupo
 * «Seguridad» que la etapa anterior dejaba como plantilla ya no tiene sentido aqui: sus cuatro
 * opciones —usuarios, grupos, miembros, permisos— se administran en `identidad`, y un grupo
 * plantilla sobre opciones que este sistema ya no sirve seria una promesa vacia.
 */
@Service
public class SembradorDeLaCopiaLocal extends RepositorioJdbc {

    /** El grupo del que cuelgan los permisos del primer administrador. */
    public static final String GRUPO_DE_ADMINISTRACION = "Administracion del sistema";

    private final Auditoria auditoria;
    private final Clock reloj;

    public SembradorDeLaCopiaLocal(JdbcClient jdbc, Auditoria auditoria, Clock reloj) {
        super(jdbc);
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Deja la copia local lista para la municipalidad del contexto.
     *
     * <p><b>Una sola transaccion para todo</b>, y por dos motivos distintos. El primero es de
     * negocio: una municipalidad implantada a medias —con accesos y sin administrador— es peor que
     * ninguna, porque parece lista. El segundo es tecnico y se paga en cuanto se olvida: las cinco
     * tablas llevan RLS con {@code FORCE} y sus politicas leen {@code app.municipalidad_id}, que el
     * gestor de transacciones fija con {@code SET LOCAL} <b>al abrir la transaccion</b>; leerlas
     * fuera de una no devuelve vacio, revienta.
     *
     * @return cuantos accesos se crearon; 0 en un despliegue donde no cambio el catalogo
     */
    @Transactional
    public int sembrar(String cuenta, String nombreDelAdministrador, Observacion porQue) {
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

        long grupoId = grupoDeAdministracion();
        long usuarioId = administrador(cuenta, nombreDelAdministrador);
        afiliar(grupoId, usuarioId, cuenta);
        for (CatalogoDeOpciones.Opcion opcion : opciones) {
            otorgarLosSiete(grupoId, opcion.codigo(), cuenta);
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

    private long grupoDeAdministracion() {
        jdbc().sql(
                        "INSERT INTO grupo (municipalidad_id, nombre, descripcion)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :nombre, :descripcion)"
                                + " ON CONFLICT (municipalidad_id, nombre) DO NOTHING")
                .param("nombre", GRUPO_DE_ADMINISTRACION)
                .param(
                        "descripcion",
                        "Creado por la implantacion: administra la municipalidad entera")
                .update();
        return jdbc().sql("SELECT id FROM grupo WHERE nombre = :nombre")
                .param("nombre", GRUPO_DE_ADMINISTRACION)
                .query(Long.class)
                .single();
    }

    /**
     * El primer administrador, como fila.
     *
     * <p>{@code cuenta} tiene que coincidir con el {@code preferred_username} del token: es lo
     * unico que une esta fila con la identidad de Keycloak, y si no coinciden el usuario entra y no
     * es nadie. <b>No se crea ninguna clave</b>: el sistema no guarda contrasenas ni las transporta
     * (ADR-0005).
     */
    private long administrador(String cuenta, String nombre) {
        jdbc().sql(
                        "INSERT INTO usuario (municipalidad_id, cuenta, nombre)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :cuenta, :nombre)"
                                + " ON CONFLICT (municipalidad_id, cuenta) DO NOTHING")
                .param("cuenta", cuenta)
                .param("nombre", nombre)
                .update();
        return jdbc().sql("SELECT id FROM usuario WHERE cuenta = :cuenta")
                .param("cuenta", cuenta)
                .query(Long.class)
                .single();
    }

    private void afiliar(long grupoId, long usuarioId, String quien) {
        jdbc().sql(
                        "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id, usuario_alta)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :grupo, :usuario, :quien)"
                                + " ON CONFLICT (municipalidad_id, grupo_id, usuario_id)"
                                + " DO NOTHING")
                .param("grupo", grupoId)
                .param("usuario", usuarioId)
                .param("quien", quien)
                .update();
    }

    /**
     * Los siete privilegios sobre una opcion, para el grupo de administracion.
     *
     * <p>Las siete columnas se nombran desde {@link Privilegio#columna()} y no a mano: son las
     * mismas que lee {@code ComprobadorDeAccesoJdbc}, y escribir aqui una lista paralela seria dos
     * verdades sobre lo mismo — un privilegio nuevo quedaria otorgado en un sitio y sin leer en el
     * otro.
     */
    private void otorgarLosSiete(long grupoId, String acceso, String quien) {
        StringBuilder columnas = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        for (Privilegio privilegio : Privilegio.values()) {
            columnas.append(", ").append(privilegio.columna());
            valores.append(", true");
        }
        jdbc().sql(
                        "INSERT INTO permiso (municipalidad_id, acceso_id, grupo_id,"
                                + " usuario_registro"
                                + columnas
                                + ") SELECT "
                                + MUNICIPALIDAD_ACTUAL
                                + ", a.id, :grupo, :quien"
                                + valores
                                + "   FROM acceso a"
                                + "  WHERE a.codigo = :acceso"
                                + "    AND NOT EXISTS ("
                                + "      SELECT 1 FROM permiso p"
                                + "       WHERE p.acceso_id = a.id AND p.grupo_id = :grupo)")
                .param("grupo", grupoId)
                .param("acceso", acceso)
                .param("quien", quien)
                .update();
    }
}
