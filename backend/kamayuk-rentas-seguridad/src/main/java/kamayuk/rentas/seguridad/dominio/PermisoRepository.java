package kamayuk.rentas.seguridad.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Puerto de persistencia de los permisos (RF-121).
 *
 * <p>Un permiso es de un grupo <b>o</b> de un usuario, nunca de los dos, asi que las consultas van
 * separadas: mezclarlas en un {@code findBySujeto(Object)} obligaria a preguntar el tipo en cada
 * llamada, que es la forma de que un dia se pregunte mal.
 */
public interface PermisoRepository {

    Permiso save(Permiso permiso);

    Optional<Permiso> deGrupo(long accesoId, long grupoId);

    Optional<Permiso> deUsuario(long accesoId, long usuarioId);

    /** Todos los permisos de un grupo, para la pantalla de niveles de accesibilidad. */
    List<Permiso> todosLosDeGrupo(long grupoId);

    /**
     * La matriz de permisos <b>efectivos</b> de un usuario: por cada opcion del catalogo sobre la
     * que tiene algun privilegio, el conjunto de privilegios. Las opciones sin ninguno no aparecen.
     *
     * <p>Misma precedencia que el guardia ({@code ComprobadorDeAcceso}): la excepcion del usuario
     * decide —otorgue o niegue—, y si no la hay manda la union de sus grupos vigentes. Vigencia y
     * habilitacion se comprueban en el usuario, en el grupo y en la pertenencia (RF-123); un
     * usuario deshabilitado o fuera de vigencia recibe la matriz vacia.
     *
     * <p>Es la fuente del menu de la interfaz (ADR-0013): resolverlo con otra regla que la del
     * guardia mostraria opciones que despues responden 403, o esconderia opciones que si funcionan.
     */
    Map<String, Set<Privilegio>> efectivosDe(String cuenta, LocalDate fecha);

    /**
     * La misma matriz, pero de <b>otro</b> usuario y diciendo de donde viene cada fila (#543).
     *
     * <p>No es una variante por comodidad de la de arriba. Aquella sirve a la interfaz para
     * dibujarse a si misma —el sujeto sale del token y lo unico que importa es que se puede—; esta
     * sirve para <b>administrar a otro</b>, y ahi lo que hay que ver es la precedencia: si lo que
     * manda es la excepcion del usuario o sus grupos. Aplanarlas juntas dejaria a quien administra
     * sin poder distinguir un permiso propio de uno heredado, que es exactamente lo que la pantalla
     * de la matriz existe para enseñar.
     *
     * <p>El sujeto va por <b>identificador</b> y no por cuenta porque asi lo nombra la ruta que la
     * publica, y porque es lo que el resto de la administracion usa (grupo, miembro).
     *
     * <p>Misma regla que el guardia en todo lo demas: vigencia y habilitacion en los tres eslabones
     * (RF-123), y un usuario deshabilitado o fuera de vigencia recibe la lista vacia. Resolverlo
     * con otra regla mostraria en la matriz privilegios que despues responden 403.
     *
     * @see PermisoEfectivo
     */
    List<PermisoEfectivo> efectivosConOrigenDe(long usuarioId, LocalDate fecha);

    /**
     * La misma matriz, <b>sin exigir que la cuenta pueda operar hoy</b>: lo configurado (#583).
     *
     * <p>No es un relajamiento de la de arriba, es otra pregunta. Aquella contesta lo que la cuenta
     * <b>puede</b>, y por eso aplica la regla del guardia entera: una cuenta deshabilitada o fuera
     * de vigencia recibe la lista vacia, porque ensenar en la matriz privilegios que despues
     * responden 403 seria peor que no ensenar nada. Su efecto secundario es que <b>una cuenta
     * deshabilitada que conserva permisos y una que nunca tuvo ninguno devuelven el mismo JSON</b>,
     * y quien audita necesita separarlas: deshabilitar no retira nada, y rehabilitar lo devuelve
     * entero.
     *
     * <p>Lo unico que se quita es la guarda del <b>usuario</b>. La del grupo y la de la pertenencia
     * se quedan, y eso tambien es deliberado: lo que se pregunta es que volveria a poder el dia que
     * alguien reactive <b>la cuenta</b>, y reactivar una cuenta no reactiva un grupo inhabilitado
     * ni devuelve a nadie a un grupo del que salio.
     *
     * <p>La precedencia es la misma expresion —y el mismo SQL— que {@link
     * #efectivosConOrigenDe(long, LocalDate)}: escribirla dos veces es como dos copias del mismo
     * {@code CASE} acaban divergiendo, y aqui la copia divergente decidiria quien entra donde.
     */
    List<PermisoEfectivo> configuradosDe(long usuarioId, LocalDate fecha);

    /**
     * Que cuentas pueden hoy ejercer un privilegio sobre un acceso, en <b>una</b> consulta (#583).
     *
     * <p>La pregunta inversa de {@link #efectivosConOrigenDe(long, LocalDate)}, y no se compone con
     * ella: contestarla cuenta por cuenta es una peticion por usuario del padron, y acotar por
     * grupo no vale porque la excepcion propia <b>sustituye</b> a lo que el grupo da —alguien cuyo
     * grupo no tiene el privilegio puede tenerlo por excepcion, y al reves—.
     *
     * <p>Misma regla que el guardia: solo las cuentas habilitadas y vigentes, con sus grupos
     * vigentes y sus pertenencias activas (RF-123). Lo que una cuenta que hoy no puede operar
     * <b>conserva</b> lo contesta {@link #configuradosDe(long, LocalDate)}.
     *
     * @see TitularDelPrivilegio
     */
    Pagina<TitularDelPrivilegio> quienesTienen(
            long accesoId, Privilegio privilegio, LocalDate fecha, Paginacion paginacion);

    /**
     * Cuantos usuarios habilitados y vigentes pueden hoy administrar permisos.
     *
     * <p>Existe para una sola cosa: impedir que el ultimo se quede sin el privilegio. Un sistema
     * sin nadie que pueda otorgar permisos no se arregla desde el sistema —hace falta entrar por la
     * base de datos—, asi que el error mas caro de esta pantalla es tambien el mas facil de
     * cometer: quitarse a uno mismo el permiso que hacia falta para devolverselo.
     */
    long usuariosQuePuedenAdministrarPermisos(LocalDate fecha);
}
