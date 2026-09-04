package kamayuk.rentas.dominio;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * La politica de redondeo <b>resuelta por punto</b>: que escala y que modo se aplican en cada
 * {@link PuntoDeRedondeo} del calculo.
 *
 * <p>Es la forma que D-03c necesita. Una {@link PoliticaDeRedondeo} suelta responde «con dos
 * decimales y HALF_UP»; D-03c pregunta «¿en el metrado de la obra, o solo al cerrar el autovaluo?»,
 * y esa respuesta es una por punto.
 *
 * <p><b>Un punto sin politica es una excepcion, nunca «no redondear».</b> Es la diferencia que
 * justifica este tipo: con una politica unica, el punto que nadie observo no falla —simplemente no
 * redondea— y produce un importe plausible que nadie distingue del correcto hasta que llega la
 * reclamacion. Aqui falla {@link #en(PuntoDeRedondeo)}, en el acto y nombrando el punto.
 *
 * <p>No hay constructor de conveniencia que ponga la misma politica en todos los puntos: existiria
 * para no tener que contestar la pregunta, y la pregunta es el trabajo. Quien construye este objeto
 * enumera los puntos que su calculo atraviesa, y esa enumeracion se ve en el diff.
 */
public final class PoliticasDeRedondeo {

    private final Map<PuntoDeRedondeo, PoliticaDeRedondeo> porPunto;

    private PoliticasDeRedondeo(Map<PuntoDeRedondeo, PoliticaDeRedondeo> porPunto) {
        this.porPunto = Collections.unmodifiableMap(new EnumMap<>(porPunto));
    }

    /** Constructor para quien lee la parametrizacion y para las pruebas, que arman las suyas. */
    public static Constructor construir() {
        return new Constructor();
    }

    /**
     * La politica del punto.
     *
     * @throws PuntoSinPolitica si el punto no tiene politica. No se sustituye por «no redondear»:
     *     ver el javadoc de la clase
     */
    public PoliticaDeRedondeo en(PuntoDeRedondeo punto) {
        Objects.requireNonNull(punto, "Resolver una politica de redondeo exige su punto");
        PoliticaDeRedondeo politica = porPunto.get(punto);
        if (politica == null) {
            throw new PuntoSinPolitica(punto, porPunto.keySet());
        }
        return politica;
    }

    /** Para quien quiera preguntar antes de calcular, no para elegir un valor por omision. */
    public Optional<PoliticaDeRedondeo> politicaDe(PuntoDeRedondeo punto) {
        Objects.requireNonNull(punto, "Consultar una politica de redondeo exige su punto");
        return Optional.ofNullable(porPunto.get(punto));
    }

    /** Los puntos que esta parametrizacion cubre. */
    public Set<PuntoDeRedondeo> puntos() {
        return porPunto.keySet();
    }

    @Override
    public boolean equals(@Nullable Object otro) {
        return otro instanceof PoliticasDeRedondeo otras && porPunto.equals(otras.porPunto);
    }

    @Override
    public int hashCode() {
        return porPunto.hashCode();
    }

    @Override
    public String toString() {
        return "PoliticasDeRedondeo" + porPunto;
    }

    /**
     * El calculo llego a un punto que nadie parametrizo.
     *
     * <p>El mensaje nombra el punto y los que si estan, porque «falta una politica de redondeo» no
     * le sirve a quien tiene que ir a buscarla: lo que hace falta saber es <b>cual</b>, y eso es
     * una fila de la campana de observacion del SRTM del MEF.
     *
     * <p><b>Y el punto sale tambien por {@link #punto()}, no solo dentro del texto</b> (#691). Esta
     * es la unica de las excepciones de «falta publicar» que no puede declarar {@code
     * ParametroSinPublicar} —vive en el dominio puro y no sabe de que ejercicio salieron las
     * politicas (regla 7)—, asi que quien si sabe el ejercicio compone con las dos mitades la llave
     * {@code REDONDEO:‹punto›} que el cuerpo del 422 publica. Leerla del mensaje seria reaccionar
     * al texto, que es exactamente lo que el discriminador existe para evitar.
     */
    public static final class PuntoSinPolitica extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final PuntoDeRedondeo punto;

        PuntoSinPolitica(PuntoDeRedondeo punto, Set<PuntoDeRedondeo> parametrizados) {
            super(
                    "No hay politica de redondeo para "
                            + punto
                            + "; las hay para "
                            + parametrizados
                            + ". No redondear tambien produce un importe, y ese importe seria"
                            + " plausible y equivocado: el punto se observa contra el SRTM del MEF"
                            + " y entra como parametro (D-03c)");
            this.punto = punto;
        }

        /** El punto que nadie parametrizo, legible por programa y no solo dentro del mensaje. */
        public PuntoDeRedondeo punto() {
            return punto;
        }
    }

    /** Arma la parametrizacion punto por punto. */
    public static final class Constructor {

        private final Map<PuntoDeRedondeo, PoliticaDeRedondeo> porPunto =
                new EnumMap<>(PuntoDeRedondeo.class);

        private Constructor() {}

        public Constructor en(PuntoDeRedondeo punto, PoliticaDeRedondeo politica) {
            Objects.requireNonNull(punto, "Parametrizar un redondeo exige su punto");
            Objects.requireNonNull(politica, "Parametrizar un redondeo exige su politica");
            porPunto.put(punto, politica);
            return this;
        }

        public PoliticasDeRedondeo construir() {
            if (porPunto.isEmpty()) {
                throw new IllegalArgumentException(
                        "Una parametrizacion de redondeo sin ningun punto no redondearia nada, y"
                                + " eso es justo el modo de falla silencioso que este tipo evita");
            }
            return new PoliticasDeRedondeo(porPunto);
        }
    }
}
