package kamayuk.rentas.plataforma;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * La guarda de #450: <b>ningun viaje de red con una conexion de la base tomada</b>.
 *
 * <h2>De que defecto viene, para que nadie la «simplifique»</h2>
 *
 * <p>{@code TenantTransactionManager} toma la conexion al abrir la transaccion y la suelta al
 * confirmarla. Todo lo que pase en medio —tambien esperar a {@code catastro}, a {@code caja} o a
 * {@code normativa}— lo espera <b>con la conexion tomada</b>. Con un vecino que acepta la conexion
 * y tarda en contestar, las diez conexiones del pool se quedan esperandolo, y la undecima peticion
 * de rentas —la que sea, el guardia de acceso incluido— recibe un 500 a los 30 s. El diagnostico
 * del 2026-09-23 lo midio en el panel de Inicio, en las emisiones de licencia —que ademas esperaban
 * con el candado del correlativo tomado— y en «lo vigente» de {@code normativa}.
 *
 * <p>No nacio del monolito: nacio del corte. Los puertos ya existian con una implementacion SQL
 * local, y P5B, P5C y P5D cambiaron el adaptador por HTTP <b>sin tocar el puerto</b>; justo por eso
 * ninguna frontera de transaccion se movio, y el siguiente vecino que se ponga detras de un puerto
 * que ya existia repetira el defecto sin que nada se ponga rojo. Esta guarda es lo que se pone
 * rojo.
 *
 * <h2>Donde se llama, y por que ahi</h2>
 *
 * <p>Cada cliente la llama en su costura con la red, <b>encima</b> del metodo que los dobles de
 * prueba sustituyen ({@code ClienteHttpDeCatastro.enviar}, {@code ClienteHttpDeCaja.enviar}): si
 * fuera dentro, cada prueba que monta el adaptador sobre un doble se la saltaria, y la guarda solo
 * cazaria lo que ya se sabe. Puesta encima, caza a <b>todos</b> los llamadores, tambien a los que
 * el diagnostico no enumero.
 *
 * <h2>Avisa en produccion y falla en las pruebas</h2>
 *
 * <p>Lo decide la propiedad de sistema {@value #PROPIEDAD}: {@code fallar} o {@code avisar}, y
 * avisar si no se dice. En produccion un llamador que el diagnostico no vio <b>no puede</b> dejar
 * de atender por esto —el defecto que vigila es de rendimiento bajo un vecino lento, no de datos—,
 * asi que deja un WARN que nombra el vecino y la ruta. En las pruebas {@code kamayuk.pruebas} la
 * pone en {@code fallar}, y un llamador nuevo con la transaccion abierta sale rojo en la prueba que
 * lo ejerce.
 */
public final class ViajeDeRed {

    /** La propiedad de sistema que decide que se hace con una transaccion abierta. */
    public static final String PROPIEDAD = "kamayuk.red.con-transaccion";

    private static final Logger REGISTRO = LoggerFactory.getLogger(ViajeDeRed.class);

    /** Que se hace si al salir a la red hay una transaccion abierta en el hilo. */
    public enum ConTransaccionAbierta {
        /** Deja un WARN que nombra el vecino y la ruta, y sale. Es lo de produccion. */
        AVISAR,
        /** No sale: lanza {@link ConLaConexionTomada}. Es lo de las pruebas. */
        FALLAR;

        /** El modo que dice la propiedad de sistema, o {@link #AVISAR} si no dice nada. */
        static ConTransaccionAbierta configurado() {
            String valor = System.getProperty(PROPIEDAD, "avisar");
            return "fallar".equals(valor.strip().toLowerCase(Locale.ROOT)) ? FALLAR : AVISAR;
        }
    }

    private ViajeDeRed() {}

    /**
     * Se llama justo antes de salir a la red, con el modo que diga la propiedad de sistema.
     *
     * @param vecino el sistema al que se va a preguntar, tal como se nombra en los mensajes
     * @param ruta la ruta que se va a pedir, para que el aviso diga cual
     * @param que lo que se iba a hacer, con las palabras del cliente
     */
    public static void antesDeSalir(String vecino, String ruta, String que) {
        comprobar(vecino, ruta, que, ConTransaccionAbierta.configurado());
    }

    /** Lo mismo con el modo explicito: es lo que prueban las dos ramas sin tocar el sistema. */
    static void comprobar(String vecino, String ruta, String que, ConTransaccionAbierta modo) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        String mensaje =
                "#450: se iba a preguntar a `"
                        + vecino
                        + "` por "
                        + ruta
                        + " ("
                        + que
                        + ") con una transaccion abierta, o sea con una conexion del pool tomada"
                        + " mientras se espera a la red. Con el vecino lento, rentas entero se queda"
                        + " sin conexiones. La pregunta va FUERA de la transaccion: un orquestador"
                        + " sin @Transactional que pregunta y un escritor @Transactional que solo"
                        + " escribe";
        if (modo == ConTransaccionAbierta.FALLAR) {
            throw new ConLaConexionTomada(mensaje);
        }
        REGISTRO.warn(mensaje);
    }

    /** Un viaje de red con una transaccion abierta, en el modo que no lo deja pasar. */
    public static final class ConLaConexionTomada extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ConLaConexionTomada(String mensaje) {
            super(mensaje);
        }
    }
}
