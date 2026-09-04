package kamayuk.rentas;

import java.time.Clock;
import kamayuk.rentas.plataforma.ConfiguracionDeTenant;
import kamayuk.rentas.plataforma.SeguridadWeb;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.Modulithic;

/**
 * Artefacto unico del SGTM, desplegado en los perfiles {@code web} y {@code batch} (ADR-0003).
 * Mismo codigo y misma imagen; lo que cambia es la configuracion.
 *
 * <p>Ocho modulos se declaran <b>compartidos</b>: {@code dominio} (el vocabulario comun), {@code
 * compartido} (el contexto de tenant), {@code plataforma} (el camino del token al {@code SET
 * LOCAL}), {@code persistencia} (el patron de repositorio), {@code auditoria}, {@code documentos}
 * (la generacion y reimpresion, RF-132), {@code carga} (lo comun a toda carga masiva desde archivo)
 * y {@code web}. Ninguno es un contexto acotado, y que cualquier contexto los use no es una
 * violacion de los limites sino su proposito: sin declararlos, cada contexto que use {@code Dinero}
 * o extienda {@code RepositorioJdbc} contaria como una dependencia que explicar.
 */
@Modulithic(
        systemName = "SGTM",
        sharedModules = {
            "dominio",
            "compartido",
            "plataforma",
            "persistencia",
            "auditoria",
            "documentos",
            "carga",
            "web"
        })
@SpringBootApplication
@Import({ConfiguracionDeTenant.class, SeguridadWeb.class})
public class SgtmAplicacion {

    /** El perfil de los procesos que corren y terminan (ADR-0003). */
    private static final String PERFIL_BATCH = "batch";

    /**
     * En el perfil {@code web} arranca y se queda; en {@code batch} hace su trabajo y
     * <b>termina</b>.
     *
     * <p>La segunda mitad no es un adorno. Spring Boot no cierra el contexto al acabar los {@code
     * ApplicationRunner}, y basta un {@code ScheduledThreadPoolExecutor} no-demonio —los hay, sin
     * que nadie los pida— para que la JVM siga viva sin nada que hacer. Un contenedor de un solo
     * uso que no termina no es una molestia: el orquestador espera su {@code
     * service_completed_successfully} para arrancar lo siguiente, y se queda esperando para
     * siempre. El despliegue entero se cuelga en el paso mas tonto.
     *
     * <p>Lo descubrio la primera implantacion ejecutada de verdad: hizo su trabajo —municipalidad,
     * 134 accesos, administrador y permisos, todo correcto en la base— y se quedo ahi.
     *
     * <p>{@code SpringApplication.exit} cierra el contexto y calcula el codigo de salida a partir
     * de los {@code ExitCodeGenerator}, asi que un proceso masivo que falle seguira saliendo
     * distinto de cero.
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext contexto = SpringApplication.run(SgtmAplicacion.class, args);
        if (contexto.getEnvironment().matchesProfiles(PERFIL_BATCH)) {
            System.exit(SpringApplication.exit(contexto));
        }
    }

    /**
     * El reloj del sistema, como componente inyectable.
     *
     * <p>Existe para que ninguna capa llame a {@code LocalDate.now()} sin argumento. En el dominio
     * esta prohibido y lo verifica ArchUnit; en la capa de aplicacion es legitimo necesitar la
     * fecha —la auditoria se particiona por ejercicio— pero sigue siendo indeseable que sea
     * imposible de fijar en una prueba. Un {@code Clock} inyectado resuelve las dos cosas sin
     * discutir con nadie.
     */
    @Bean
    Clock reloj() {
        return Clock.systemDefaultZone();
    }
}
