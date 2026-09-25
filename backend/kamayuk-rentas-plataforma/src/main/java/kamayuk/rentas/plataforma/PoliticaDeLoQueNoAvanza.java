package kamayuk.rentas.plataforma;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Que se hace con un evento de un buzon que esta vuelta NO resolvio: seguir esperandolo o apartarlo
 * (#377). Es la estrategia comun de los dos consumidores de buzon de este sistema —el ingestor de
 * {@code catastro} y el consumidor de {@code identidad}—.
 *
 * <h2>De que defecto viene, para que nadie la «simplifique»</h2>
 *
 * <p>Los dos consumidores ejecutan el mismo algoritmo: piden una pagina, clasifican cada evento,
 * acusan lo resuelto y deciden si seguir. Y los dos tenian salidas que <b>no acusan</b> —el
 * ingestor, un tipo que no sabe aplicar (#54); el de identidad, un {@code TodaviaNo}— <b>sin ningun
 * tope</b>. Lo que no se acusa se vuelve a servir, en el mismo orden y antes que lo de detras: con
 * 200 de esos en la cabeza, la pagina entera son ellos y lo de detras <b>no se lee nunca</b>, con
 * la corrida en verde. Medido en #377 con un buzon de mentira que respeta el {@code limite}: 200
 * {@code MANZANA_PUBLICADA} dejaban fuera al predio de la 201, y 200 {@code PERMISO_FIJADO}
 * pospuestos dejaban fuera la baja de un empleado cesado.
 *
 * <p>Esta interfaz es donde se decide que <b>nada ocupa la cabeza para siempre</b>: cada consumidor
 * la consulta por cada evento que no resolvio, y lo que ella aparta va a la cola de muertos de ese
 * buzon ({@code catastro_evento_muerto}, {@code identidad_evento_muerto}) con su motivo, <b>y se
 * acusa</b>.
 *
 * <h2>Por tipo o por antiguedad, y NO por numero de lecturas</h2>
 *
 * <p>El issue admitia las tres. La tercera exige recordar cuantas veces se leyo cada evento, y los
 * dos consumidores son procesos de vida corta que un {@code CronJob} arranca: no recuerdan nada de
 * una corrida a otra, y guardarlo seria una tabla nueva para decidir lo que la antiguedad ya decide
 * con el instante que el emisor publica en cada evento. Si algun dia hace falta, es otra
 * implementacion de esta interfaz.
 */
@FunctionalInterface
public interface PoliticaDeLoQueNoAvanza {

    /**
     * El prefijo del motivo de lo que se aparta por falta de capacidad.
     *
     * <p>Es el que {@code catastro_evento_muerto} lleva para los tipos que este sistema no sabe
     * aplicar, y lo que permite <b>reinyectarlos</b> desde la cola el dia que exista quien los
     * aplique: {@code WHERE motivo LIKE 'SIN_CAPACIDAD:%'} los separa de lo que no se podra aplicar
     * nunca.
     */
    String SIN_CAPACIDAD = "SIN_CAPACIDAD:";

    /**
     * Que hacer con este evento.
     *
     * @param evento lo que se sabe de el
     * @param ahora el instante contra el que se mide su antiguedad. Entra como argumento: la
     *     politica no lee el reloj
     */
    Decision decidir(LoQueNoAvanza evento, Instant ahora);

    /**
     * Un tipo que este consumidor no sabe aplicar se aparta EN EL ACTO, con {@value #SIN_CAPACIDAD}
     * y el tipo como motivo.
     *
     * <p>Esperar no lo arregla: lo que falta no llega por el buzon, llega con un despliegue. Es la
     * politica del ingestor de {@code catastro} desde #377, que revierte la de #54.
     */
    static PoliticaDeLoQueNoAvanza apartarLoQueNoSeSabeAplicar() {
        return (evento, ahora) -> Decision.apartar(SIN_CAPACIDAD + evento.tipoPublicado());
    }

    /**
     * Se espera mientras el evento tenga como mucho {@code tolerancia} de antiguedad, y pasada se
     * aparta.
     *
     * <p>Es la del consumidor de {@code identidad}: un {@code TodaviaNo} es normal mientras su
     * dependencia este en camino —llega en el mismo lote o en el siguiente—, y deja de serlo cuando
     * pasa el tiempo en que habria llegado. A partir de ahi esperar solo para lo de detras.
     */
    static PoliticaDeLoQueNoAvanza apartarPasadoDe(Duration tolerancia) {
        Objects.requireNonNull(tolerancia, "la tolerancia es la politica");
        return (evento, ahora) -> {
            Duration edad = Duration.between(evento.emitidoEn(), ahora);
            if (edad.compareTo(tolerancia) <= 0) {
                return Decision.seguirEsperando();
            }
            return Decision.apartar(
                    "NO_AVANZA: lleva "
                            + edad.toMinutes()
                            + " minuto(s) sin poder aplicarse y la tolerancia es de "
                            + tolerancia.toMinutes()
                            + "; esperar ya no lo arregla y paraba todo lo de detras. "
                            + evento.motivo());
        };
    }

    /**
     * Nunca se aparta nada: todo lo que no se resuelve se queda en el buzon.
     *
     * <p>Es lo que habia antes de #377 en los dos consumidores. No la usa ninguna configuracion; la
     * usan las pruebas que tienen que poder ver una cola {@link EstadoDeLaCola.Bloqueada
     * BLOQUEADA}, que con cualquiera de las otras dos no se produce.
     */
    static PoliticaDeLoQueNoAvanza esperarSiempre() {
        return (evento, ahora) -> Decision.seguirEsperando();
    }

    /**
     * Lo que se sabe de un evento que la vuelta no resolvio.
     *
     * @param tipoPublicado el tipo tal como lo escribio el emisor
     * @param emitidoEn cuando lo emitio el emisor —no cuando se leyo aqui—, que es el mismo para
     *     cualquier corrida
     * @param motivo por que no se resolvio, en las palabras del consumidor
     */
    record LoQueNoAvanza(String tipoPublicado, Instant emitidoEn, String motivo) {

        public LoQueNoAvanza {
            Objects.requireNonNull(tipoPublicado, "un evento dice de que tipo es");
            Objects.requireNonNull(emitidoEn, "un evento sabe cuando se emitio");
            Objects.requireNonNull(motivo, "lo que no avanza dice por que");
        }
    }

    /** Las dos salidas. */
    enum Accion {
        /** No se acusa: el emisor lo vuelve a servir en la vuelta siguiente. */
        SEGUIR_ESPERANDO,
        /** A la cola de muertos con {@link Decision#motivo()}, y se acusa. */
        APARTAR
    }

    /**
     * Lo que la politica decidio.
     *
     * @param motivo con que se aparta; vacio si se sigue esperando
     */
    record Decision(Accion accion, String motivo) {

        public Decision {
            Objects.requireNonNull(accion, "una decision es una de las dos salidas");
            Objects.requireNonNull(motivo, "el motivo no es opcional, aunque este vacio");
            if (accion == Accion.APARTAR && motivo.isBlank()) {
                // Un muerto sin causa: quien lo mire tendria que ir a buscarla al registro de esa
                // noche. Las dos colas lo prohiben tambien en la base (`*_motivo_ck`).
                throw new IllegalArgumentException("Lo que se aparta lleva su motivo");
            }
        }

        public static Decision seguirEsperando() {
            return new Decision(Accion.SEGUIR_ESPERANDO, "");
        }

        public static Decision apartar(String motivo) {
            return new Decision(Accion.APARTAR, motivo);
        }

        public boolean aparta() {
            return accion == Accion.APARTAR;
        }
    }
}
