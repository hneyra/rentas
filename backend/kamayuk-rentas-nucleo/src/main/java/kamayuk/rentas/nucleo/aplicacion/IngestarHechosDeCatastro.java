package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.plataforma.PoliticaDeLoQueNoAvanza;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El ingestor: trae los hechos de {@code catastro}, los aplica y los acusa (C-8, ADR-0027).
 *
 * <h2>Este objeto NO abre transaccion</h2>
 *
 * <p>Las abre {@link AplicarUnHecho}, una por hecho. Ver su javadoc: envolver el bucle es el
 * defecto que este proyecto ha medido cuatro veces.
 *
 * <h2>El acuse va DESPUES de confirmar, y por lotes</h2>
 *
 * <p>Se acusa lo que quedo escrito, no lo que se intento escribir. Un acuse que se pierda hace que
 * el emisor vuelva a servir lo mismo y este lado lo descarte por deduplicacion: <b>la entrega es al
 * menos una vez y quien deduplica es el receptor</b>. Acusar antes de confirmar seria lo contrario
 * —el hecho dejaria de servirse sin estar aplicado— y nada lo diria.
 *
 * <h2>Los desenlaces que no son «aplicado» no se tratan igual</h2>
 *
 * <table>
 *   <tr><th>Que paso</th><th>Que se hace</th><th>Por que</th></tr>
 *   <tr><td>{@code catastro} no contesta</td><td>se corta la vuelta, sin acusar nada</td>
 *       <td>Se arregla levantando un despliegue y va a arreglarse solo</td></tr>
 *   <tr><td>El hecho no se puede aplicar</td><td>se aparta, SE ACUSA y se avisa a una persona</td>
 *       <td>Reintentarlo no lo arregla y <b>bloquea la cola detras de el</b>: la proyeccion se
 *           quedaria congelada sin un solo error visible</td></tr>
 *   <tr><td>La base rechaza el hecho —una clave unica, un dominio, un largo, un {@code CHECK}—</td>
 *       <td>lo mismo: la proyeccion lo traduce a «no se puede aplicar» nombrando la restriccion
 *       (#377)</td>
 *       <td>Es un fallo permanente con otra cara: reintentarlo da el mismo rechazo</td></tr>
 *   <tr><td>El tipo del hecho no se sabe aplicar</td>
 *       <td>lo decide la {@link PoliticaDeLoQueNoAvanza}: la de produccion lo APARTA en el acto
 *           con {@code SIN_CAPACIDAD:<tipo>} y lo acusa, sin avisar a nadie; una que esperase lo
 *           dejaria sin acusar</td>
 *       <td>No es un fallo del hecho: es una capacidad que todavia no existe. Y esperarla no la
 *           trae: llega con un despliegue, no por el buzon (#377)</td></tr>
 * </table>
 *
 * <h2>El tipo que no se sabe aplicar se APARTA, y eso revierte una decision (#54 → #377)</h2>
 *
 * <p>Hasta #54 ese caso ni siquiera llegaba aqui: el cliente HTTP lanzaba al armar el lote, asi que
 * un solo hecho del territorio mataba la vuelta ENTERA. #54 decidio ignorarlo <b>sin acusarlo</b>
 * —«sigue pendiente en el buzon de {@code catastro} y se aplicara el dia que exista quien lo
 * aplique»— y lo razono con una premisa: «con {@code POR_VUELTA = 200} el buzon entero viene en una
 * pagina».
 *
 * <p><b>Esa premisa era falsa, y #377 lo midio.</b> El emisor sirve lo no acusado por orden y como
 * mucho {@value #POR_VUELTA}: con 200 hechos del territorio en la cabeza —la carga de las manzanas
 * va antes que el padron— la pagina entera eran ellos, la vuelta no resolvia nada, el runner paraba
 * «sin progreso» con codigo 0 y la corrida siguiente traia <b>los mismos 200</b>. Ningun {@code
 * PREDIO_PROYECTADO}, {@code VALUACION_PUBLICADA} ni {@code CORRIDA_CERRADA} de detras volvia a
 * aplicarse, y lo unico visible eran avisos {@code WARN}. Antes de llegar a 200 la caida era
 * gradual: con 150 en la cabeza cada vuelta traia 50 utiles.
 *
 * <p>Asi que ahora se <b>aparta a la cola de muertos y se acusa</b>, y las partes de eso tambien
 * son deliberadas:
 *
 * <ul>
 *   <li><b>No se aplica nada.</b> Aplicarlo a medias dejaria la proyeccion diciendo algo que nadie
 *       escribio. Eso no cambia.
 *   <li><b>Se acusa, y NO se pierde</b>: {@code catastro_evento_muerto} guarda el hecho entero —el
 *       cuerpo tal como llego, su huella y su secuencia— con el motivo {@code
 *       SIN_CAPACIDAD:<tipo>}, que lo separa de lo que no se podra aplicar nunca. El dia que exista
 *       quien lo aplique se reinyecta desde ahi, no desde el emisor.
 *   <li><b>No se avisa al responsable.</b> Su aviso dice «la proyeccion del padron esta
 *       incompleta», y de una manzana eso es falso: no hay nada roto que atender. Por la misma
 *       razon {@code muertosSinExplicar} no los cuenta. Lo que queda es UNA linea {@code WARN} por
 *       vuelta que dice cuantos y de que tipos, para que «se aparto» no sea indistinguible de «se
 *       perdio».
 * </ul>
 *
 * <p>Cuales son los tipos que este sistema sabe aplicar esta escrito en el javadoc de {@link
 * kamayuk.rentas.nucleo.dominio.proyeccion.TipoDeHechoDeCatastro}; cuales publica el emisor, en su
 * buzon ({@code catastro}, {@code TipoDeEventoDeCatastro} y {@code
 * docs/50-api/eventos/lote-de-eventos.json}).
 *
 * <p><b>La vuelta se corta en el primer fallo de transporte y no sigue con el resto</b>, y eso es
 * deliberado: los hechos llegan en orden y aplicar el 40 saltandose el 39 es exactamente lo que la
 * secuencia existe para impedir.
 */
public class IngestarHechosDeCatastro {

    private static final Logger log = LoggerFactory.getLogger(IngestarHechosDeCatastro.class);

    /** Cuantos se piden por vuelta. Un lote y no la cola entera: una vuelta tiene que acabar. */
    static final int POR_VUELTA = 200;

    private final FuenteDeHechosDeCatastro fuente;
    private final AplicarUnHecho aplicador;
    private final AlertaDeHechosSinAplicar alerta;
    private final PoliticaDeLoQueNoAvanza politica;
    private final Clock reloj;

    public IngestarHechosDeCatastro(
            FuenteDeHechosDeCatastro fuente,
            AplicarUnHecho aplicador,
            AlertaDeHechosSinAplicar alerta,
            PoliticaDeLoQueNoAvanza politica,
            Clock reloj) {
        this.fuente = fuente;
        this.aplicador = aplicador;
        this.alerta = alerta;
        this.politica = politica;
        this.reloj = reloj;
    }

    /**
     * Una vuelta: trae un lote, lo aplica y lo acusa.
     *
     * @throws FuenteDeHechosDeCatastro.CatastroNoContesta si no se pudo preguntar. No se acusa
     *     nada: la vuelta siguiente lo reintenta
     */
    public Vuelta ingerir() {
        Instant cuando = reloj.instant();
        FuenteDeHechosDeCatastro.Lote lote = fuente.pendientes(POR_VUELTA);
        List<UUID> resueltos = new ArrayList<>();
        int aplicados = 0;
        int yaEstaban = 0;
        int descartados = 0;
        int muertos = 0;
        int ignorados = 0;
        long cabeza = -1;
        // Por tipo y ordenado, para que la linea de resumen salga igual en dos corridas iguales.
        Map<String, Integer> sinCapacidad = new TreeMap<>();

        for (HechoRecibido hecho : lote.hechos()) {
            if (hecho.tipo() == null) {
                PoliticaDeLoQueNoAvanza.Decision decision =
                        politica.decidir(
                                new PoliticaDeLoQueNoAvanza.LoQueNoAvanza(
                                        hecho.tipoPublicado(),
                                        hecho.emitidoEn(),
                                        "este sistema no sabe aplicar el tipo «"
                                                + hecho.tipoPublicado()
                                                + "»"),
                                cuando);
                if (decision.aparta()) {
                    // A la cola de muertos y SE ACUSA: es lo que impide que 200 hechos del
                    // territorio ocupen la cabeza para siempre (#377). No se avisa a nadie: no
                    // hay nada roto, y el aviso del responsable diria que el padron esta
                    // incompleto, que es falso de una manzana.
                    aplicador.matar(hecho, decision.motivo(), cuando);
                    sinCapacidad.merge(hecho.tipoPublicado(), 1, Integer::sum);
                    resueltos.add(hecho.eventoId());
                    continue;
                }
                ignorados++;
                if (cabeza < 0) {
                    cabeza = hecho.secuencia();
                }
                // WARN, y no ERROR: no hay nada roto que atender. Y no en silencio: un hecho que
                // se ignora sin dejar rastro es indistinguible de uno que se perdio. NO se acusa
                // —la politica decidio esperar— y la vuelta SIGUE con los demas.
                log.warn(
                        "Hecho {} IGNORADO: `catastro` publica el tipo «{}» (secuencia {}, predio"
                                + " {}, ejercicio {}) y este sistema todavia no sabe aplicarlo, y"
                                + " la politica decidio esperar. NO es un fallo: es una capacidad"
                                + " que no existe. No se aplica nada, no se acusa y esta vuelta"
                                + " sigue con los demas. Que tipos sabe aplicar este sistema esta"
                                + " en el javadoc de TipoDeHechoDeCatastro; cuales publica el"
                                + " emisor, en el buzon de `catastro`",
                        hecho.eventoId(),
                        hecho.tipoPublicado(),
                        hecho.secuencia(),
                        hecho.predioId(),
                        hecho.ejercicio());
                continue;
            }
            try {
                ProyeccionDeCatastro.Aplicacion resultado = aplicador.aplicar(hecho, cuando);
                switch (resultado) {
                    case APLICADO -> aplicados++;
                    case YA_APLICADO -> yaEstaban++;
                    case DESCARTADO_POR_VIEJO -> {
                        descartados++;
                        // SE DICE. Descartar en silencio es la mitad del defecto que la secuencia
                        // existe para impedir: la fila queda plausible y nadie sabe que se ignoro
                        // un hecho. Nivel de aviso y no de error: es una decision correcta, no un
                        // fallo — lo que separa uno de otro en este sistema es si deja incidencia.
                        log.warn(
                                "Hecho {} ({}, secuencia {}) DESCARTADO POR VIEJO: la fila que hay"
                                        + " en la proyeccion salio de un hecho posterior. No es un"
                                        + " fallo: es lo que impide que un hecho viejo que llega"
                                        + " tarde pise a uno nuevo ya aplicado",
                                hecho.eventoId(),
                                hecho.tipo(),
                                hecho.secuencia());
                    }
                    // El `default` esta por Checkstyle y no sobra: el dia que la proyeccion
                    // devuelva un cuarto resultado, contarlo como si no hubiera pasado nada seria
                    // exactamente el silencio que este informe existe para no tener.
                    default ->
                            throw new IllegalStateException(
                                    "La proyeccion devolvio «"
                                            + resultado
                                            + "», que este informe no sabe contar");
                }
                resueltos.add(hecho.eventoId());
            } catch (ProyeccionDeCatastro.NoSePuedeAplicar noSePuede) {
                String motivo = motivoDe(noSePuede);
                aplicador.matar(hecho, motivo, cuando);
                muertos++;
                // Se acusa igual: apartado y acusado deja de servirse, que es lo que impide que
                // bloquee la cola detras de el. Sin esto, un solo hecho imposible congela la
                // proyeccion entera y ninguna cifra lo dice.
                resueltos.add(hecho.eventoId());
                alerta.hayUnHechoSinAplicar(hecho, motivo, aplicador.muertosSinExplicar());
            }
        }

        if (!sinCapacidad.isEmpty()) {
            // UNA linea por vuelta y no una por hecho: la carga del territorio son miles de
            // manzanas, y un registro que grita en lo normal es el que nadie lee el dia que dice
            // algo (la leccion de H6 en el consumidor de identidad).
            log.warn(
                    "{} hecho(s) de tipos que este sistema todavia no sabe aplicar APARTADOS a la"
                            + " cola de muertos con motivo {}<tipo> y acusados en esta vuelta: {}."
                            + " NO es un fallo y no se pierden: el cuerpo entero queda en"
                            + " catastro_evento_muerto para reinyectarlo el dia que exista quien"
                            + " lo aplique (#377)",
                    sinCapacidad.values().stream().mapToInt(Integer::intValue).sum(),
                    PoliticaDeLoQueNoAvanza.SIN_CAPACIDAD,
                    sinCapacidad);
        }

        fuente.acusar(List.copyOf(resueltos));
        return new Vuelta(
                lote.hechos().size(),
                aplicados,
                yaEstaban,
                descartados,
                muertos,
                sinCapacidad.values().stream().mapToInt(Integer::intValue).sum(),
                ignorados,
                lote.quedan(),
                cabeza);
    }

    private static String motivoDe(RuntimeException noSePudo) {
        String mensaje = noSePudo.getMessage();
        return mensaje == null ? noSePudo.getClass().getSimpleName() : mensaje;
    }

    /**
     * Lo que hizo una vuelta.
     *
     * @param sinCapacidad los de un tipo que este sistema no sabe aplicar que la politica aparto:
     *     estan en la cola de muertos y acusados
     * @param ignorados los de un tipo que no se sabe aplicar que la politica decidio ESPERAR: ni
     *     aplicados ni acusados, y el emisor los vuelve a servir
     * @param quedan cuantos le quedaban al emisor tras servir este lote, SIN contarlo (el puerto lo
     *     declara asi). Es lo que permite decir «faltan 9 000» en vez de «faltan», y lo que separa
     *     una cola al dia de una BLOQUEADA
     * @param cabeza la secuencia del primer hecho que se quedo sin acusar, o {@code -1} si no hubo
     */
    public record Vuelta(
            int leidos,
            int aplicados,
            int yaEstaban,
            int descartados,
            int muertos,
            int sinCapacidad,
            int ignorados,
            long quedan,
            long cabeza) {

        public Vuelta {
            Objects.requireNonNull(Integer.valueOf(leidos), "la vuelta cuenta lo que leyo");
        }

        /** Cuantos se acusaron: todo lo leido menos lo que la politica decidio esperar. */
        public int resueltos() {
            return leidos - ignorados;
        }

        /**
         * Si esta vuelta no dejo nada resuelto, otra traeria exactamente lo mismo.
         *
         * <p><b>No es «no leyo nada», y la diferencia la introdujo #54.</b> Un hecho ignorado se
         * lee y <b>no se acusa</b>, asi que el emisor lo vuelve a servir en la vuelta siguiente:
         * con un buzon donde solo queda eso, «leidos == 0» no se cumple <b>nunca</b> y quien de
         * vueltas hasta vaciarlo daria las cincuenta, avisando cincuenta veces de lo mismo.
         *
         * <p><b>Y «sin progreso» no es «nada que hacer»: eso lo dice {@link #estado()}</b> (#377).
         * Si la pagina vino llena de ignorados y el emisor tiene mas detras, lo de detras no se lee
         * nunca.
         */
        public boolean sinProgreso() {
            return resueltos() == 0;
        }

        /** Vacia, al dia, o BLOQUEADA en su cabeza con lo que espera detras (#377). */
        public EstadoDeLaCola estado() {
            return EstadoDeLaCola.alTerminarLaVuelta(leidos, resueltos(), quedan, cabeza);
        }

        @Override
        public String toString() {
            return leidos
                    + " hecho(s) leidos: "
                    + aplicados
                    + " aplicados, "
                    + yaEstaban
                    + " ya estaban, "
                    + descartados
                    + " descartados por viejos, "
                    + muertos
                    + " sin poder aplicar, "
                    + sinCapacidad
                    + " apartados por tipo que este sistema no sabe aplicar, "
                    + ignorados
                    + " ignorados sin acusar; quedan "
                    + quedan
                    + " en el buzon del emisor detras de este lote; la cola esta "
                    + estado();
        }
    }
}
