package kamayuk.rentas.seguridad.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos.PermisosConfigurados;
import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Modulo;
import kamayuk.rentas.seguridad.dominio.PermisoEfectivo;
import kamayuk.rentas.seguridad.dominio.TitularDelPrivilegio;
import kamayuk.rentas.seguridad.dominio.Usuario;
import org.jspecify.annotations.Nullable;

/**
 * Los DTO de seguridad, juntos: son cuatro registros de cinco campos y separarlos en cuatro
 * archivos no aclara nada.
 *
 * <p>Campos en español {@code camelCase} (ARQ-04 §3). Ninguno lleva {@code municipalidadId}.
 */
public final class Recursos {

    private Recursos() {}

    public record ModuloResource(long id, String codigo, String nombre, int orden, boolean activo) {
        public static ModuloResource de(Modulo modulo) {
            return new ModuloResource(
                    modulo.id() == null ? 0L : modulo.id(),
                    modulo.codigo(),
                    modulo.nombre(),
                    modulo.orden(),
                    modulo.activo());
        }
    }

    public record AccesoResource(
            long id, long moduloId, String tipo, String codigo, String nombre, boolean activo) {
        public static AccesoResource de(Acceso acceso) {
            return new AccesoResource(
                    acceso.id() == null ? 0L : acceso.id(),
                    acceso.moduloId(),
                    acceso.tipo().name(),
                    acceso.codigo(),
                    acceso.nombre(),
                    acceso.activo());
        }
    }

    public record GrupoResource(
            long id,
            String nombre,
            @Nullable String descripcion,
            boolean habilitado,
            @Nullable LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta) {
        public static GrupoResource de(Grupo grupo) {
            return new GrupoResource(
                    grupo.id() == null ? 0L : grupo.id(),
                    grupo.nombre(),
                    grupo.descripcion(),
                    grupo.habilitado(),
                    grupo.vigencia().desde(),
                    grupo.vigencia().hasta());
        }
    }

    /**
     * El usuario, sin su {@code sujetoOidc} y sin nada parecido a una clave.
     *
     * <p>No hay clave que ocultar —ADR-0005: la autenticacion es del proveedor— y el identificador
     * del proveedor tampoco sale: no lo necesita ninguna pantalla y es un dato con el que se puede
     * correlacionar a la persona fuera del sistema.
     */
    public record UsuarioResource(
            long id,
            String cuenta,
            String nombre,
            @Nullable String correo,
            boolean habilitado,
            @Nullable LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta) {
        public static UsuarioResource de(Usuario usuario) {
            return new UsuarioResource(
                    usuario.id() == null ? 0L : usuario.id(),
                    usuario.cuenta(),
                    usuario.nombre(),
                    usuario.correo(),
                    usuario.habilitado(),
                    usuario.vigencia().desde(),
                    usuario.vigencia().hasta());
        }
    }

    /**
     * Un permiso <b>efectivo</b> de un usuario, con el origen que lo produjo (#543).
     *
     * <p>{@code origen} no es un adorno: es lo que impide que quien consume esta lectura tenga que
     * reimplementar la regla de precedencia —una excepcion de usuario <b>sustituye</b> al grupo
     * entero para ese acceso, otorgue o niegue—. El frontend la tenia invertida (calculaba {@code
     * on = esPropio || esHeredado}), y esa forma es la que lo hace dificil de repetir.
     *
     * <p><b>Una fila con {@code privilegios} vacio no es una fila de mas.</b> Solo se produce
     * cuando hay una excepcion que niega, y es la unica manera de distinguir «se le nego
     * expresamente» de «nunca lo tuvo»; los accesos sobre los que no hay nada configurado,
     * simplemente, no salen.
     *
     * @param grupoId el grupo del que hereda, o nulo si el origen es la excepcion <b>o</b> si la
     *     union viene de mas de un grupo vigente y no hay uno solo que nombrar
     */
    public record PermisoEfectivoResource(
            String acceso, List<String> privilegios, String origen, @Nullable Long grupoId) {

        public static PermisoEfectivoResource de(PermisoEfectivo permiso) {
            List<String> nombres =
                    java.util.Arrays.stream(Privilegio.values())
                            .filter(permiso.privilegios()::contains)
                            .map(Enum::name)
                            .toList();
            return new PermisoEfectivoResource(
                    permiso.codigoDeAcceso(), nombres, permiso.origen().name(), permiso.grupoId());
        }
    }

    /**
     * Lo que una cuenta tiene <b>configurado</b>, surta efecto hoy o no (#583).
     *
     * <p>Las filas son, campo a campo, las mismas que las de la matriz efectiva, y <b>esa es la
     * razon de que la respuesta no sea una lista pelada</b>: sin {@code surtenEfectoHoy}, esta
     * respuesta y la de {@code GET /seguridad/usuarios/{id}/permisos} son indistinguibles, y quien
     * se equivoque de lectura ensenaria como vigentes privilegios que hoy responden 403.
     *
     * <p>Y al reves: hasta este issue las dos preguntas tenian la misma respuesta para una cuenta
     * deshabilitada —la lista vacia—, asi que «conserva permisos» y «nunca tuvo ninguno» no se
     * podian separar. Aqui la primera trae sus filas y la segunda trae {@code permisos: []}, las
     * dos con {@code surtenEfectoHoy: false}.
     *
     * @param surtenEfectoHoy falso cuando la cuenta esta deshabilitada o fuera de vigencia. Por que
     *     exactamente lo dicen {@code habilitado} y las dos fechas de vigencia que ya publica cada
     *     fila de {@code GET /seguridad/usuarios}: repetirlas aqui seria una segunda copia del
     *     estado de la cuenta
     */
    public record PermisosConfiguradosResource(
            long usuarioId,
            String cuenta,
            boolean surtenEfectoHoy,
            List<PermisoEfectivoResource> permisos) {

        public static PermisosConfiguradosResource de(PermisosConfigurados configurados) {
            List<PermisoEfectivoResource> filas = new java.util.ArrayList<>();
            for (PermisoEfectivo permiso : configurados.permisos()) {
                filas.add(PermisoEfectivoResource.de(permiso));
            }
            return new PermisosConfiguradosResource(
                    configurados.usuarioId(),
                    configurados.cuenta(),
                    configurados.surtenEfectoHoy(),
                    List.copyOf(filas));
        }
    }

    /**
     * Una cuenta que hoy puede ejercer un privilegio sobre un acceso, y de donde le viene (#583).
     *
     * <p>No publica {@code habilitado}: aqui solo salen las cuentas que pueden operar, asi que la
     * columna valdria {@code true} en todas las filas — y una columna que no cambia afirma que
     * existe la otra mitad. La otra mitad es la lectura de lo configurado.
     *
     * @param grupoId el grupo que otorga <b>este</b> privilegio, cuando hay uno solo; nulo si el
     *     origen es la excepcion propia <b>o</b> si lo conceden varios grupos vigentes
     */
    public record TitularDelPrivilegioResource(
            long usuarioId, String cuenta, String nombre, String origen, @Nullable Long grupoId) {

        public static TitularDelPrivilegioResource de(TitularDelPrivilegio titular) {
            return new TitularDelPrivilegioResource(
                    titular.usuarioId(),
                    titular.cuenta(),
                    titular.nombre(),
                    titular.origen().name(),
                    titular.grupoId());
        }
    }

    /** Cuerpo de {@code POST /seguridad/grupos/{grupo}/miembros}. */
    public record CambioDeMiembro(long usuarioId, boolean activo, String observacion) {}

    /**
     * El cuerpo del alta de un grupo (#572).
     *
     * <p>La observacion es obligatoria y viaja en el cuerpo: si viene vacia, el constructor de
     * {@code Observacion} la rechaza y la peticion es 422 (regla 10, RNF-052).
     */
    public record AltaDeGrupo(
            String nombre,
            @Nullable String descripcion,
            @Nullable LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String observacion) {}

    /**
     * El cuerpo del alta de un usuario (#572, ADR-0012 §5).
     *
     * <p><b>No hay campo de clave, y no puede haberlo</b>: la credencial vive en el proveedor de
     * identidad y este sistema no la recibe nunca (ADR-0005). Tampoco hay campo para {@code
     * sujetoOidc}: seria pedirle a quien atiende un identificador del proveedor que no tiene por
     * que tener, y tecleado mal enlaza esta fila con nadie (ADR-0012 §5.4).
     *
     * <p>Lo que esta alta escribe es <b>la fila del padron</b>. La cuenta del proveedor la crea el
     * archivo declarativo de {@code despliegue/identidad/}, y sin ella esta persona figura en los
     * listados y no puede entrar.
     */
    public record AltaDeUsuario(
            String cuenta,
            String nombre,
            @Nullable String correo,
            @Nullable LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String observacion) {}

    /** El cuerpo de una baja o una reactivacion: solo su motivo (regla 10). */
    public record MotivoDelCambio(String observacion) {}

    /**
     * El cuerpo de un cambio de vigencia (RF-123).
     *
     * <p>Los dos extremos admiten nulo y significan cosas distintas: sin {@code desde} vale desde
     * siempre, sin {@code hasta} vale indefinidamente. Mandar los dos nulos es <b>quitar</b> la
     * caducidad, no «no cambiar nada»: este cuerpo declara la vigencia entera.
     */
    public record CambioDeVigencia(
            @Nullable LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String observacion) {}

    public record MiembroResource(long grupoId, long usuarioId, boolean activo) {}
}
