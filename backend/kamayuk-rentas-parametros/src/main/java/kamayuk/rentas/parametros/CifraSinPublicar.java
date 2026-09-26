package kamayuk.rentas.parametros;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * La familia «falta publicar» como <b>tipo</b>, y no como una lista que cada {@code catch} repite
 * (#435).
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>{@link ParametroSinPublicar} es un contrato —ejercicio y llave, legibles por programa—, pero
 * una interfaz no se puede capturar como familia sin enumerar a mano a sus miembros. Hasta #435 lo
 * implementaban veintiuna excepciones, cada una con el mismo bloque copiado —{@code
 * serialVersionUID}, el campo del ejercicio con su {@code @SuppressWarnings}, la llave y los dos
 * accesores—, y cada {@code catch} las nombraba una a una: la cifra que se parametrizara despues y
 * se olvidara en uno de ellos contestaba {@code 500} con incidencia en vez del {@code 422} con
 * {@code parametroQueFalta} que promete el contrato, y ni el compilador ni la guarda lo veian.
 *
 * <p>Con la base, la familia es lo que el compilador ya sabe: {@code ManejadorDeLoQueFaltaPublicar}
 * la traduce <b>entera</b> en un sitio, y la cifra que nazca mañana entra en el 422 con solo
 * extenderla. Que toda implementacion de la interfaz extienda esta clase lo exige {@code
 * LaFamiliaFaltaPublicarEsUnTipoTest}.
 *
 * <h2>Lo que la subclase sigue decidiendo</h2>
 *
 * <p>El mensaje, redactado en lenguaje del dominio, y como se compone la llave. La regla de la
 * llave es la de {@link ParametroSinPublicar}: {@code TIPO:CLAVE} cuando falta exactamente una
 * fila, el {@code TIPO} solo cuando falta el bloque entero, y ninguna —{@code null}— cuando falta
 * el conjunto.
 *
 * <p>La unica de la familia que no puede extenderla es {@code
 * PoliticasDeRedondeo.PuntoSinPolitica}: vive en el dominio puro, por debajo de este modulo, y no
 * sabe de que ejercicio son sus politicas.
 */
public abstract class CifraSinPublicar extends RuntimeException implements ParametroSinPublicar {

    @java.io.Serial private static final long serialVersionUID = 1L;

    // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no implementa
    // Serializable, y una excepcion de negocio nunca se serializa —se lanza, se traduce a
    // problem+json y muere ahi—.
    @SuppressWarnings("serial")
    private final Ejercicio ejercicio;

    private final @Nullable String llave;

    protected CifraSinPublicar(String mensaje, Ejercicio ejercicio, @Nullable String llave) {
        super(mensaje);
        this.ejercicio = Objects.requireNonNull(ejercicio, "Siempre se sabe de que ejercicio es");
        this.llave = llave;
    }

    protected CifraSinPublicar(
            String mensaje, Ejercicio ejercicio, @Nullable String llave, Throwable causa) {
        super(mensaje, causa);
        this.ejercicio = Objects.requireNonNull(ejercicio, "Siempre se sabe de que ejercicio es");
        this.llave = llave;
    }

    @Override
    public final Ejercicio ejercicio() {
        return ejercicio;
    }

    @Override
    public final Optional<String> llave() {
        return Optional.ofNullable(llave);
    }
}
