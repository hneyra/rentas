package kamayuk.rentas.catastro;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;

/**
 * Los frentes de un predio, con la constancia de cuando se derivaron.
 *
 * <p><b>La lista vacia NO viaja sola, y ese es el punto de este tipo.</b> «Este predio no da a
 * ninguna calle» y «a este predio no le ha pasado el derivador» son la misma lista vacia y dos
 * problemas distintos: el primero se arregla midiendo en campo y el segundo cargando la
 * cartografia. Hoy no hay ni un poligono en ninguna instalacion, asi que la respuesta que se va a
 * dar siempre al principio es la segunda — y determinar arbitrios sobre cero metros lineales
 * cobraria de menos a todo el padron sin que ninguna cifra pareciera mal (#48).
 *
 * <h2>Los confirmados y los propuestos llegan SEPARADOS, y no hay una lista que los mezcle</h2>
 *
 * <p>Es la forma que #15 pide, y la razon es la regla de la casa: <b>si el desarrollador no lo
 * maneja, no puede olvidarlo</b>. Con una sola lista, la ruta mas corta hacia una cifra —recorrerla
 * y sumar {@code longitud()}— cobra las propuestas, y una propuesta la corto una maquina contra el
 * eje de la via sin que nadie la firmara (ADR-0021). Aqui esa ruta no existe: para llegar a una
 * lista hay que escribir {@code confirmados()} o {@code propuestos()}, y el nombre dice cual se
 * eligio.
 *
 * <p>Y el <b>unico total</b> que este tipo ofrece es {@link #metrosLinealesConfirmados()}. No hay
 * un {@code metrosLineales()} a secas, y {@code NingunaPropuestaLlegaAUnaCifraTest} se pone rojo si
 * alguien lo anade: un metodo asi seria otra vez la ruta corta, con otro nombre.
 *
 * <p><b>Los propuestos no se ocultan</b>, y eso tambien es a proposito: son la lista de lo que
 * falta por confirmar, y quien opera necesita verla para mandar a confirmarla. Lo que no pueden es
 * sumarse sin decirlo.
 *
 * <p>Y <b>no hay ningun {@code todos()} ni {@code paraEnsenar()}</b>, aunque una pantalla los
 * quiera: una lista que mezcla los dos estados es la ruta corta con otro nombre, y hoy no tiene ni
 * un llamador que la justifique. Quien dibuje esa pantalla escribe las dos listas, que es una linea
 * mas y dice cual es cual.
 *
 * @param confirmados los frentes cuya longitud firmo una persona; los unicos que pueden llegar a
 *     una base imponible
 * @param propuestos los que corto el derivador y nadie ha confirmado. Se ensenan, no se cobran
 * @param derivadoEn cuando corrio el derivador sobre este predio; {@code null} si no ha corrido
 *     nunca. Es texto y no una fecha porque {@code catastro} publica ahi un instante
 * @param frentesDerivados cuantos propuso esa corrida; {@code null} si no ha corrido nunca
 * @param motivoDeLaDerivacion por que no propuso ninguno; {@code null} si propuso alguno o si no
 *     corrio
 */
public record FrentesInscritos(
        long predioId,
        List<FrenteInscrito> confirmados,
        List<FrenteInscrito> propuestos,
        @Nullable String derivadoEn,
        @Nullable Integer frentesDerivados,
        @Nullable String motivoDeLaDerivacion) {

    public FrentesInscritos {
        Objects.requireNonNull(confirmados, "La lista de confirmados es vacia, no nula");
        Objects.requireNonNull(propuestos, "La lista de propuestos es vacia, no nula");
        confirmados = List.copyOf(confirmados);
        propuestos = List.copyOf(propuestos);
        // La invariante que sostiene todo lo demas: un frente esta en la lista que su estado dice.
        // Sin ella, el tipo prometeria la separacion y la podria romper quien lo construye —que es
        // exactamente donde se cuela un defecto que despues nadie encuentra, porque el nombre del
        // campo sigue diciendo la verdad.
        for (FrenteInscrito frente : confirmados) {
            if (!frente.confirmada()) {
                throw new IllegalArgumentException(
                        "El frente "
                                + frente.id()
                                + " esta "
                                + frente.longitudEstado()
                                + " y llego en la lista de confirmados: de esa cifra cuelga un"
                                + " arbitrio, y confirmar es un acto de una persona en `catastro`"
                                + " (ADR-0021)");
            }
        }
        for (FrenteInscrito frente : propuestos) {
            if (frente.confirmada()) {
                throw new IllegalArgumentException(
                        "El frente "
                                + frente.id()
                                + " esta CONFIRMADA y llego en la lista de propuestos: quedaria"
                                + " fuera de la base imponible sin que nadie lo decidiera");
            }
        }
    }

    /**
     * Los metros lineales que <b>se pueden cobrar</b>: la suma de los frentes confirmados.
     *
     * <p>Devuelve la {@link Medida} con su unidad dentro y no un numero suelto, y la suma la hace
     * {@link Medida#mas(Medida)}, que se niega a sumar unidades distintas: el barrido se determina
     * sobre metros LINEALES y el recojo sobre CUADRADOS, y leer unos por otros no falla, cobra otra
     * cosa.
     *
     * <p>Vacio cuando no hay ningun frente confirmado. <b>No devuelve cero</b>: cero metros
     * lineales es una cifra, y una cifra que sale de «todavia no hay ninguno confirmado» cobraria
     * de menos a todo el padron sin que nada pareciera mal (#48). Quien lo consuma tiene que
     * decidir que hace con la ausencia, y por eso la ausencia no se puede confundir con un total.
     *
     * <p><b>Ni un importe.</b> Esto son metros; la tarifa y la ordenanza son de quien determine
     * (ADR-0024), y hoy las bloquea D-02b.
     */
    public Optional<Medida> metrosLinealesConfirmados() {
        Medida total = null;
        for (FrenteInscrito frente : confirmados) {
            total = total == null ? frente.longitud() : total.mas(frente.longitud());
        }
        return Optional.ofNullable(total);
    }
}
