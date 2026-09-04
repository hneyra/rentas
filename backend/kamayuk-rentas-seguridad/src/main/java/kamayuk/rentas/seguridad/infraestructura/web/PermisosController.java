package kamayuk.rentas.seguridad.infraestructura.web;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos.PermisoDeAcceso;
import kamayuk.rentas.seguridad.dominio.Permiso;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Niveles de accesibilidad de un grupo: {@code PUT /api/v1/seguridad/grupos/{id}/permisos}.
 *
 * <p>Es un {@code PUT} y recibe la lista completa de accesos con sus privilegios, no un cambio
 * incremental. La pantalla del manual es una tabla de accesos por siete casillas: lo que el usuario
 * ve al guardar es el estado completo, y aceptar aqui un delta obligaria a la interfaz a calcular
 * que cambio —y a acertar—.
 *
 * <p>Lo que <b>no</b> hace es borrar los permisos que no vengan en el cuerpo. Un acceso ausente se
 * queda como estaba: enviar una lista parcial no debe traducirse en retirar en silencio todo lo
 * demas. Para retirar, se manda el acceso con la lista de privilegios vacia, que es explicito.
 */
@RestController
@RequestMapping(Api.RAIZ + "/seguridad/grupos/{id}/permisos")
public class PermisosController {

    private final AdministrarPermisos administrar;

    public PermisosController(AdministrarPermisos administrar) {
        this.administrar = administrar;
    }

    /**
     * Los permisos ya configurados del grupo, para cargar la matriz antes de guardarla.
     *
     * <p>No trae las 134 opciones del catalogo: trae las que este grupo tiene configuradas. La
     * pantalla combina esta respuesta —tipicamente unas pocas filas— con la pagina de {@code GET
     * /seguridad/accesos}, que ya pagina el catalogo entero. Ninguna de las dos necesita traer las
     * 134 opciones a la vez.
     */
    @GetMapping
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.LECTURA)
    public List<PermisoResource> deGrupo(@PathVariable("id") long grupo) {
        List<PermisoResource> resultado = new ArrayList<>();
        for (PermisoDeAcceso permiso : administrar.deGrupo(grupo)) {
            resultado.add(PermisoResource.de(permiso));
        }
        return resultado;
    }

    @PutMapping
    @RequiereAcceso(acceso = "permisos", privilegio = Privilegio.REGISTRO)
    public List<PermisoResource> fijar(
            @PathVariable("id") long grupo, @RequestBody CambioDePermisos cambio) {

        Observacion observacion = observacionDe(cambio.observacion());
        List<PermisoResource> resultado = new ArrayList<>();

        for (NivelDeAcceso nivel : nivelesDe(cambio.niveles())) {
            String acceso = accesoDe(nivel);
            Permiso permiso =
                    administrar.fijarParaGrupo(grupo, acceso, privilegios(nivel), observacion);
            resultado.add(PermisoResource.de(permiso, acceso));
        }
        return resultado;
    }

    /**
     * La observacion del cuerpo, o 422 nombrandola (regla 10, RNF-052).
     *
     * <p>{@link Observacion#de} rechaza el nulo con {@code Objects.requireNonNull}, y una {@code
     * NullPointerException} no la caza ningun manejador: cae en el
     * {@code @ExceptionHandler(Exception.class)} y sale <b>500 con identificador de incidencia</b>.
     * O sea que un cuerpo sin observacion decia «el servidor se rompio» y ensuciaba el registro de
     * errores, cuando lo que pasa es que la peticion esta mal. Se lee aqui, una vez, y las dos
     * matrices —la del grupo y la de la cuenta (#585)— usan la misma lectura: dos copias acabarian
     * contestando cosas distintas al mismo cuerpo.
     */
    static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            String mensaje = invalida.getMessage();
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    mensaje == null ? "La observacion recibida no es valida" : mensaje);
        }
    }

    /** Los niveles del cuerpo. Un {@code PUT} que no fija ningun acceso no es un acto. */
    static List<NivelDeAcceso> nivelesDe(@Nullable List<NivelDeAcceso> niveles) {
        if (niveles == null || niveles.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que declarar al menos un acceso en 'niveles': un cambio de permisos que"
                            + " no fija ninguno no cambia nada, y contestarle 200 lo haria parecer"
                            + " guardado");
        }
        return niveles;
    }

    /** El codigo del acceso de un nivel, o 422 nombrandolo. */
    static String accesoDe(NivelDeAcceso nivel) {
        String acceso = nivel.acceso();
        if (acceso == null || acceso.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Cada nivel tiene que decir sobre que 'acceso' va");
        }
        return acceso;
    }

    /**
     * Los privilegios que quedan otorgados sobre ese acceso.
     *
     * <p><b>La lista vacia y la lista ausente no son lo mismo.</b> {@code "privilegios": []} es la
     * retirada explicita de los siete —lo que la pantalla hace al desmarcar todas las casillas—, y
     * en la excepcion de usuario es ademas una <b>negacion</b> que sustituye a lo que da el grupo
     * (#585). Que la clave falte no puede significar eso: seria retirar siete privilegios por un
     * campo olvidado, asi que se rechaza con 422.
     */
    static Set<Privilegio> privilegios(NivelDeAcceso nivel) {
        List<String> nombres = nivel.privilegios();
        if (nombres == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El nivel de '"
                            + nivel.acceso()
                            + "' no trae 'privilegios'. Para retirarlos todos hay que mandar la"
                            + " lista vacia, que es explicito");
        }
        Set<Privilegio> privilegios = EnumSet.noneOf(Privilegio.class);
        for (String nombre : nombres) {
            try {
                privilegios.add(
                        Privilegio.valueOf(nombre.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // El mensaje enumera los siete: el cliente mando un nombre que no
                // existe, y decirle cuales hay es mas util que decirle que fallo.
                throw new IllegalArgumentException(
                        "Privilegio desconocido: '"
                                + nombre
                                + "'. Los siete son "
                                + java.util.Arrays.toString(Privilegio.values()));
            }
        }
        return privilegios;
    }

    /**
     * Cuerpo de la peticion: los niveles a fijar y por que.
     *
     * <p>Los dos campos van {@code @Nullable} porque es lo que Jackson puede producir: la clave que
     * el cliente no manda llega nula, y declararla no nula solo consigue que el fallo salga como
     * {@code NullPointerException} —o sea, 500— en vez de como el 422 que le corresponde.
     */
    public record CambioDePermisos(
            @Nullable List<NivelDeAcceso> niveles, @Nullable String observacion) {}

    /** Un acceso y los privilegios que quedan otorgados sobre el. */
    public record NivelDeAcceso(@Nullable String acceso, @Nullable List<String> privilegios) {}

    /**
     * Un permiso configurado, del <b>grupo o del usuario</b>, y se distingue cual (#543).
     *
     * <p>Los dos identificadores son {@code Long} nulables y no {@code long} primitivos. Antes
     * {@code grupoId} era primitivo y valia {@code 0L} cuando la fila no tenia grupo —o sea, cuando
     * era una excepcion de usuario—, y esa fila salia por HTTP <b>indistinguible de una del grupo
     * 0</b>. {@code usuarioId} directamente no se publicaba, asi que el cliente no tenia ni un dato
     * con el que separarlas: una excepcion de usuario se leia como un permiso del grupo.
     *
     * <p>El esquema garantiza que hay exactamente uno de los dos ({@code permiso_sujeto_ck}, V5), y
     * {@link Permiso} lo repite en su constructor: nulo aqui significa «el otro», no «se
     * desconoce».
     */
    public record PermisoResource(
            long id,
            String acceso,
            @Nullable Long grupoId,
            @Nullable Long usuarioId,
            List<String> privilegios) {

        static PermisoResource de(Permiso permiso, String acceso) {
            List<String> nombres =
                    java.util.Arrays.stream(Privilegio.values())
                            .filter(permiso::tiene)
                            .map(Enum::name)
                            .toList();
            return new PermisoResource(
                    permiso.id() == null ? 0L : permiso.id(),
                    acceso,
                    permiso.grupoId(),
                    permiso.usuarioId(),
                    nombres);
        }

        static PermisoResource de(PermisoDeAcceso permiso) {
            List<String> nombres =
                    java.util.Arrays.stream(Privilegio.values())
                            .filter(permiso.privilegios()::contains)
                            .map(Enum::name)
                            .toList();
            return new PermisoResource(
                    permiso.id(),
                    permiso.codigoDeAcceso(),
                    permiso.grupoId(),
                    permiso.usuarioId(),
                    nombres);
        }
    }
}
