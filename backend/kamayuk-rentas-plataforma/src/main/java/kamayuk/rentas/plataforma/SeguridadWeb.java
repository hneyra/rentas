package kamayuk.rentas.plataforma;

import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.RespuestaDeError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * La cadena de filtros de seguridad, escrita en vez de heredada.
 *
 * <h2>Por que existe este archivo</h2>
 *
 * <p>Con {@code spring-boot-starter-oauth2-resource-server} en el classpath y <b>ninguna</b> cadena
 * declarada, Spring Boot arma la suya: protege todo con autenticacion basica y una clave generada
 * que escribe en el registro de arranque. El efecto en este sistema es peor que un endpoint
 * abierto, porque no se parece a un fallo: sin emisor configurado no hay {@code JwtDecoder}, sin
 * {@code JwtDecoder} no hay {@code Jwt} en el {@code SecurityContextHolder}, y {@link
 * kamayuk.rentas.plataforma.tenant.TenantContextFilter} toma entonces siempre su camino de
 * «peticion sin token: sigue sin contexto». Toda consulta falla despues en la base por falta de
 * contexto de tenant —que es el comportamiento correcto del aislamiento— y el sistema queda mudo
 * sin que nada se vea roto.
 *
 * <h2>Las cuatro reglas</h2>
 *
 * <ul>
 *   <li>{@code /actuator/health} es publico, y con el sus dos GRUPOS —{@code .../liveness} y {@code
 *       .../readiness}—. Es lo que permite que el orquestador sepa si el proceso esta vivo y con
 *       base de datos; sin un endpoint publico, {@code depends_on: service_healthy} no puede
 *       significar nada. No expone detalles: {@code show-details} va en {@code never}, asi que dice
 *       si y no que. Los tres se nombran <b>uno por uno</b>, sin comodin: el porque de que los
 *       grupos no basten con el padre esta en {@link #SONDA_DE_VIDA}.
 *   <li>{@code /actuator/prometheus} tambien es publico —sin token—, y es una decision distinta a
 *       la de {@code health}: no protege datos de negocio, protege una superficie de ataque. Lo que
 *       lo mantiene fuera de alcance NO es esta cadena: es que ninguna {@code IngressRoute} enruta
 *       ahi. {@code Ingreso.ts} reenvia {@code /api/v1} a este servicio y todo lo demas del dominio
 *       publico va a la interfaz, que no conoce {@code /actuator/*}; Prometheus llega por la red
 *       interna del cluster, sin pasar por Traefik (issue #156). Es el mismo modelo que protege el
 *       puerto de PostgreSQL: de red, no de aplicacion. Si algun dia una {@code IngressRoute}
 *       reenvia aqui, este endpoint queda expuesto sin que nada en este archivo lo evite —por eso
 *       {@code componentes.test.ts} fija esa ruta como invariante del lado de infraestructura.
 *   <li>{@code /api/v1/**} exige un token que valide contra el emisor configurado. Validar es
 *       comprobar la firma contra su JWKS, el vencimiento y el emisor; el <b>claim</b> {@code
 *       municipalidad_id} lo exige despues {@code TenantContextFilter}, que corre por dentro de
 *       esta cadena y por eso ve la autenticacion ya puesta.
 *   <li>Todo lo demas se <b>niega</b>. Una ruta que nadie declaro no es una ruta que convenga
 *       servir, y {@code /actuator/**} entero cae aqui: {@code health} y {@code prometheus} estan
 *       nombrados uno por uno, de modo que exponer un endpoint nuevo del actuator exige tambien
 *       abrirlo aqui.
 * </ul>
 *
 * <h2>Sin sesion y sin CSRF, y por que eso no es un descuido</h2>
 *
 * <p>La autenticacion viaja en el encabezado {@code Authorization} y en ningun otro sitio: no hay
 * cookie de sesion, y el frontend guarda el token <b>en memoria</b> (FRO-01 §5). CSRF protege
 * contra que el navegador adjunte una credencial ambiental —una cookie— a una peticion que el
 * usuario no hizo; sin credencial ambiental no hay nada que adjuntar. Dejar CSRF puesto con
 * sesiones sin estado solo produciria 403 en cada escritura.
 *
 * <h2>Lo que todavia no hace</h2>
 *
 * <p><b>D-06:</b> el token de un usuario con acceso a varias municipalidades llevara la lista de
 * autorizadas ademas de la activa, y comprobar que la activa esta en la lista es una defensa
 * barata. El nombre de ese claim no esta fijado. Hasta entonces, un usuario, una municipalidad.
 *
 * <h2>D-07 cerrada: dos cadenas, no una con excepciones (ADR-0020)</h2>
 *
 * <p>El portal del contribuyente tiene la suya, ordenada <b>antes</b> que esta y acotada a {@code
 * /api/v1/portal/**}, con un {@code JwtDecoder} que apunta <b>solo</b> al emisor del realm del
 * ciudadano. La consecuencia es la que se buscaba, y es estructural y no una comprobacion que se
 * pueda olvidar: un token de funcionario <b>no autentica</b> en el portal, y uno de ciudadano no
 * autentica en ninguna otra ruta. Con un solo emisor —o con un cliente mas del mismo realm— lo
 * unico que separaria a las dos poblaciones seria un {@code if} dentro de la aplicacion, y un
 * {@code if} que se olvida no rompe nada visible; con dos emisores, olvidarlo produce un 401.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SeguridadWeb {

    /** La sonda de vida del orquestador. Se atiende sin identidad. */
    public static final String SONDA_DE_SALUD = "/actuator/health";

    /**
     * La sonda de <b>vida</b> de Kubernetes: «¿este proceso sigue en pie?».
     *
     * <p>Es un GRUPO de {@link #SONDA_DE_SALUD}, no un endpoint nuevo. Spring Boot 4.1 lo registra
     * por omision —{@code AvailabilityProbesAutoConfiguration} lleva
     * {@code @ConditionalOnBooleanProperty(name = "management.endpoint.health.probes.enabled",
     * matchIfMissing = true)}—, asi que aqui no hay ninguna propiedad que anadir: lo unico que
     * faltaba era abrirlo.
     *
     * <h2>Por que se abre, habiendo un {@code /actuator/health} que ya es publico</h2>
     *
     * <p>Porque los dos <b>no dicen lo mismo</b>. {@code /actuator/health} incluye el indicador de
     * la base de datos, y una sonda de VIDA que incluya la base le dice al orquestador «mata este
     * proceso cuando la base no conteste». Matarlo no devuelve la base: lo que produce es los
     * cuatro sistemas reiniciandose en bucle mientras el motor se recupera, tirando en cada vuelta
     * el pool de conexiones y el monton caliente de la JVM — una caida del motor convertida en
     * cinco. Este grupo contiene <b>solo</b> {@code livenessState}: contesta si el proceso esta
     * vivo, que es la unica pregunta cuya respuesta «no» se arregla reiniciando.
     *
     * <h2>Lo que cuesta, medido y no supuesto</h2>
     *
     * <p>Lo que se anade a la superficie sin token son dos <b>subrecursos del endpoint que ya era
     * publico</b>, y con {@code show-details: never} cada uno contesta exactamente {@code
     * {"status":"UP"}}: dicen estrictamente MENOS que su padre, que ya enumeraba mas indicadores. Y
     * se nombran <b>uno por uno</b>, sin comodin: {@code /actuator/health/**} abriria de golpe
     * cualquier grupo que alguien anada despues, que es justo lo que el docstring de esta clase
     * dice que no se hace.
     *
     * <h2>Como se llego a esto</h2>
     *
     * <p>Los cuatro descriptores declaraban estas dos rutas en sus sondas desde que existen, y esta
     * cadena permitia <b>solo</b> {@code /actuator/health}. Medido dentro del clúster: {@code
     * /actuator/health} → 200, {@code /actuator/health/liveness} → <b>401</b>. Los cuatro pods
     * arrancaban, conectaban a la base y el kubelet los mataba a los ~45 s: {@code
     * CrashLoopBackOff} para siempre, con la aplicacion sana. Nada comparaba la ruta de la sonda
     * con lo que esta cadena permite; ahora lo hace `sondas-contra-la-cadena.test.ts` de
     * `infrastructure`, que lee este archivo y los cuatro descriptores (C-17, punto 2).
     */
    public static final String SONDA_DE_VIDA = SONDA_DE_SALUD + "/liveness";

    /**
     * La sonda de <b>preparacion</b> de Kubernetes: «¿puede recibir trafico?».
     *
     * <p>El otro grupo, y con la misma decision detras. Contiene solo {@code readinessState}, asi
     * que <b>no comprueba la base</b> — y eso es deliberado: quien la comprueba es la sonda de
     * ARRANQUE, que sigue apuntando a {@link #SONDA_DE_SALUD} entero, de modo que un pod no se
     * declara arrancado hasta que llega a su base. Despues del arranque, con una sola replica,
     * sacar el pod de la rotacion no gana nada: el ingreso contestaria 503 —indistinguible de «el
     * sistema entero esta caido»— en vez del error del catalogo, que dice que paso.
     */
    public static final String SONDA_DE_PREPARACION = SONDA_DE_SALUD + "/readiness";

    /**
     * Las metricas de Prometheus (issue #156). Se atienden sin identidad porque quien las protege
     * es la red, no esta cadena: ver el docstring de la clase.
     */
    public static final String METRICAS = "/actuator/prometheus";

    /**
     * Todo lo que publica la API, bajo la raiz que declara el contrato.
     *
     * <p>Se DERIVA de {@link Api#RAIZ} y no se repite. Antes del corte estaba escrita a mano aqui,
     * y eso hacia que la raiz viviera en dos sitios: el dia que uno cambiara y el otro no, la
     * cadena de seguridad dejaria de cubrir lo que los controladores publican —sin error de
     * compilacion y sin que ninguna prueba de un controlador lo notara, porque cada una monta el
     * suyo—. El sintoma seria una API entera servida sin autenticar.
     */
    public static final String RAIZ_DE_LA_API = Api.RAIZ + "/**";

    /**
     * La raiz de lo que sirve al ciudadano, sin comodin.
     *
     * <p>La usa tambien {@code DocumentoCiudadanoContextFilter} para saber donde corre el, y donde
     * NO corre el de tenant: las dos mitades del par tienen que decir exactamente lo mismo, asi que
     * lo dicen una sola vez y aqui.
     */
    public static final String RAIZ_DEL_PORTAL = Api.RAIZ + "/portal";

    /** Lo mismo, como patron de rutas. */
    public static final String RUTAS_DEL_PORTAL = RAIZ_DEL_PORTAL + "/**";

    /**
     * Si un camino es del ciudadano.
     *
     * <p>Lo preguntan los <b>dos</b> filtros de sujeto —el de tenant para no correr, el del
     * ciudadano para correr—, y por eso se responde aqui una sola vez: dos comprobaciones que
     * tienen que decir siempre lo contrario la una de la otra son dos que un dia dejan de hacerlo,
     * y el dia que dejen de hacerlo o no habra sujeto ninguno o habra los dos a la vez.
     */
    public static boolean esDelPortal(String camino) {
        return camino.startsWith(RAIZ_DEL_PORTAL);
    }

    /**
     * La cadena del ciudadano, <b>antes</b> que la general y acotada al portal (ADR-0020 §1).
     *
     * <h2>Por que es una cadena y no una excepcion dentro de la otra</h2>
     *
     * <p>Porque lo que separa a las dos poblaciones tiene que ser <b>estructural</b>. Con un solo
     * servidor de recursos, un token de ciudadano y uno de funcionario validarian igual y lo unico
     * que los distinguiria seria una comprobacion escrita dentro de la aplicacion: un {@code if}
     * que se puede olvidar y que, al olvidarse, no rompe nada visible. Con dos cadenas y dos
     * emisores, olvidarse produce un 401.
     *
     * <p>Consecuencia, en las dos direcciones: un token de funcionario <b>no autentica</b> aqui
     * —{@code iss} de otro emisor—, y uno de ciudadano no autentica en ninguna otra ruta de la API.
     *
     * <h2>Sin emisor configurado, esta cadena lo niega todo</h2>
     *
     * <p>Y existe igual, que es lo que importa. Si en vez de negar no se registrara —un
     * {@code @ConditionalOnProperty}, que es lo que primero se piensa—, {@code /api/v1/portal/**}
     * caeria en la cadena general y quedaria servido contra el emisor de <b>funcionarios</b>: justo
     * la mezcla que estas dos cadenas existen para impedir, aparecida por no configurar algo.
     *
     * @param emisor el {@code iss} del realm del ciudadano. Vacio: instalacion sin portal
     * @param jwks de donde traer las claves, cuando el nombre publico del emisor no es alcanzable
     *     desde dentro del contenedor. Mismo reparto que en el perfil web de {@code
     *     application.yaml}: el emisor es una identidad, el JWKS una direccion de red
     */
    @Bean
    @Order(1)
    SecurityFilterChain cadenaDelPortal(
            HttpSecurity http,
            @Value("${sgtm.portal.oidc.emisor:}") String emisor,
            @Value("${sgtm.portal.oidc.jwks:}") String jwks)
            throws Exception {

        http.securityMatcher(RUTAS_DEL_PORTAL)
                .exceptionHandling(
                        errores ->
                                errores.authenticationEntryPoint(entradaSinToken())
                                        .accessDeniedHandler(accesoDenegado()))
                .sessionManagement(
                        sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(proteccion -> proteccion.disable())
                .httpBasic(mecanismo -> mecanismo.disable())
                .formLogin(mecanismo -> mecanismo.disable())
                .logout(mecanismo -> mecanismo.disable());

        if (emisor.isBlank()) {
            return http.authorizeHttpRequests(rutas -> rutas.anyRequest().denyAll()).build();
        }

        return http.authorizeHttpRequests(rutas -> rutas.anyRequest().authenticated())
                .oauth2ResourceServer(
                        servidor ->
                                servidor.jwt(jwt -> jwt.decoder(delRealmDelCiudadano(emisor, jwks)))
                                        .authenticationEntryPoint(entradaSinToken())
                                        .accessDeniedHandler(accesoDenegado()))
                .build();
    }

    /**
     * El decodificador del realm del ciudadano, y de ese solo.
     *
     * <p>Se construye a mano en vez de dejarselo a la autoconfiguracion porque la autoconfiguracion
     * monta <b>uno</b>, el de {@code spring.security.oauth2.resourceserver}, que es el de
     * funcionarios. Aqui hacen falta dos, y separados.
     *
     * <p>El validador de emisor se pone <b>siempre</b>, tambien cuando las claves llegan por {@code
     * jwks}: sin el, un token firmado por ese mismo Keycloak pero emitido por el realm de
     * funcionarios pasaria, y las dos poblaciones volverian a ser una. Es exactamente la diferencia
     * entre {@code issuer-uri} y {@code jwk-set-uri} que ya tiene su prueba en la cadena general.
     */
    private static JwtDecoder delRealmDelCiudadano(String emisor, String jwks) {
        NimbusJwtDecoder decodificador =
                jwks.isBlank()
                        ? NimbusJwtDecoder.withIssuerLocation(emisor).build()
                        : NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decodificador.setJwtValidator(JwtValidators.createDefaultWithIssuer(emisor));
        return decodificador;
    }

    /**
     * La cadena general: todo lo que no es el portal.
     *
     * <p>No lleva {@code securityMatcher}: recoge lo que la del portal no tomo, que es la forma en
     * que Spring Security encadena. Y sigue negando por omision lo que nadie declaro.
     */
    @Bean
    @Order(2)
    SecurityFilterChain cadenaDeSeguridad(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        rutas ->
                                rutas.requestMatchers(
                                                SONDA_DE_SALUD,
                                                SONDA_DE_VIDA,
                                                SONDA_DE_PREPARACION,
                                                METRICAS)
                                        .permitAll()
                                        .requestMatchers(RAIZ_DE_LA_API)
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        servidor ->
                                servidor.jwt(jwt -> {})
                                        // Sin esto, un token invalido devuelve el cuerpo vacio de
                                        // Spring Security en vez del error del catalogo, y la
                                        // interfaz no tiene a que reaccionar.
                                        .authenticationEntryPoint(entradaSinToken())
                                        .accessDeniedHandler(accesoDenegado()))
                // La misma respuesta para lo que no pasa por el servidor de recursos: una ruta
                // negada tiene que contestar igual la pida quien la pida.
                .exceptionHandling(
                        errores ->
                                errores.authenticationEntryPoint(entradaSinToken())
                                        .accessDeniedHandler(accesoDenegado()))
                .sessionManagement(
                        sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(proteccion -> proteccion.disable())
                // Sin mecanismos de autenticacion interactivos: este backend no tiene formulario de
                // acceso ni acepta clave por cabecera. Dejarlos puestos haria que una peticion
                // negada devolviera una redireccion a una pantalla que no existe, o el dialogo de
                // clave del navegador.
                .httpBasic(mecanismo -> mecanismo.disable())
                .formLogin(mecanismo -> mecanismo.disable())
                .logout(mecanismo -> mecanismo.disable())
                .build();
    }

    private static AuthenticationEntryPoint entradaSinToken() {
        return (peticion, respuesta, excepcion) ->
                RespuestaDeError.escribir(respuesta, CodigoDeError.NO_AUTENTICADO);
    }

    private static AccessDeniedHandler accesoDenegado() {
        return (peticion, respuesta, excepcion) ->
                RespuestaDeError.escribir(respuesta, CodigoDeError.SIN_PRIVILEGIO);
    }
}
