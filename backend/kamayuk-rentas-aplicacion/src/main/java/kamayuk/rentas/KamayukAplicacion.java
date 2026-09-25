package kamayuk.rentas;

import java.time.Clock;
import kamayuk.rentas.dominio.ZonaHoraria;
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
        systemName = "Kamayuk",
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
public class KamayukAplicacion {

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
        ConfigurableApplicationContext contexto =
                SpringApplication.run(KamayukAplicacion.class, args);
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
     *
     * <p><b>Lleva la zona del producto, y no la del servidor</b> ({@code rentas}#316). {@code
     * LocalDate.now(reloj)} trunca con la zona <i>del reloj</i>, y en {@code src/main} hay {@code
     * MEDIDO: 118 LocalDate.now(reloj) en el codigo de src/main} que deciden que dia es:
     * vencimientos, plazos, cuentas de dias, la fecha que consta en un acto. Con {@code
     * Clock.systemDefaultZone()} ese dia era el del sistema operativo que sirviera la peticion, y
     * con la JVM en UTC todo lo que ocurriera entre las 19:00 y la medianoche de Catacaos se
     * fechaba <b>el dia siguiente</b> —y la noche del 31 de diciembre, en el ejercicio siguiente—.
     *
     * <p><b>Por que aqui y no en los 126 sitios</b>, medido el 2026-09-22 sobre {@code
     * backend/*&#47;src/main} sin contar comentarios —el issue dice 127 porque su {@code grep}
     * contaba tambien uno de {@code FichasDelPadronHttp}—: el reloj se lee en 172 sitios, todos con
     * el nombre {@code reloj}, y <b>solo los 126 {@code LocalDate.now(reloj)} dependen de su
     * zona</b>. Los 43 {@code reloj.instant()} no la leen —un instante es el mismo punto en
     * cualquier zona—, y los tres {@code OffsetDateTime.now(reloj)} ({@code AuditoriaJdbc}, {@code
     * CorridaDeEmisionRepositoryJdbc} y {@code CacheDeSnapshotsJdbc}) van a columnas {@code
     * timestamptz}, que guardan el instante y descartan el desfase con que llega: cambia {@code
     * +00:00} por {@code -05:00} y la fila es la misma. No hay ni un {@code LocalDateTime.now},
     * {@code ZonedDateTime.now}, {@code reloj.getZone()} ni {@code setClock} en produccion, y los
     * dos componentes que reciben el reloj —{@code GuardiaDeAcceso} y {@code
     * TokenDeServicioDeKeycloak}— estan contados arriba. O sea que fijar la zona aqui cambia
     * exactamente los 126 sitios que tenia que cambiar y ninguno mas; reescribirlos a {@code
     * ZonaHoraria.diaDe(reloj.instant())} tocaria 86 archivos para llegar al mismo dia.
     *
     * <p>Que no vuelva un reloj sin esta zona lo vigila {@code
     * NingunRelojSinLaZonaDelProductoTest}; que el dia salga bien en la franja en que las dos zonas
     * discrepan, {@code ElDiaDelRelojEsElDelProductoTest}.
     */
    @Bean
    Clock reloj() {
        return Clock.system(ZonaHoraria.DEL_PRODUCTO);
    }
}
