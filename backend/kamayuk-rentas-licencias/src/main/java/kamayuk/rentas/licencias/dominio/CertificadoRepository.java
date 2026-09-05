package kamayuk.rentas.licencias.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Los certificados emitidos (#54, RF-115). Ningun metodo recibe la municipalidad (regla 2): la
 * filtra la politica RLS con el valor que {@code SET LOCAL} fijo al abrir la transaccion.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V51 crea {@code
 * certificado} sin conceder {@code UPDATE} ni {@code DELETE} a {@code kamayuk_app}. Un certificado
 * equivocado se sustituye emitiendo otro; no se corrige.
 */
public interface CertificadoRepository {

    /**
     * El siguiente correlativo de ese tipo en ese ejercicio, reservado.
     *
     * <p>Un {@code INSERT ... ON CONFLICT DO UPDATE SET ultimo = ultimo + 1} sobre {@code
     * certificado_correlativo}: una sola sentencia, que bloquea la fila del contador mientras la
     * actualiza. Nunca un {@code SELECT} seguido de un {@code UPDATE} —entre los dos cabe otra
     * emision, y las dos leerian el mismo numero—.
     *
     * <p>Es <b>por tipo</b>: cada clase de certificado es un tramite del TUPA con su propia serie.
     */
    long siguienteCorrelativo(TipoDeCertificado tipo, Ejercicio ejercicio);

    /** Guarda el certificado. Devuelve el certificado con su identificador. */
    Certificado emitir(Certificado certificado);

    /** El certificado con ese numero. */
    Optional<Certificado> porNumero(String numero);

    /**
     * El certificado que se emitio con esa clave de idempotencia, si ya existe.
     *
     * <p>Es lo que convierte un reenvio —el doble clic, el reintento del navegador— en la misma
     * respuesta en vez de en un segundo certificado con otro numero por el mismo derecho pagado. La
     * <b>garantia</b> sigue siendo {@code certificado_idempotencia_uq}, no esta consulta: entre
     * leer y escribir cabe otra peticion, y por eso el indice esta ademas de la lectura.
     */
    Optional<Certificado> porClaveDeIdempotencia(String clave);

    /** La grilla «Certificados emitidos», paginada. */
    Pagina<Certificado> buscar(CriterioDeCertificados criterio, Paginacion paginacion);

    /**
     * Ese numero de certificado ya existe. Lo decide {@code certificado_numero_uq}, no un {@code
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
     * <p>Lo decide {@code certificado_idempotencia_uq}. Es distinto de {@link NumeroDuplicado} y
     * por eso es otra excepcion: aqui <b>no</b> hay ningun defecto —el cliente reintento, que es lo
     * que se espera de el— y lo que importa es que del reintento no salga un segundo papel con otro
     * numero por el mismo derecho pagado.
     */
    final class ClaveRepetida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ClaveRepetida(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
