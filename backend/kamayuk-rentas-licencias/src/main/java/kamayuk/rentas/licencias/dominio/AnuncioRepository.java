package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las autorizaciones de anuncio (#51, RF-114). Ningun metodo recibe la municipalidad (regla 2): la
 * filtra la politica RLS con el valor que {@code SET LOCAL} fijo al abrir la transaccion.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V45 le retira a {@code
 * kamayuk_app} el privilegio de {@code UPDATE} y {@code DELETE} nunca lo tuvo (V7). Un anuncio se
 * cesa con un movimiento; no se corrige.
 */
public interface AnuncioRepository {

    /**
     * El siguiente correlativo del ejercicio, reservado.
     *
     * <p>Un {@code INSERT ... ON CONFLICT DO UPDATE SET ultimo = ultimo + 1} sobre {@code
     * anuncio_correlativo}: una sola sentencia, que bloquea la fila del contador mientras la
     * actualiza. Nunca un {@code SELECT} seguido de un {@code UPDATE} —entre los dos cabe otra
     * autorizacion, y las dos leerian el mismo numero—.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /** Guarda la autorizacion. Devuelve el anuncio con su identificador. */
    Anuncio autorizar(Anuncio anuncio);

    /** El anuncio con ese numero de autorizacion. */
    Optional<Anuncio> porNumero(String numero);

    /**
     * El anuncio que se registro con esa clave de idempotencia, si ya existe.
     *
     * <p>Es lo que convierte un reenvio —el doble clic, el reintento del navegador— en la misma
     * respuesta en vez de en un segundo anuncio con un segundo cargo (AC 1 de #51). La
     * <b>garantia</b> sigue siendo {@code anuncio_idempotencia_uq}, no esta consulta: entre leer y
     * escribir cabe otra peticion, y por eso el indice esta ademas de la lectura.
     */
    Optional<Anuncio> porClaveDeIdempotencia(String clave);

    /** La grilla y el padron, paginados. */
    Pagina<Anuncio> buscar(CriterioDeAnuncios criterio, Paginacion paginacion);

    /**
     * El resumen del padron: cuantas autorizaciones encuentra el criterio y cuanto han devengado
     * hasta esa fecha.
     *
     * <p>Es un agregado del motor y no una suma en Java sobre la pagina devuelta, y esa es toda su
     * razon de existir: sumar la pagina daria una cifra que parece un total y no lo es (#25).
     *
     * @param aLaFecha solo cuentan los movimientos hasta ese dia (regla 9, RNF-075)
     */
    ResumenDelPadron resumen(CriterioDeAnuncios criterio, LocalDate aLaFecha);

    /**
     * Ese numero de autorizacion ya existe. Lo decide {@code anuncio_numero_uq}, no un {@code
     * SELECT}.
     */
    final class NumeroDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public NumeroDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * Otra peticion con la misma clave de idempotencia gano la carrera.
     *
     * <p>Lo decide {@code anuncio_idempotencia_uq}. Es distinto de {@link NumeroDuplicado} y por
     * eso es otra excepcion: aqui <b>no</b> hay ningun defecto —el cliente reintento, que es lo que
     * se espera de el— y lo que importa es que del reintento no salga un segundo anuncio con un
     * segundo cargo. La lectura previa de {@link #porClaveDeIdempotencia} atiende el caso comun;
     * esta es la carrera que la lectura no puede cerrar, porque entre leer y escribir cabe otra
     * peticion.
     */
    final class ClaveRepetida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ClaveRepetida(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
