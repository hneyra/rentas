package kamayuk.rentas.rentas.aplicacion;

import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.rentas.dominio.proyeccion.FuenteDeHechosDeCatastro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;

/**
 * El ingestor como trabajo por lotes (C-8).
 *
 * <h2>Un {@code ApplicationRunner} del perfil {@code batch}, y NO un {@code @Scheduled}</h2>
 *
 * <p>Se midio antes de elegir (P6 §4.4): en los cuatro backends no hay <b>ni un</b>
 * {@code @EnableScheduling} —asi que el unico {@code @Scheduled} del sistema, el publicador del
 * buzon de `caja`, tampoco se registra— y el perfil {@code batch} <b>termina el proceso</b> con
 * {@code web-application-type: none}. Un proceso que sale no puede sostener un temporizador.
 *
 * <p>Asi que se hace como la anti-entropia, la implantacion y las cargas: un runner que un {@code
 * CronJob} invoca. <b>Ese {@code CronJob} no esta desplegado</b> — {@code infra/} despliega hoy un
 * solo sistema y ninguno de los cuatro del corte tiene manifiesto.
 *
 * <h2>Da vueltas hasta vaciar el buzon, con un tope</h2>
 *
 * <p>Una sola vuelta dejaria el buzon a medias cada vez que hubiera mas de un lote, y con una
 * invocacion diaria eso es una proyeccion que nunca se pone al dia. El tope existe para que la
 * corrida <b>acabe</b>: si el emisor produce mas rapido de lo que este consume, el proceso termina
 * diciendo cuantos quedan en vez de no terminar.
 *
 * <h2>Que NO hace</h2>
 *
 * <p>No corrige nada por su cuenta y no compara nada: quien compara es la anti-entropia, y quien
 * dice donde mirar es ella. Este proceso solo aplica hechos.
 */
@Profile("batch")
public class CorrerElIngestor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorrerElIngestor.class);

    /**
     * Cuantas vueltas como maximo. Con 200 hechos por vuelta son 10 000 por corrida, que es de
     * sobra para el padron de Catacaos entero —14 422 predios— repartido en dos invocaciones.
     */
    private static final int VUELTAS_MAXIMAS = 50;

    private final IngestarHechosDeCatastro ingestor;
    private final long municipalidadId;

    public CorrerElIngestor(IngestarHechosDeCatastro ingestor, long municipalidadId) {
        this.ingestor = ingestor;
        this.municipalidadId = municipalidadId;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        TenantContext.fijar(new MunicipalidadId(municipalidadId));
        try {
            for (int vuelta = 1; vuelta <= VUELTAS_MAXIMAS; vuelta++) {
                IngestarHechosDeCatastro.Vuelta resultado = ingestor.ingerir();
                log.info("Vuelta {}: {}", vuelta, resultado);
                if (resultado.vacia()) {
                    return;
                }
            }
            log.warn(
                    "Se agotaron las {} vueltas y el buzon de `catastro` sigue teniendo hechos."
                            + " No es un fallo: la corrida acaba a proposito en vez de no acabar."
                            + " La siguiente invocacion sigue por donde esta se quedo",
                    VUELTAS_MAXIMAS);
        } catch (FuenteDeHechosDeCatastro.CatastroNoContesta noContesta) {
            // Se corta la corrida SIN acusar nada, y sale distinto de cero. Es un fallo
            // transitorio: la invocacion siguiente lo reintenta, y los hechos siguen pendientes
            // en el buzon del emisor. Tragarselo dejaria el CronJob en verde con la proyeccion
            // parada, que es la peor combinacion posible.
            throw noContesta;
        } finally {
            // SIEMPRE. Sin esto, cualquier cosa que corriera despues leeria con el contexto de
            // esta municipalidad: datos reales bajo otra etiqueta.
            TenantContext.limpiar();
        }
    }
}
