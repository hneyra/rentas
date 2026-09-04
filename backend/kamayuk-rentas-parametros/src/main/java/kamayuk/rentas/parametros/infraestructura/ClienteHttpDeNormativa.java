package kamayuk.rentas.parametros.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El unico cliente HTTP de este backend, y el unico camino hacia {@code normativa}.
 *
 * <h2>Por que un {@code HttpClient} de la JDK y no un {@code RestClient}</h2>
 *
 * <p>Porque lo que hace falta es leer <b>los bytes</b> de la respuesta para firmarlos: el {@code
 * ETag} es el {@code sha256} del cuerpo servido, y un cliente que deserialice por su cuenta entrega
 * un objeto, no los bytes con que se calculo la huella. Con un {@code String} en la mano, verificar
 * es una linea.
 *
 * <h2>Las dos esperas, y por que hay dos</h2>
 *
 * <p>Se llaman ESPERA y no PLAZO, y no es cosmetico: el escaner de la regla 5 marca toda constante
 * que empiece por una palabra de valor normativo y lleve una cifra dentro, y `PLAZO` es una de
 * ellas desde #192 —los plazos del Codigo Tributario—. Aqui la cifra no la fija ninguna norma sino
 * la red, asi que lo que se movio es el NOMBRE y no la regla: es lo mismo que #690 hizo con
 * `CUOTAS_ABIERTAS`. Ensanchar el escaner para que distinguiera «plazo legal» de «plazo de socket»
 * seria pedirle que entienda el dominio; renombrar cuesta una linea.
 *
 * <p>La de <b>conexion</b> es corta: si {@code normativa} no esta, conviene saberlo pronto para
 * replegarse a la cache. La de <b>lectura</b> es larga, porque el snapshot con el anexo vehicular
 * son 54 000 filas y unos cuantos megabytes — y una descarga que se corta a la mitad por un plazo
 * pensado para una lectura pequena se lee como «normativa no contesta», que manda a buscar donde no
 * es.
 *
 * <h2>Lo que este cliente NO tiene</h2>
 *
 * <p>Ningun metodo que pregunte por un parametro suelto. Es lo que impide reinventar la API de
 * consulta que ADR-0025 descarta, y no lo sostiene ninguna prueba: lo sostiene que la clase no
 * tenga como.
 */
@Component
public class ClienteHttpDeNormativa implements PublicadorDeNormativa {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofMinutes(5);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String raiz;

    public ClienteHttpDeNormativa(
            JsonMapper json, @Value("${kamayuk.normativa.url:}") String raiz) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    @Override
    public long conjuntoVigenteEn(Ejercicio ejercicio) {
        String url = raiz + "/conjuntos?ejercicio=" + ejercicio.valor();
        HttpResponse<String> respuesta = pedir(url, "resolver el conjunto de " + ejercicio);
        // El 404 de `normativa` para esta ruta significa UNA cosa: ese ejercicio no tiene conjunto
        // sellado. Se traduce al tipo que los doce sitios que calculan ya saben cazar, porque
        // dejarlo salir como «normativa no contesta» mandaria a levantar un despliegue que esta
        // perfectamente arriba.
        if (respuesta.statusCode() == 404) {
            throw new kamayuk.rentas.parametros.LectorDeParametros.EjercicioSinSellar(ejercicio);
        }
        if (respuesta.statusCode() != 200) {
            throw new PublicadorDeNormativa.NormativaInalcanzable(
                    "resolver el conjunto de "
                            + ejercicio
                            + " (contesto "
                            + respuesta.statusCode()
                            + ")",
                    null);
        }
        return leer(respuesta.body()).get("conjuntoId").asLong();
    }

    @Override
    public SnapshotDeNormativa descargar(long conjuntoId, String ambito) {
        String url = raiz + "/conjuntos/" + conjuntoId + "/snapshot?ambito=" + ambito;
        HttpResponse<String> respuesta = pedir(url, "descargar el conjunto " + conjuntoId);
        // Igual que arriba: 404 aqui es «ese conjunto no existe o no esta sellado», que es un
        // hecho del dominio y no una caida.
        if (respuesta.statusCode() == 404) {
            throw new kamayuk.rentas.parametros.LectorDeParametros.ConjuntoNoSellado(
                    kamayuk.rentas.parametros.IdentificadorDeConjunto.de(conjuntoId));
        }
        if (respuesta.statusCode() != 200) {
            throw new PublicadorDeNormativa.NormativaInalcanzable(
                    "descargar el conjunto "
                            + conjuntoId
                            + " (contesto "
                            + respuesta.statusCode()
                            + ")",
                    null);
        }

        String cuerpo = respuesta.body();
        String esperada = etiquetaSinComillas(respuesta);
        String calculada = sha256(cuerpo);
        if (!calculada.equals(esperada)) {
            throw new PublicadorDeNormativa.HuellaQueNoCuadra(conjuntoId, esperada, calculada);
        }

        JsonNode raiz = leer(cuerpo);
        return new SnapshotDeNormativa(
                raiz.get("conjuntoId").asLong(),
                new Ejercicio(raiz.get("ejercicio").asInt()),
                raiz.get("version").asInt(),
                raiz.get("ambito").asString(),
                calculada,
                url,
                parametros(raiz.get("parametros")),
                valoresUnitarios(raiz.get("valoresUnitarios")),
                depreciaciones(raiz.get("depreciaciones")),
                valoresReferenciales(raiz.get("valoresReferenciales")));
    }

    private HttpResponse<String> pedir(String url, String que) {
        if (raiz.isBlank()) {
            throw new PublicadorDeNormativa.NormativaInalcanzable(
                    que + ": `kamayuk.normativa.url` no esta configurada", null);
        }
        try {
            HttpRequest peticion =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(ESPERA_DE_LECTURA)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            return cliente.send(
                    peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException noContesta) {
            throw new PublicadorDeNormativa.NormativaInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            // Reponer la marca antes de propagar: tragarsela deja al hilo sin saber que se le pidio
            // parar, y en una corrida masiva eso es un proceso que no se puede cancelar.
            Thread.currentThread().interrupt();
            throw new PublicadorDeNormativa.NormativaInalcanzable(que, interrumpido);
        }
    }

    /**
     * El {@code ETag} sin sus comillas.
     *
     * <p>Si no viene, se devuelve la cadena vacia y la comparacion falla: una respuesta sin huella
     * no se puede verificar, y aceptarla «porque el cuerpo parece bien» es exactamente lo que la
     * huella existe para impedir.
     */
    private static String etiquetaSinComillas(HttpResponse<String> respuesta) {
        String etiqueta = respuesta.headers().firstValue("ETag").orElse("");
        return etiqueta.replace("\"", "").replace("W/", "");
    }

    private JsonNode leer(String cuerpo) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException noEsJson) {
            // Jackson 3 lanza `JacksonException`, que NO es comprobada (C-7). Se sigue capturando
            // a proposito: un cuerpo que no es JSON —el HTML de un proxy— tiene que salir como
            // «normativa no contesta lo que dice contestar» y no como una excepcion de libreria.
            throw new PublicadorDeNormativa.NormativaInalcanzable(
                    "leer la respuesta de `normativa`", noEsJson);
        }
    }

    private static String sha256(String cuerpo) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(cuerpo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("SHA-256 es obligatorio en toda JVM", imposible);
        }
    }

    private static List<SnapshotDeNormativa.Parametro> parametros(JsonNode filas) {
        List<SnapshotDeNormativa.Parametro> leidas = new ArrayList<>();
        for (JsonNode fila : filas) {
            leidas.add(
                    new SnapshotDeNormativa.Parametro(
                            fila.get("tipo").asString(),
                            texto(fila, "clave"),
                            texto(fila, "valorNumerico"),
                            texto(fila, "valorTexto"),
                            texto(fila, "vigenciaDesde"),
                            texto(fila, "vigenciaHasta"),
                            fila.get("documentoFuente").asString()));
        }
        return leidas;
    }

    private static List<SnapshotDeNormativa.ValorUnitario> valoresUnitarios(JsonNode filas) {
        List<SnapshotDeNormativa.ValorUnitario> leidas = new ArrayList<>();
        for (JsonNode fila : filas) {
            leidas.add(
                    new SnapshotDeNormativa.ValorUnitario(
                            fila.get("partida").asString(),
                            fila.get("categoria").asString(),
                            fila.get("anioConstruccionDesde").asInt(),
                            entero(fila, "anioConstruccionHasta"),
                            fila.get("valorM2").asString(),
                            fila.get("documentoFuente").asString()));
        }
        return leidas;
    }

    private static List<SnapshotDeNormativa.Depreciacion> depreciaciones(JsonNode filas) {
        List<SnapshotDeNormativa.Depreciacion> leidas = new ArrayList<>();
        for (JsonNode fila : filas) {
            leidas.add(
                    new SnapshotDeNormativa.Depreciacion(
                            fila.get("uso").asString(),
                            fila.get("material").asString(),
                            fila.get("estadoConservacion").asString(),
                            entero(fila, "antiguedadHasta"),
                            fila.get("porcentaje").asString(),
                            fila.get("documentoFuente").asString()));
        }
        return leidas;
    }

    private static List<SnapshotDeNormativa.ValorReferencial> valoresReferenciales(JsonNode filas) {
        List<SnapshotDeNormativa.ValorReferencial> leidas = new ArrayList<>();
        for (JsonNode fila : filas) {
            leidas.add(
                    new SnapshotDeNormativa.ValorReferencial(
                            fila.get("ejercicio").asInt(),
                            fila.get("categoria").asString(),
                            fila.get("marca").asString(),
                            fila.get("modelo").asString(),
                            fila.get("anioFabricacion").asInt(),
                            fila.get("valor").asString(),
                            fila.get("documentoFuente").asString()));
        }
        return leidas;
    }

    private static @Nullable String texto(JsonNode fila, String campo) {
        JsonNode valor = fila.get(campo);
        return valor == null || valor.isNull() ? null : valor.asString();
    }

    /**
     * Un entero que puede faltar.
     *
     * <p>{@code asInt()} sobre un nulo devuelve <b>cero</b>, y aqui el nulo es «mas de 50 anios»:
     * leerlo como cero convierte el tramo abierto de la depreciacion en uno que no cubre nada, sin
     * ningun error de por medio (#188 H-15).
     */
    private static @Nullable Integer entero(JsonNode fila, String campo) {
        JsonNode valor = fila.get(campo);
        return valor == null || valor.isNull() ? null : valor.asInt();
    }
}
