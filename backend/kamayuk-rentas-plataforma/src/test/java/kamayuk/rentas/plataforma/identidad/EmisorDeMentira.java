package kamayuk.rentas.plataforma.identidad;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

/**
 * Un emisor OIDC de verdad, diminuto, para las pruebas de la cadena de identidad.
 *
 * <h2>Por que un servidor y no un {@code JwtDecoder} de mentira</h2>
 *
 * <p>Sustituir el decodificador por uno propio en el contexto de prueba deja sin verificar
 * justamente lo que se quiere verificar: que la propiedad {@code issuer-uri} esta puesta, que
 * Spring hace el descubrimiento, que se trae el juego de claves, que valida la firma contra esa
 * clave y que rechaza un {@code iss} distinto. Todo eso son lineas de configuracion —el sitio donde
 * se cometen estos fallos— y un decodificador inyectado a mano las salta todas.
 *
 * <p>Asi que esto es lo que Keycloak seria: publica {@code /.well-known/openid-configuration} y un
 * juego de claves, y firma tokens. Lo que <b>no</b> hace es autenticar a nadie: no hay flujo de
 * codigo, ni pantalla, ni usuarios. Aqui el token se pide y se recibe, porque lo que esta bajo
 * prueba es el servidor de recursos, no el emisor.
 *
 * <p>Cada instancia tiene su puerto y sus claves, asi que dos instancias son dos emisores distintos
 * de verdad, con {@code iss} distinto y firmas distintas. Es lo que permite la prueba del token
 * ajeno sin simular nada.
 *
 * <h2>Por que HTTP a mano y no {@code com.sun.net.httpserver}</h2>
 *
 * <p>Porque el paquete {@code com.sun} esta prohibido por Checkstyle, y la prohibicion es buena: no
 * conviene que una excepcion abierta «solo para una prueba» sea la puerta por la que despues entre
 * una clase interna del JDK en produccion. Lo que hace falta aqui son dos respuestas fijas, y eso
 * cabe en un socket y treinta lineas.
 */
public final class EmisorDeMentira implements AutoCloseable {

    /** Vida del token cuando no se dice otra cosa. Larga para que no venza a mitad de la prueba. */
    private static final int DURACION_SEGUNDOS = 600;

    private final ServerSocket puerta;
    private final Thread atencion;
    private final String realm;
    private final String emisor;
    private final RSAKey clavePublicada;
    private final RSAKey claveNoPublicada;

    private EmisorDeMentira(
            ServerSocket puerta, String realm, RSAKey clavePublicada, RSAKey claveNoPublicada) {
        this.puerta = puerta;
        this.realm = realm;
        this.emisor = "http://127.0.0.1:" + puerta.getLocalPort() + "/realms/" + realm;
        this.clavePublicada = clavePublicada;
        this.claveNoPublicada = claveNoPublicada;
        this.atencion = Thread.ofVirtual().name("emisor-" + realm).unstarted(this::atender);
    }

    /**
     * Arranca el emisor en un puerto libre de la interfaz local.
     *
     * @param realm nombre del realm, que forma parte del emisor igual que en Keycloak
     */
    public static EmisorDeMentira arrancar(String realm) throws IOException, JOSEException {
        RSAKey publicada = new RSAKeyGenerator(2048).keyID("publicada-" + realm).generate();
        RSAKey noPublicada = new RSAKeyGenerator(2048).keyID("ajena-" + realm).generate();

        ServerSocket puerta = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        EmisorDeMentira instancia = new EmisorDeMentira(puerta, realm, publicada, noPublicada);
        instancia.atencion.start();
        return instancia;
    }

    /** El {@code iss} que este emisor pone en sus tokens, y el que hay que configurar. */
    public String emisor() {
        return emisor;
    }

    /** Token firmado con la clave publicada y con el claim de municipalidad. */
    public String tokenPara(long municipalidadId) {
        return token(claves -> claves.claim("municipalidad_id", municipalidadId));
    }

    /** Token perfectamente valido al que le falta el unico claim que ADR-0005 exige. */
    public String tokenSinMunicipalidad() {
        return token(claves -> {});
    }

    /** Token vencido: firmado por el emisor bueno, con la clave buena, y ya caducado. */
    public String tokenVencido(long municipalidadId) {
        return token(
                claves ->
                        claves.claim("municipalidad_id", municipalidadId)
                                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                                .expirationTime(Date.from(Instant.now().minusSeconds(3600))));
    }

    /**
     * Token con todo correcto salvo la firma: usa una clave que este emisor <b>no</b> publica.
     *
     * <p>Es el ataque mas obvio contra un servidor de recursos que se olvidara de comprobar la
     * firma, y por eso tiene su prueba.
     */
    public String tokenConFirmaNoPublicada(long municipalidadId) {
        return firmar(
                claveNoPublicada, claves -> claves.claim("municipalidad_id", municipalidadId));
    }

    /** Token a medida, firmado con la clave publicada. */
    public String token(Consumer<JWTClaimsSet.Builder> ajustes) {
        return firmar(clavePublicada, ajustes);
    }

    private String firmar(RSAKey clave, Consumer<JWTClaimsSet.Builder> ajustes) {
        Instant ahora = Instant.now();
        JWTClaimsSet.Builder claves =
                new JWTClaimsSet.Builder()
                        .issuer(emisor)
                        .subject("usuario-de-prueba")
                        .issueTime(Date.from(ahora))
                        .expirationTime(Date.from(ahora.plusSeconds(DURACION_SEGUNDOS)));
        ajustes.accept(claves);

        JWSHeader cabecera =
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(clave.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build();
        try {
            SignedJWT token = new SignedJWT(cabecera, claves.build());
            token.sign(new RSASSASigner(clave));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", e);
        }
    }

    // ------------------------------------------------------------------
    // El servidor
    // ------------------------------------------------------------------

    /** Un hilo virtual por conexion, hasta que se cierra la puerta. */
    private void atender() {
        while (!puerta.isClosed()) {
            try {
                Socket conexion = puerta.accept();
                Thread.ofVirtual().start(() -> responder(conexion));
            } catch (IOException e) {
                // La puerta cerrada es como termina esto: no es un fallo.
                return;
            }
        }
    }

    private void responder(Socket conexion) {
        try (Socket abierta = conexion;
                BufferedReader entrada =
                        new BufferedReader(
                                new InputStreamReader(
                                        abierta.getInputStream(), StandardCharsets.UTF_8));
                OutputStream salida = abierta.getOutputStream()) {

            String peticion = entrada.readLine();
            if (peticion == null) {
                return;
            }
            String ruta = peticion.split(" ").length > 1 ? peticion.split(" ")[1] : "";
            String cuerpo = cuerpoDe(ruta);
            int estado = cuerpo == null ? 404 : 200;
            byte[] bytes = (cuerpo == null ? "" : cuerpo).getBytes(StandardCharsets.UTF_8);

            salida.write(
                    ("HTTP/1.1 "
                                    + estado
                                    + (estado == 200 ? " OK" : " Not Found")
                                    + "\r\nContent-Type: application/json\r\nContent-Length: "
                                    + bytes.length
                                    + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            salida.write(bytes);
            salida.flush();
        } catch (IOException e) {
            // Una conexion que se corta no invalida la prueba: el cliente lo vera.
        }
    }

    private String cuerpoDe(String ruta) {
        if (ruta.equals("/realms/" + realm + "/.well-known/openid-configuration")) {
            return descubrimiento();
        }
        if (ruta.equals("/realms/" + realm + "/protocol/openid-connect/certs")) {
            return new JWKSet(clavePublicada.toPublicJWK()).toString();
        }
        return null;
    }

    /**
     * El documento de descubrimiento, con lo minimo que Nimbus exige para parsearlo como metadatos
     * OIDC. Los dos campos que importan son {@code issuer} —Spring comprueba que coincide con lo
     * configurado— y {@code jwks_uri}.
     */
    private String descubrimiento() {
        return """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                  "token_endpoint": "%1$s/protocol/openid-connect/token",
                  "jwks_uri": "%1$s/protocol/openid-connect/certs",
                  "response_types_supported": ["code"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"],
                  "grant_types_supported": ["authorization_code", "refresh_token"],
                  "scopes_supported": ["openid", "profile"]
                }
                """
                .formatted(emisor);
    }

    @Override
    public void close() throws IOException {
        puerta.close();
        atencion.interrupt();
    }
}
