package kamayuk.rentas.parametros.dominio;

import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El puerto hacia {@code normativa}, y la <b>unica</b> forma en que este sistema habla con el.
 *
 * <p>Dos metodos y ninguno mas, que es lo que impide reinventar la API de consulta por parametro
 * que ADR-0025 descarta: no hay {@code uitDe(ejercicio)} ni {@code arancelDe(via)}. Lo que se pide
 * es el conjunto entero, una vez, y despues todo se lee de la cache local.
 *
 * <p><b>Ninguno de los dos se llama dentro de un bucle de calculo.</b> Es la propiedad que hace que
 * una corrida de 300 000 predios haga una peticion y no 300 000, y no la sostiene ninguna prueba
 * automatica: la sostiene que este puerto no tenga forma de responder a una pregunta por partida.
 */
public interface PublicadorDeNormativa {

    /**
     * Que conjunto sellado rige hoy el ejercicio.
     *
     * <p>Es la unica pregunta que <b>no</b> se puede contestar desde la cache sin arriesgarse:
     * entre dos corridas puede haberse sellado una version nueva —un arancel corregido a mitad de
     * ano (ARQ-09 §3)—, y esta es la llamada que se entera.
     *
     * @throws NormativaInalcanzable si no contesta
     */
    long conjuntoVigenteEn(Ejercicio ejercicio);

    /**
     * El conjunto entero, con su huella ya verificada.
     *
     * @param ambito {@code VALUACION} u {@code OBLIGACION} (ADR-0024)
     * @throws NormativaInalcanzable si no contesta
     * @throws HuellaQueNoCuadra si los bytes no son los que el {@code ETag} dice
     */
    SnapshotDeNormativa descargar(long conjuntoId, String ambito);

    /**
     * {@code normativa} no contesta.
     *
     * <p>Es <b>distinta</b> de «ese ejercicio no esta sellado», y por eso es un tipo aparte y no un
     * {@code Optional} vacio: confundirlas haria que una caida de red se leyera en pantalla como
     * «esta municipalidad no ha parametrizado el ejercicio», que es una frase falsa que quien
     * atiende no puede distinguir de la verdadera.
     */
    final class NormativaInalcanzable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NormativaInalcanzable(String que, @Nullable Throwable causa) {
            super(
                    "No se pudo hablar con `normativa` para "
                            + que
                            + ". No es que el ejercicio no este parametrizado: es que no se sabe."
                            + " Un conjunto ya descargado se sigue pudiendo usar (ADR-0025"
                            + " §Consecuencias); lo que no se puede es resolver uno nuevo",
                    causa);
        }
    }

    /**
     * Los bytes recibidos no son los que su huella dice.
     *
     * <p>No se guarda nada: cachear «para siempre» un contenido que no se pudo verificar es
     * exactamente lo contrario de lo que la huella existe para permitir.
     */
    final class HuellaQueNoCuadra extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public HuellaQueNoCuadra(long conjuntoId, String esperada, String calculada) {
            super(
                    "El snapshot del conjunto "
                            + conjuntoId
                            + " llego con la huella "
                            + calculada
                            + " y su ETag dice "
                            + esperada
                            + ". No se cachea: una copia que no se pudo verificar guardada para"
                            + " siempre es peor que no tener ninguna");
        }
    }
}
