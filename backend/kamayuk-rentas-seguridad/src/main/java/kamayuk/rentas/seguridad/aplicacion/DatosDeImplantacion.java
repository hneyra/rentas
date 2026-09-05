package kamayuk.rentas.seguridad.aplicacion;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para implantar una municipalidad.
 *
 * <p>Son propiedades y no argumentos de linea de comandos por lo mismo que en el migrador: la clave
 * de {@code sgtm_owner} esta entre ellas, y un argumento queda en el historial del proceso y en los
 * registros del orquestador.
 *
 * <h2>Por que no la registra un {@code @ConfigurationPropertiesScan}</h2>
 *
 * <p>Porque un escaneo la registra en <b>todos</b> los perfiles, y este record valida en su
 * constructor compacto: sin las propiedades puestas, el bean falla al construirse y el contexto no
 * arranca. Con el escaneo, el proceso <b>web</b> —que no implanta nada y no tiene por que conocer
 * la clave de {@code sgtm_owner}— moria al arrancar con «Falta kamayuk.implantacion.ubigeo». Lo
 * encontro el primer arranque real del artefacto despues de escribir la implantacion.
 *
 * <p>Por eso la declara {@link ImplantarMunicipalidad} con {@code @EnableConfigurationProperties}:
 * asi hereda sus dos condiciones —perfil {@code batch} y propiedad presente— y no existe en ningun
 * otro sitio. La validacion sigue siendo dura donde tiene que serlo: si alguien pide una
 * implantacion a medias, no obtiene una implantacion a medias.
 *
 * @param ubigeo los seis digitos que identifican a la municipalidad; es la clave por la que el
 *     procedimiento es idempotente
 * @param nombre nombre de la municipalidad
 * @param tipo {@code DISTRITAL} o {@code PROVINCIAL}, como exige el {@code CHECK} de la tabla
 * @param administrador cuenta del primer administrador. <b>Tiene que ser el mismo {@code
 *     preferred_username} que emite Keycloak</b>: es lo unico que une la fila con la identidad
 * @param nombreDelAdministrador su nombre, para las pantallas y la auditoria
 * @param esDemostracion si la instalacion es de demostracion, y por tanto todo documento que emita
 *     sale marcado (#122). <b>Por omision {@code false}</b>, y ese es el valor correcto: una
 *     instalacion que se olvide de declararse de demostracion emite papeles sin marca, pero una que
 *     se declarara de demostracion por descuido solo emite papeles marcados de mas. De los dos
 *     errores posibles, el valor por omision tiene que ser el que no se pueda cometer callando
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso. No es una
 *     persona y no debe parecerlo: quien lea la auditoria tiene que distinguir lo que hizo la
 *     implantacion de lo que hizo alguien
 */
@ConfigurationProperties("kamayuk.implantacion")
public record DatosDeImplantacion(
        String ubigeo,
        String nombre,
        String tipo,
        String administrador,
        String nombreDelAdministrador,
        boolean esDemostracion,
        String usuarioDelProceso) {

    private static final Set<String> TIPOS = Set.of("DISTRITAL", "PROVINCIAL");

    public DatosDeImplantacion {
        ubigeo = exigir(ubigeo, "kamayuk.implantacion.ubigeo");
        if (!ubigeo.matches("\\d{6}")) {
            throw new IllegalArgumentException(
                    "El ubigeo son seis digitos, y llego '" + ubigeo + "'");
        }
        nombre = exigir(nombre, "kamayuk.implantacion.nombre");
        tipo = exigir(tipo, "kamayuk.implantacion.tipo").toUpperCase(java.util.Locale.ROOT);
        if (!TIPOS.contains(tipo)) {
            throw new IllegalArgumentException(
                    "El tipo de municipalidad es DISTRITAL o PROVINCIAL, y llego '" + tipo + "'");
        }
        administrador = exigir(administrador, "kamayuk.implantacion.administrador");
        nombreDelAdministrador =
                exigir(nombreDelAdministrador, "kamayuk.implantacion.nombre-del-administrador");
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "implantacion"
                        : usuarioDelProceso;
    }

    private static String exigir(String valor, String propiedad) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta " + propiedad + ", que no tiene valor por omision");
        }
        return valor.strip();
    }
}
