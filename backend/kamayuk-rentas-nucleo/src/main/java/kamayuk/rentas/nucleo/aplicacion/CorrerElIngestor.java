package kamayuk.rentas.nucleo.aplicacion;

import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
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
                if (resultado.sinProgreso()) {
                    // SIN PROGRESO, no «vacia». Un hecho ignorado se lee y no se acusa (#54), asi
                    // que el emisor lo vuelve a servir: dar vueltas hasta que el lote llegue vacio
                    // seria dar las cincuenta sobre los mismos hechos.
                    //
                    // Y sin progreso no es «nada que hacer» (#377): si lo que no avanza llena la
                    // pagina y el emisor tiene mas detras, lo de detras no se lee NUNCA. Salir con
                    // codigo 0 era la proyeccion congelada con el CronJob en verde.
                    if (resultado.estado() instanceof EstadoDeLaCola.Bloqueada bloqueada) {
                        throw new ColaBloqueada(bloqueada);
                    }
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

    /**
     * La cabeza del buzon de {@code catastro} esta ocupada por hechos que no avanzan y tiene mas
     * detras: la corrida sale DISTINTO DE CERO y nombra la cabeza (#377).
     *
     * <p>Con la politica de produccion no deberia darse —lo que no se sabe aplicar se aparta en el
     * acto—, y por eso mismo tiene que ser ruidoso el dia que se de: una politica que espere, o un
     * desenlace nuevo que no acuse, volverian a congelar la proyeccion sin un solo error visible.
     */
    public static final class ColaBloqueada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient EstadoDeLaCola.Bloqueada estado;

        public ColaBloqueada(EstadoDeLaCola.Bloqueada estado) {
            super(
                    "COLA BLOQUEADA: el buzon de `catastro` sirve en su cabeza "
                            + estado.enLaCabeza()
                            + " hecho(s) que no avanzan, desde la secuencia "
                            + estado.secuenciaDeCabeza()
                            + ", y tiene "
                            + estado.detras()
                            + " detras que esta corrida no puede leer. La proyeccion del padron se"
                            + " queda como esta hasta que alguien mire esa secuencia: la corrida"
                            + " siguiente traeria los mismos (#377)");
            this.estado = estado;
        }

        public EstadoDeLaCola.Bloqueada estado() {
            return estado;
        }
    }
}
