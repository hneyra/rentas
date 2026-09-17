package kamayuk.rentas.seguridad.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.RespuestaAjena;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cliente HTTP del buzon de {@code identidad} (ADR-0039 etapa 4; {@code EventosController} de
 * {@code identidad}): {@code GET /eventos/pendientes?limite=} y {@code POST /eventos/acuses}.
 *
 * <h2>Con la misma credencial de servicio que el ingestor, y una segunda clave</h2>
 *
 * <p>El token lo pide {@code TokenDeServicioDeKeycloak} con {@code client_credentials}, igual que
 * el ingestor del padron hacia {@code catastro}: el mismo cliente confidencial —{@code
 * kamayuk-rentas-servicio-<ubigeo>}, uno por municipalidad (ADR-0028 §2)— y la clave que el
 * despliegue le da para ESTE destino ({@code KAMAYUK_IDENTIDAD_CREDENCIAL}). Por eso el proveedor
 * del token se mudo a {@code plataforma}: dos contextos lo consumen y Spring Modulith no expone los
 * sub-paquetes de {@code nucleo}. Lo que cambia entre los dos es el secreto, no el mecanismo.
 *
 * <h2>Todo fallo es transitorio, incluido el 401 y el 403</h2>
 *
 * <p>Lo que aqui no puede pasar es que un fallo de transporte se lleve un evento. Un 401 es una
 * clave que no vale o que no esta; un 403 es {@code identidad} negandose, y <b>hay tres 403
 * distintos</b>. Los dos se arreglan del lado del despliegue y cambian solos; mientras tanto la
 * corrida sale en rojo diciendo cual de los dos es, y la copia se queda como estaba.
 *
 * <h2>El 403 NO SE ADIVINA: se lee del cuerpo (#66)</h2>
 *
 * <p><b>Hasta #66 este cliente mapeaba cualquier 403 a «falta afiliarla al grupo «Consumidores del
 * buzon»» y tiraba el cuerpo</b>, que es lo unico que lo distinguiria. Medido en el cluster de
 * `stg` el 2026-09-11: lo que `identidad` contestaba era la rama <b>contraria</b> —{@code
 * SIN_PRIVILEGIO} «la cuenta no esta dada de alta en este sistema», porque el token salia sin
 * {@code preferred_username} y el guardia caia al {@code sub}—, y la afiliacion al grupo, a donde
 * el mensaje mandaba a mirar, estaba perfectamente bien. Se perdieron horas mirando el sitio
 * equivocado.
 *
 * <p>Los tres 403 de `identidad` y sus remedios, que son distintos:
 *
 * <ul>
 *   <li>{@code SIN_PRIVILEGIO} + «no esta dada de alta» — la cuenta no tiene ficha: falta el alta,
 *       o el claim con que se la busca. Afiliarla a un grupo <b>no lo arregla</b>.
 *   <li>{@code SIN_PRIVILEGIO} + «no tiene el privilegio» — la cuenta existe: falta afiliarla al
 *       grupo «Consumidores del buzon», que nace sin miembros a proposito.
 *   <li>{@code SIN_IDENTIDAD_DE_SERVICIO} — el token no identifica una cuenta de servicio: no falta
 *       ningun permiso, hay que pedir otro token con el cliente confidencial que toca.
 * </ul>
 *
 * <p>Y si no dijo cual, el mensaje <b>lo dice</b> en vez de elegir uno. Lo que contesto viaja
 * siempre, tachado y recortado por {@link RespuestaAjena}.
 *
 * <p>Lo unico que no es transitorio es un acuse que el emisor rechaza por su contenido (422): eso
 * es {@link FuenteDeEventosDeIdentidad.AcuseRechazado}, se registra y no se reintenta.
 */
public class ClienteHttpDelBuzonDeIdentidad implements FuenteDeEventosDeIdentidad {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    /** Las dos rutas del buzon, relativas a la raiz de la API de `identidad`. */
    static final String PENDIENTES = "/eventos/pendientes";

    static final String ACUSES = "/eventos/acuses";

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String raiz;
    private final CredencialDeServicio credencial;

    /**
     * @param raiz la raiz de la API de `identidad`, con su prefijo: {@code
     *     http://…/identidad/api/v1}
     */
    public ClienteHttpDelBuzonDeIdentidad(
            JsonMapper json, String raiz, CredencialDeServicio credencial) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.credencial = credencial;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    @Override
    public Lote pendientes(int limite) {
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + PENDIENTES + "?limite=" + limite))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Accept", "application/json")
                        .GET();
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, "leer el buzon de identidad");
        if (respuesta.statusCode() != 200) {
            throw noContesto(respuesta.statusCode(), respuesta.body(), "leer el buzon");
        }
        JsonNode cuerpo = arbol(respuesta.body(), "leer el buzon");
        List<EventoDeIdentidadRecibido> eventos = new ArrayList<>();
        for (JsonNode evento : cuerpo.path("eventos")) {
            eventos.add(leer(evento));
        }
        return new Lote(List.copyOf(eventos), cuerpo.path("quedan").asLong(0));
    }

    @Override
    public Acuse acusar(List<UUID> eventoIds) {
        if (eventoIds.isEmpty()) {
            return new Acuse(0, 0, 0);
        }
        List<String> ids = new ArrayList<>();
        for (UUID id : eventoIds) {
            ids.add(id.toString());
        }
        String cuerpo = escribir(new PeticionDeAcuse(List.copyOf(ids)));
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ACUSES))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, "acusar los eventos aplicados");
        int estado = respuesta.statusCode();
        if (estado == 200) {
            JsonNode acuse = arbol(respuesta.body(), "acusar");
            return new Acuse(
                    acuse.path("recibidos").asInt(0),
                    acuse.path("escritos").asInt(0),
                    acuse.path("quedan").asLong(0));
        }
        if (esDeNegocio(estado)) {
            // Lo escribio `identidad` para decir que rechazo; se guarda entero porque es lo unico
            // que dice CUAL de los identificadores no le cuadro. Es un `problem+json`, no un
            // eco de lo que se le mando.
            throw new AcuseRechazado(estado, respuesta.body());
        }
        throw noContesto(
                estado,
                respuesta.body(),
                "acusar. Los eventos SI estan resueltos aqui: se volveran a servir y se"
                        + " descartaran por deduplicacion");
    }

    // ------------------------------------------------------------------

    /** Un 4xx que no es de credencial: el emisor entendio la peticion y la rechazo. */
    private static boolean esDeNegocio(int estado) {
        return estado >= 400 && estado < 500 && estado != 401 && estado != 403;
    }

    /**
     * El mensaje de un estado que no es 200, con lo que el emisor contesto DENTRO.
     *
     * <p>No es estatico porque necesita el {@link JsonMapper}: leer el {@code codigo} del cuerpo es
     * la diferencia entre decir la rama que es y adivinar una.
     */
    private IdentidadNoContesta noContesto(int estado, String cuerpo, String que) {
        RespuestaAjena contesto = RespuestaAjena.de(json, cuerpo);
        String loQueDijo = ", y contesto " + contesto.comoTexto();
        return switch (estado) {
            case 401 ->
                    new IdentidadNoContesta(
                            "`identidad` contesto 401 al "
                                    + que
                                    + loQueDijo
                                    + ". La credencial de servicio no vale o no se manda"
                                    + " (kamayuk.identidad.cliente, kamayuk.identidad.credencial)."
                                    + " Se arregla en el despliegue, no en el evento");
            case 403 ->
                    new IdentidadNoContesta(
                            "`identidad` contesto 403 al "
                                    + que
                                    + loQueDijo
                                    + ". "
                                    + remedioDel403(contesto));
            default ->
                    new IdentidadNoContesta(
                            "`identidad` contesto " + estado + " al " + que + loQueDijo);
        };
    }

    /**
     * Que hay que ir a arreglar, segun lo que el emisor DIJO y no segun lo que se supone.
     *
     * <p>Los dos {@code SIN_PRIVILEGIO} de `identidad` llevan el mismo codigo —lo separa su texto,
     * {@code GuardiaDeAcceso} desde #29 §8—, asi que aqui se mira el detalle. Es fragil por
     * definicion, y por eso el remedio va <b>detras</b> del cuerpo literal: si el emisor reescribe
     * su frase, quien lee el registro sigue teniendo delante lo que contesto de verdad.
     */
    private static String remedioDel403(RespuestaAjena contesto) {
        if (contesto.sinDecirPorQue()) {
            return "Y NO dijo cual de sus tres 403 es, asi que aqui no se elige ninguno: el"
                    + " remedio sale del «codigo», y sin el hay que mirar quien contesto —puede"
                    + " no ser `identidad` sino un proxy delante";
        }
        if ("SIN_IDENTIDAD_DE_SERVICIO".equals(contesto.codigo())) {
            return "El token NO identifica una cuenta de servicio de un sistema: no falta ningun"
                    + " permiso y afiliar a nadie a ningun grupo no lo arregla — hay que pedir el"
                    + " token con el cliente confidencial que toca (kamayuk.identidad.cliente,"
                    + " kamayuk.identidad.credencial)";
        }
        if (contesto.dice("no esta dada de alta")) {
            return "La cuenta NO tiene ficha en `identidad`: NO se arregla afiliandola a ningun"
                    + " grupo — o falta el alta, o el claim con que `identidad` la busca no es el"
                    + " que el token trae (un `sub` en vez de `preferred_username` es la firma de"
                    + " un realm con los ambitos incompletos)";
        }
        if (contesto.dice("privilegio")) {
            return "La cuenta de servicio de `rentas` existe y no tiene el acceso «eventos» —"
                    + " falta afiliarla al grupo «Consumidores del buzon» de su municipalidad en"
                    + " `identidad`. Se arregla en el despliegue, no en el evento";
        }
        return "El «codigo» que contesto no es ninguno de los tres que este cliente sabe leer:"
                + " el remedio esta en lo que dijo, arriba";
    }

    private EventoDeIdentidadRecibido leer(JsonNode evento) {
        try {
            return new EventoDeIdentidadRecibido(
                    UUID.fromString(evento.path("eventoId").asString()),
                    evento.path("secuencia").asLong(),
                    evento.path("tipo").asString(""),
                    evento.path("sujetoId").asLong(),
                    evento.path("cuerpo").asString(""),
                    evento.path("huella").asString(""),
                    Instant.parse(evento.path("creadoEn").asString()));
        } catch (IllegalArgumentException | DateTimeParseException malFormado) {
            throw new IdentidadNoContesta(
                    "El buzon de `identidad` contesto algo que no tiene la forma de un evento: "
                            + malFormado.getMessage(),
                    malFormado);
        }
    }

    private JsonNode arbol(String cuerpo, String que) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException ilegible) {
            throw new IdentidadNoContesta(
                    "`identidad` contesto algo que no es JSON al " + que, ilegible);
        }
    }

    /**
     * Pone la cabecera si la hay. Se pide en cada peticion y no al construir: un token caduca.
     *
     * <p>Un fallo al pedir el token llega como {@link CredencialDeServicio.NoSePudoObtener}, que es
     * de quien llama y no de a quien se llama; aqui se traduce a la excepcion de este puerto con el
     * mismo significado: transitorio, se arregla en el despliegue.
     */
    private void conCredencial(HttpRequest.Builder peticion) {
        String cabecera;
        try {
            cabecera = credencial.cabecera();
        } catch (CredencialDeServicio.NoSePudoObtener sinToken) {
            throw new IdentidadNoContesta(
                    "No se pudo pedir el token de servicio para leer el buzon de `identidad`: "
                            + sinToken.getMessage(),
                    sinToken);
        }
        if (!cabecera.isBlank()) {
            peticion.header("Authorization", cabecera);
        }
    }

    private HttpResponse<String> enviar(HttpRequest.Builder peticion, String que) {
        try {
            return cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException noContesta) {
            throw new IdentidadNoContesta("No se pudo " + que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IdentidadNoContesta("Se interrumpio al " + que, interrumpido);
        }
    }

    private String escribir(Object cuerpo) {
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JacksonException noSePuede) {
            throw new IllegalStateException("No se pudo componer el acuse", noSePuede);
        }
    }

    /** Lo que se manda al acusar: la forma de {@code EventosController.PeticionDeAcuse}. */
    private record PeticionDeAcuse(List<String> eventos) {}
}
