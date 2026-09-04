package kamayuk.rentas.tesoreria;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import org.jspecify.annotations.Nullable;

/**
 * Suscribe un convenio de fraccionamiento <b>coactivo</b>, publicado para {@code coactiva} (ARQ-01
 * §4, #42, RF-105).
 *
 * <p>Es la segunda API publica de {@code tesoreria}, tras {@link ConveniosDelContribuyente}, y vive
 * en el paquete raiz por el mismo motivo: Spring Modulith trata como interno todo lo que esta en un
 * subpaquete, asi que {@code coactiva} no puede ver {@code
 * tesoreria.aplicacion.RegistrarPreconvenio} ni {@code tesoreria.dominio.Convenio}. Esto es
 * exactamente lo que puede ver.
 *
 * <h2>El mecanismo es el de #35, entero</h2>
 *
 * <p>Este puerto <b>no</b> reimplementa nada: delega en el mismo {@code RegistrarPreconvenio} que
 * usa la ventanilla, con {@code TipoDeConvenio.COACTIVO}. Lo que eso arrastra, y es justamente lo
 * que se queria arrastrar:
 *
 * <ul>
 *   <li>La deuda acogible la resuelve {@code AcogimientoAConvenio} releyendo el libro, cuota por
 *       cuota y <b>con su fase de origen</b>. Ninguna cifra viaja en la peticion.
 *   <li>El interes y el maximo de cuotas salen del conjunto sellado ({@code
 *       CondicionesParametrizadas}), y el convenio guarda de cual (ARQ-09 §3, regla 5).
 *   <li>Lo que sale de aqui es siempre un <b>preconvenio</b>: sin cuota inicial cobrada en caja no
 *       hay convenio. El convenio coactivo se formaliza por la misma caja que el ordinario.
 *   <li>Y el quiebre devuelve la deuda a la fase que {@code convenio_deuda.fase_origen} guardo. Una
 *       cuota que venia de coactiva vuelve a <b>coactiva</b>, no a ordinaria: el expediente sigue
 *       vivo. Eso ya lo hacia el mecanismo de #35 sin saber nada de expedientes, y #42 lo verifica
 *       asiento por asiento en vez de suponerlo.
 * </ul>
 *
 * <p><b>La unica diferencia con el convenio ordinario es el tipo</b>, que es constancia
 * administrativa de bajo que procedimiento se firmo. Lo que de verdad distingue a un
 * fraccionamiento coactivo —a donde vuelve su deuda si se incumple— no lo decide ese campo sino la
 * fase de origen, cuota por cuota. Un convenio marcado ORDINARIO sobre deuda coactiva devolveria
 * igual a coactiva.
 *
 * <h2>Lo que NO se publica aqui</h2>
 *
 * <p>Ni formalizar, ni quebrar, ni anular. Son actos con su recibo, su acta y su observacion, viven
 * en {@code FormalizarConvenio} y {@code CerrarConvenio} y ya tienen su ruta en {@code
 * ConvenioController}. Publicarlos aqui seria un segundo camino al convenio sin nada de eso, y en
 * particular permitiria quebrar un convenio desde coactiva sin pasar por la comprobacion del recibo
 * de la inicial.
 */
public interface FraccionamientoCoactivo {

    /**
     * El cronograma que saldria, <b>sin registrar nada</b>.
     *
     * <p>Ni numera un convenio, ni toca el libro, ni deja auditoria. Es lo que permite que {@code
     * coactiva} compruebe de que fase viene cada cuota <b>antes</b> de firmar.
     *
     * @throws SinDeudaCoactivaQueFraccionar si la seleccion no tiene deuda a la fecha de corte
     * @throws CondicionesSinPublicar si el conjunto sellado del ejercicio no existe o no trae
     *     alguna de las cifras con que se arma el cronograma
     */
    ConvenioCoactivo simular(SolicitudDeConvenioCoactivo solicitud);

    /**
     * Registra el preconvenio coactivo con su numero, su deuda congelada y su cronograma.
     *
     * <p><b>Lleva su clave de idempotencia</b> (#606). Este puerto tambien esta al final de un
     * {@code POST} —{@code ConvenioCoactivoController.fraccionar}, {@code POST
     * /coactiva/convenios}—, asi que un reenvio del mismo intento abre un segundo convenio sobre la
     * misma deuda igual que por la ruta de tesoreria. Que la llamada pase por un caso de uso de
     * coactiva no cambia nada: lo que reenvia es el cliente HTTP, no el caso de uso.
     *
     * @param claveDeIdempotencia la cabecera {@code Idempotency-Key} del intento, o {@code null} si
     *     quien llama no la manda; entonces cada envio es un intento distinto, que es lo que era
     *     antes de #606
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws SinDeudaCoactivaQueFraccionar si la seleccion no tiene deuda a la fecha de corte
     * @throws CondicionesSinPublicar si el conjunto sellado del ejercicio no existe o no trae
     *     alguna de las cifras con que se arma el cronograma
     */
    ConvenioCoactivo registrar(
            SolicitudDeConvenioCoactivo solicitud,
            @org.jspecify.annotations.Nullable String claveDeIdempotencia,
            Observacion observacion);

    /**
     * La seleccion no tiene deuda a esa fecha.
     *
     * <p>Se traduce en la frontera del modulo —{@code RegistrarPreconvenio.SinDeudaQueFraccionar}
     * vive en un subpaquete y no cruza— para que {@code coactiva} pueda distinguir este caso sin
     * mirar el texto de un mensaje.
     */
    final class SinDeudaCoactivaQueFraccionar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinDeudaCoactivaQueFraccionar(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * Falta publicar una de las cifras con que se arma el cronograma (#562).
     *
     * <p>Un convenio no se puede armar sin el interes de fraccionamiento, sin el maximo de cuotas y
     * sin la politica con que se redondea cada cuota, y las tres salen del <b>conjunto sellado</b>
     * del ejercicio del convenio (regla 5). Eso no es un fallo del servidor: es una cifra que
     * todavia nadie ha publicado, y con D-02a y D-03c abiertas es el estado <i>normal</i> del
     * sistema.
     *
     * <p><b>Se traduce aqui, en la frontera, por el mismo motivo que {@link
     * SinDeudaCoactivaQueFraccionar}</b>: las excepciones que lo dicen viven en {@code
     * tesoreria.aplicacion} —un subpaquete— y Spring Modulith no deja que {@code coactiva} las
     * nombre. Sin esta clase, {@code POST /coactiva/convenios} tendria que capturar un tipo interno
     * de otro modulo o quedarse contestando 500 (#51 midio lo primero: «depends on non-exposed
     * type»).
     *
     * <p>El mensaje es el de la excepcion original, que ya nombra la llave —{@code
     * INTERES_FRACCIONAMIENTO:ORDINARIO}, {@code REDONDEO:CUOTA}— o, cuando lo que falta es el
     * conjunto entero y no hay llave que nombrar, el <b>ejercicio</b>.
     */
    final class CondicionesSinPublicar extends RuntimeException implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final @Nullable String llave;

        /**
         * La causa esta acotada a los dos limites a la vez (#691).
         *
         * <p>{@code RuntimeException} para poder encadenarla y {@link ParametroSinPublicar} para
         * poder <b>copiarle</b> el ejercicio y la llave, que es lo que este envoltorio tenia que
         * dejar de perder: hasta #691 solo conservaba el mensaje, asi que el 422 de {@code POST
         * /coactiva/convenios} salia sin discriminador aunque la excepcion original lo supiera
         * todo. Con este limite, meter en la lista del {@code catch} una excepcion que no publique
         * su ejercicio <b>no compila</b>.
         */
        public <E extends RuntimeException & ParametroSinPublicar> CondicionesSinPublicar(
                String mensaje, E causa) {
            super(mensaje, causa);
            this.ejercicio = causa.ejercicio();
            this.llave = causa.llave().orElse(null);
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.ofNullable(llave);
        }
    }
}
