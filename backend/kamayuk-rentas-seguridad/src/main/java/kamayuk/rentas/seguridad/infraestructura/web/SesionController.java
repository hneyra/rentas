package kamayuk.rentas.seguridad.infraestructura.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSesion;
import kamayuk.rentas.seguridad.aplicacion.IdentidadDeLaSesion;
import kamayuk.rentas.seguridad.aplicacion.MunicipalidadDeLaSesion;
import kamayuk.rentas.seguridad.aplicacion.PermisosDeLaSesion;
import kamayuk.rentas.seguridad.dominio.ConsultaDeAuditoria;
import kamayuk.rentas.seguridad.dominio.Identidad;
import kamayuk.rentas.seguridad.dominio.Municipalidad;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Respaldo;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesion, auditoria y respaldos: las operaciones que cierran el modulo de seguridad (RF-124 a
 * RF-126), mas las tres que <b>no son opciones del catalogo</b> sino la sesion hablando de si misma
 * —quien es (#559), que puede hacer (ADR-0013) y a que municipalidad pertenece (#555)—, y el cambio
 * de ejercicio de trabajo, que si lo es.
 */
@RestController
public class SesionController {

    private final AdministrarSesion administrar;
    private final PermisosDeLaSesion permisos;
    private final MunicipalidadDeLaSesion municipalidad;
    private final IdentidadDeLaSesion identidad;

    public SesionController(
            AdministrarSesion administrar,
            PermisosDeLaSesion permisos,
            MunicipalidadDeLaSesion municipalidad,
            IdentidadDeLaSesion identidad) {
        this.administrar = administrar;
        this.permisos = permisos;
        this.municipalidad = municipalidad;
        this.identidad = identidad;
    }

    /**
     * Quien es la sesion: el usuario que hay detras del token, ya resuelto a la fila de {@code
     * usuario} de esta municipalidad (#559, RF-121).
     *
     * <p><b>Lo que publica y no publicaba nadie es el {@code usuarioId}.</b> {@link
     * #cambiarClave(long, SolicitudDeCambioDeClave)} solo admite la clave propia, y la interfaz no
     * tenia forma de saber cual era su identificador: las dos unicas lecturas que publicaban un
     * {@code usuario.id} —el listado de usuarios y la matriz de otro— estan detras de un permiso de
     * administracion mucho mayor que «cambiar mi propia contrasena», y deducirlo cruzando la cuenta
     * del token contra el listado obligaria a otorgarlo.
     *
     * <p><b>Sin ningun parametro</b>, por lo mismo que {@link #municipalidadDeLaSesion()}: el
     * sujeto sale del token y no de la peticion. Con uno, esta lectura seria el padron de usuarios
     * sin su permiso —y devolveria el {@code id} de otro, que es exactamente lo que la guarda del
     * cambio de clave existe para rechazar—. Uno de mas ni siquiera se ignora: {@code
     * GuardiaDeParametros} lo rechaza con {@code 422} nombrandolo (#539).
     *
     * <p>Declara el centinela {@link RequiereAcceso#SESION_PROPIA}, igual que las otras dos
     * lecturas de esta clase (ADR-0013): leer quien es uno mismo no revela nada que no revele el
     * token que ya se presento, y exigir aqui el acceso {@code usuarios} dejaria a quien no
     * administra sin poder pedir el cambio de su propia clave.
     */
    @GetMapping(Api.RAIZ + "/seguridad/sesion")
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    public IdentidadResource identidadDeLaSesion() {
        return IdentidadResource.de(identidad.actual());
    }

    /**
     * A quien pertenecen las cifras de la pantalla: la municipalidad de la sesion (#555, RF-121).
     *
     * <p><b>Sin ningun parametro, y eso es la mitad de la decision.</b> Admitir un identificador
     * convertiria esta lectura en un directorio de municipalidades —quien pregunta elegiria de
     * quien pregunta— y el aislamiento pasaria a depender de que nadie cambie un numero en la barra
     * de direcciones. El identificador sale del token, se fija una vez con {@code SET LOCAL} y lo
     * compara el {@code WHERE} de la consulta (regla 2, ARQ-03 §3.1). Un parametro de mas ni
     * siquiera se ignora: {@code GuardiaDeParametros} lo rechaza con {@code 422} nombrandolo
     * (#539).
     *
     * <p>Declara el centinela {@link RequiereAcceso#SESION_PROPIA}, igual que {@link
     * #permisosDeLaSesion()} y por el mismo motivo (ADR-0013): <b>no es una opcion del catalogo</b>
     * y no hay privilegio que configurar. El rotulo de la entidad no es de un modulo, es del
     * sistema entero: sin el, las doce pantallas quedan sin decir de quien son sus cifras, y quien
     * no tenga ningun permiso tampoco puede leer mal nada por saber en que municipalidad esta —lo
     * sabe ya, se lo dice su propio token—.
     */
    @GetMapping(Api.RAIZ + "/seguridad/sesion/municipalidad")
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    public MunicipalidadResource municipalidadDeLaSesion() {
        return MunicipalidadResource.de(municipalidad.actual());
    }

    /**
     * La matriz de permisos efectivos del usuario en curso: por cada opcion del catalogo sobre la
     * que tiene algun privilegio, la lista de privilegios (RF-121, ADR-0013). La interfaz la usa
     * para dibujar el menu.
     *
     * <p><b>No es una opcion del catalogo</b>: leer los permisos propios no revela nada que no se
     * pueda enumerar probando cada endpoint (REQ-03 §5). Por eso declara el centinela {@link
     * RequiereAcceso#SESION_PROPIA}, que el guardia deja pasar con solo un token valido. Un usuario
     * sin ningun permiso recibe {@code {}}, no un 403.
     */
    @GetMapping(Api.RAIZ + "/seguridad/sesion/permisos")
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    public Map<String, List<String>> permisosDeLaSesion() {
        Map<String, List<String>> salida = new LinkedHashMap<>();
        permisos.efectivos()
                .forEach(
                        (opcion, privilegios) ->
                                salida.put(
                                        opcion,
                                        privilegios.stream()
                                                .sorted()
                                                .map(Privilegio::columna)
                                                .toList()));
        return salida;
    }

    /**
     * Cambia el ejercicio de trabajo de la sesion.
     *
     * <p>El cuerpo lleva el ejercicio y la observacion, y nada mas. En particular <b>no lleva
     * municipalidad</b>, ni la lleva la ruta: el contexto sale del token (ADR-0005) y esta pantalla
     * no puede cambiarlo. Hay una regla de ArchUnit que lo verifica sobre todos los controladores.
     */
    @PutMapping(Api.RAIZ + "/seguridad/sesion/ejercicio")
    @RequiereAcceso(acceso = "cambiar_anio", privilegio = Privilegio.ESPECIAL)
    public SesionResource cambiarEjercicio(@RequestBody CambioDeEjercicio cambio) {
        Sesion sesion =
                administrar.cambiarEjercicioDeTrabajo(
                        new Ejercicio(cambio.ejercicio()), Observacion.de(cambio.observacion()));
        return SesionResource.de(sesion);
    }

    /**
     * Inicia el cambio de contrasena.
     *
     * <p><b>No recibe ninguna contrasena, ni la vieja ni la nueva</b>, y esa ausencia es la
     * garantia: no hay forma de que llegue al servidor porque no hay donde ponerla. Lo que devuelve
     * es a donde tiene que ir la interfaz, que es el proveedor de identidad (ADR-0005).
     */
    @PutMapping(Api.RAIZ + "/seguridad/usuarios/{id}/clave")
    @RequiereAcceso(acceso = "cambiar_clave", privilegio = Privilegio.MODIFICACION)
    public CambioDeClaveIniciado cambiarClave(
            @PathVariable("id") long usuario, @RequestBody SolicitudDeCambioDeClave solicitud) {

        String destino =
                administrar.iniciarCambioDeClave(usuario, Observacion.de(solicitud.observacion()));
        return new CambioDeClaveIniciado("PROVEEDOR_DE_IDENTIDAD", destino);
    }

    /**
     * Consulta de auditoria (RF-124).
     *
     * <p>El ejercicio es obligatorio: es la clave de particion, y sin el la consulta recorre todas.
     * Con el volumen que alcanza esta tabla eso es la diferencia entre una pantalla que responde y
     * una que hay que cancelar.
     *
     * <p>Los cinco filtros que acota son los cinco que el contrato declara, y no cuatro ni seis
     * (#544): hasta este issue el contrato publicaba {@code accion} —que ningun parametro de aqui
     * leia, asi que se tecleaba y devolvia el listado entero— y callaba {@code tabla} y {@code
     * operacion}, que acotan desde #13.
     */
    @GetMapping(Api.RAIZ + "/seguridad/auditoria")
    @RequiereAcceso(acceso = "auditoria", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<AuditoriaResource> auditoria(
            @RequestParam("ejercicio") int ejercicio,
            @RequestParam(name = "usuario", required = false) @Nullable String usuario,
            @RequestParam(name = "tabla", required = false) @Nullable String tabla,
            @RequestParam(name = "operacion", required = false) @Nullable String operacion,
            @RequestParam(name = "desde", required = false) @Nullable LocalDate desde,
            @RequestParam(name = "hasta", required = false) @Nullable LocalDate hasta,
            ParametrosDePaginacion paginacion) {

        ConsultaDeAuditoria consulta =
                new ConsultaDeAuditoria(
                        new Ejercicio(ejercicio),
                        usuario,
                        tabla,
                        operacionOpcional(operacion),
                        desde,
                        hasta);

        return RespuestaPaginada.de(
                administrar.auditoria(consulta, paginacion.aPaginacion("fecha")),
                AuditoriaResource::de);
    }

    /**
     * La palabra del filtro «Acción», resuelta contra el vocabulario que la bitacora guarda.
     *
     * <p>Una palabra que el enumerado no tiene <b>se rechaza</b> en vez de acotar por ella: la
     * consulta seria {@code operacion = 'ELIMINACION'}, que no puede casar con nada, y lo que
     * llegaria a la pantalla es una tabla vacia —indistinguible de «no hubo ninguna»— en vez de un
     * error. Es el mismo trato que {@code LicenciaController} le da a su «Estado», y el motivo por
     * el que el contrato publica el enumerado entero.
     *
     * <p>Sin valor no hay filtro, que es lo que significa el «Todas» del desplegable; esa palabra
     * <b>no se traduce aqui</b>: no viaja (ver {@code pantallas/seguridad}).
     */
    private static @Nullable Operacion operacionOpcional(@Nullable String operacion) {
        String texto = operacion == null ? "" : operacion.strip();
        if (texto.isEmpty()) {
            return null;
        }
        try {
            return Operacion.valueOf(texto.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La operacion va entre "
                            + Arrays.stream(Operacion.values())
                                    .map(Enum::name)
                                    .collect(Collectors.joining(", "))
                            + ": '"
                            + operacion
                            + "'");
        }
    }

    /**
     * Estado de las copias de seguridad (RF-126).
     *
     * <p>Es un {@code POST} porque asi lo declara el contrato, derivado de la pantalla del
     * prototipo; lo que hace es <b>consultar</b>. La aplicacion no ejecuta respaldos: quien los
     * hace es el proceso de despliegue, y darle a {@code sgtm_app} lo que haria falta para
     * respaldar seria deshacer la separacion de privilegios de ARQ-03 §4.
     */
    @PostMapping(Api.RAIZ + "/seguridad/respaldos")
    @RequiereAcceso(acceso = "respaldo", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<RespaldoResource> respaldos(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                administrar.respaldos(paginacion.aPaginacion("inicio")), RespaldoResource::de);
    }

    // ------------------------------------------------------------------

    /**
     * El rotulo de la entidad, tal como sale impreso.
     *
     * <p>{@code nombre} es la columna <b>verbatim</b>: el nombre completo, con su tipo delante
     * —«Municipalidad Distrital de Catacaos»—, que es lo que declara la implantacion y lo que
     * encabeza los documentos. {@code tipo} va aparte para quien necesite distinguir una distrital
     * de una provincial, y <b>no se antepone</b>: componer «Municipalidad » + tipo + « de » +
     * nombre da «Municipalidad Distrital de Municipalidad Distrital de Catacaos», y eso no se ve
     * hasta que esta impreso.
     *
     * <p>{@code ubigeo} lo pide un segundo consumidor: el alta de predio de Catastro prefijaba el
     * distrito con seis digitos compilados, y los del padron de la piloto son otros.
     */
    public record MunicipalidadResource(long id, String ubigeo, String nombre, String tipo) {

        static MunicipalidadResource de(Municipalidad municipalidad) {
            return new MunicipalidadResource(
                    municipalidad.id(),
                    municipalidad.ubigeo(),
                    municipalidad.nombre(),
                    municipalidad.tipo());
        }
    }

    /**
     * Quien es la sesion, en cuatro campos.
     *
     * <p>{@code usuarioId} es el de <b>esta</b> municipalidad: la misma persona con la misma cuenta
     * en dos municipalidades son dos filas de {@code usuario} y dos identificadores distintos, y
     * por eso no puede salir del token —que trae la cuenta— sino de la consulta.
     *
     * <p>{@code ejercicioDeTrabajo} es <b>nulo</b> mientras nadie lo haya fijado con {@code PUT
     * /seguridad/sesion/ejercicio}, y eso no es una falta de dato: es la respuesta. Ponerle el año
     * del reloj del servidor afirmaria que alguien lo eligio, y lo que #557 tiene que poder separar
     * es exactamente eso —el filtro de vista, que es local y no necesita permiso, del acto
     * registrado con su observacion y su privilegio {@code ESPECIAL}—. Un cero o un año por omision
     * aqui hacen que las dos cosas vuelvan a ser la misma.
     */
    public record IdentidadResource(
            long usuarioId, String cuenta, String nombre, @Nullable Integer ejercicioDeTrabajo) {

        static IdentidadResource de(Identidad identidad) {
            return new IdentidadResource(
                    identidad.usuarioId(),
                    identidad.cuenta(),
                    identidad.nombre(),
                    identidad.ejercicioDeTrabajo() == null
                            ? null
                            : identidad.ejercicioDeTrabajo().valor());
        }
    }

    /** Cuerpo del cambio de ejercicio. Sin municipalidad: no la acepta y no la necesita. */
    public record CambioDeEjercicio(int ejercicio, String observacion) {}

    /** Cuerpo del cambio de clave. <b>Sin ningun campo de contrasena</b>, a proposito. */
    public record SolicitudDeCambioDeClave(String observacion) {}

    public record CambioDeClaveIniciado(String gestionadaPor, String destino) {}

    public record SesionResource(
            long id, long usuarioId, Instant inicio, @Nullable Integer ejercicioDeTrabajo) {

        static SesionResource de(Sesion sesion) {
            return new SesionResource(
                    sesion.id(),
                    sesion.usuarioId(),
                    sesion.inicio(),
                    sesion.ejercicioDeTrabajo() == null
                            ? null
                            : sesion.ejercicioDeTrabajo().valor());
        }
    }

    /** Lo que el manual pide ver: quien, desde que maquina e IP, cuando, sobre que y por que. */
    public record AuditoriaResource(
            long id,
            int ejercicio,
            String tabla,
            String clave,
            String operacion,
            String usuario,
            @Nullable String origenEquipo,
            @Nullable String origenIp,
            Instant fecha,
            String observacion,
            @Nullable String datosAnteriores,
            @Nullable String datosNuevos) {

        static AuditoriaResource de(RegistroAuditado registro) {
            return new AuditoriaResource(
                    registro.id(),
                    registro.ejercicio().valor(),
                    registro.tabla(),
                    registro.clave(),
                    registro.operacion(),
                    registro.usuario(),
                    registro.origenEquipo(),
                    registro.origenIp(),
                    registro.fecha(),
                    registro.observacion(),
                    registro.datosAnteriores(),
                    registro.datosNuevos());
        }
    }

    /**
     * Una copia de seguridad, con lo unico que de verdad contesta la pregunta de la pantalla.
     *
     * <p>{@code ultimaRestauracionVerificada} es el instante en que se comprobo, restaurandola de
     * verdad, que esta copia se puede restaurar (RNF-079, #558), y {@code
     * ultimaRestauracionVerificadaPor} dice que proceso lo comprobo. <b>Los dos son nulos mientras
     * nadie la haya probado</b>, que es el estado de casi todas: un cero o un {@code false} aqui se
     * leerian como una medicion y llevarian a no auditar una copia que no se restauro nunca.
     *
     * <p>No se publica ningun derivado —ni «hace N dias», ni «probada si/no»—: la fecha de hoy no
     * la pone el servidor en una cifra que despues se lee sin ella (regla 9), y un booleano
     * perderia el unico dato con el que se decide si la comprobacion sigue valiendo.
     */
    public record RespaldoResource(
            long id,
            Instant inicio,
            @Nullable Instant fin,
            String resultado,
            String destino,
            @Nullable Long tamanoBytes,
            @Nullable String detalle,
            @Nullable Instant ultimaRestauracionVerificada,
            @Nullable String ultimaRestauracionVerificadaPor) {

        static RespaldoResource de(Respaldo respaldo) {
            return new RespaldoResource(
                    respaldo.id(),
                    respaldo.inicio(),
                    respaldo.fin(),
                    respaldo.resultado(),
                    respaldo.destino(),
                    respaldo.tamanoBytes(),
                    respaldo.detalle(),
                    respaldo.ultimaRestauracionVerificada(),
                    respaldo.ultimaRestauracionVerificadaPor());
        }
    }
}
