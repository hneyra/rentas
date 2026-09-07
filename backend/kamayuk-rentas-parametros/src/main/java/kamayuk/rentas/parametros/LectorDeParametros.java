package kamayuk.rentas.parametros;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El unico punto de acceso a los parametros sellados, con la dimension temporal como argumento
 * obligatorio (ARQ-09 §2.2): no existe una sobrecarga sin ejercicio ni identificador. Una consulta
 * de parametros sin criterio produce un calculo silenciosamente incorrecto, que es el peor modo de
 * falla posible —no rompe nada, solo cobra mal—.
 *
 * <p>Las dos lecturas no son intercambiables:
 *
 * <ul>
 *   <li>{@link #vigenteEn(Ejercicio)} resuelve el conjunto que rige <b>hoy</b> para ese ejercicio.
 *       Es el que usa una determinacion <b>nueva</b>.
 *   <li>{@link #porConjunto(IdentificadorDeConjunto)} recupera <b>el conjunto concreto</b> que una
 *       determinacion ya emitida uso. Es el que usa <b>todo recalculo</b>.
 * </ul>
 *
 * <p>Confundirlas es el defecto que ARQ-09 §3 describe: si entre la emision y el recalculo se sello
 * una version nueva —un arancel corregido, una ordenanza modificada a mitad de ano—, resolver por
 * ejercicio devuelve otros parametros y el recalculo da otra cifra, sin ningun error de por medio.
 */
public interface LectorDeParametros {

    /**
     * El conjunto sellado que rige hoy para el ejercicio: el de mayor version. Para determinaciones
     * nuevas. Un recalculo que llame aqui esta mal escrito —usa {@link
     * #porConjunto(IdentificadorDeConjunto)}—.
     */
    ParametrosSellados vigenteEn(Ejercicio ejercicio);

    /**
     * El conjunto concreto que uso una determinacion, por el identificador que ella guarda. Es la
     * lectura de la reproducibilidad: recalcular en 2037 recupera esto, no «los parametros de
     * 2027».
     */
    ParametrosSellados porConjunto(IdentificadorDeConjunto identificador);

    /**
     * Que conjunto sellado rige hoy el ejercicio, por su identificador.
     *
     * <p>Existe porque hay datos normativos que <b>no</b> caben en {@link ParametrosSellados} —una
     * tabla entera de valores referenciales de vehiculos, un arancel por manzana, un cuadro de
     * valores unitarios— y viven en su propia tabla, con sus miles de filas. Para leer cualquiera
     * de ellas hace falta saber de que conjunto se trata, no de que ejercicio.
     *
     * <p><b>Este javadoc decia antes algo que D-13 desmintio, y conviene dejarlo escrito.</b> Decia
     * que esas tablas «cuelgan del conjunto, no del ejercicio», y agrupaba {@code
     * valor_referencial_vehiculo} con {@code arancel} como si fueran el mismo caso. No lo son, y
     * esa frase era la simplificacion que sostenia el hallazgo H-5 de GOB-03: la tabla del MEF
     * acabo con una copia por municipalidad, que es como dos municipalidades pueden acabar
     * valorizando el mismo vehiculo con dos cifras distintas de la misma norma. Desde V55
     * (ADR-0017) las tres tablas de valuacion son nacionales —{@code municipalidad_id} nulo,
     * cargadas una vez para todas, ARQ-09 §2.1— y el arancel se queda como estaba, porque el
     * arancel si es municipal.
     *
     * <p>Lo que <b>no</b> cambio, y por eso este metodo sigue existiendo con la misma firma: para
     * leer una tabla nacional tambien hace falta el conjunto. El conjunto ya no la posee, la
     * <b>compone</b> —una fila en {@code conjunto_parametro_detalle} que nombra la edicion
     * publicada—, y el sellado congela esa composicion. Resolver por ejercicio seguiria siendo el
     * defecto de ARQ-09 §3: entre la emision y el recalculo puede haberse publicado otra edicion.
     *
     * <p>Se publica aqui y no se resuelve en cada contexto: repetir el «sellado de mayor version de
     * este ejercicio» en el SQL de rentas, de catastro y de sanciones es tener tres sitios donde
     * olvidarse del {@code AND estado = 'SELLADO'}, y el que se olvide leera un conjunto abierto
     * sin que nada falle.
     */
    IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio);

    /**
     * Las senias del conjunto que este sistema <b>ya tiene descargado</b> para el ejercicio, si hay
     * alguno. Sin red (#25, AC-3).
     *
     * <p>Es la lectura que sostiene lo que ADR-0025 §Consecuencias promete: «{@code normativa}
     * puede caerse sin detener nada que ya haya resuelto su conjunto». Contesta las cuatro senias
     * —el ejercicio, si esta sellado, que conjunto y que version— <b>sin llamar por red y sin
     * cargar los parametros</b>, que es lo que la distingue de {@link
     * #porConjunto(IdentificadorDeConjunto)}: esa arma el juego entero y, si el snapshot no
     * estuviera en la copia local, iria a buscarlo.
     *
     * <p><b>Vacio significa «aqui no hay nada descargado», y NUNCA «ese ejercicio no esta
     * sellado».</b> Las dos se ven igual desde fuera y no lo son: la segunda es una respuesta de
     * {@code normativa} y la primera es no haber podido preguntar. Quien la use tiene que conservar
     * esa diferencia —el controlador vuelve a lanzar la {@code NormativaInalcanzable} original—,
     * porque contestar «no esta parametrizado» mandaria a buscar una ordenanza cuando lo que falta
     * es un despliegue.
     *
     * <p><b>Por que es {@code default} y no abstracto</b>: este puerto lo implementan veintiseis
     * clases, y veinticinco son dobles de prueba de otros modulos a los que la copia local no les
     * dice nada. Obligarlas a escribir un metodo que no usan seria ruido en veinticinco archivos
     * para una sola implementacion de produccion. El vacio es ademas la respuesta correcta para un
     * doble: no tiene cache.
     */
    default Optional<ConjuntoYaDescargado> loQueYaEstaDescargado(Ejercicio ejercicio) {
        return Optional.empty();
    }

    /**
     * Las senias de un conjunto que esta en la copia local: cual es, de que ejercicio y que
     * version.
     *
     * <p>No lleva ninguna cifra normativa dentro, y es a proposito: esto identifica el conjunto, no
     * lo abre.
     */
    record ConjuntoYaDescargado(long conjuntoId, Ejercicio ejercicio, int version) {}

    /**
     * Ningun conjunto sellado rige el ejercicio. No hay valor por omision (ARQ-09 §2.5).
     *
     * <p>Publica su ejercicio y <b>ninguna llave</b> ({@link ParametroSinPublicar}): lo que falta
     * no es una fila, es el conjunto donde publicarla. Nombrar una llave aqui diria que basta con
     * publicarla, y no basta: primero hay que sellar el ejercicio.
     */
    final class EjercicioSinSellar extends RuntimeException implements ParametroSinPublicar {
        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        public EjercicioSinSellar(Ejercicio ejercicio) {
            super(
                    "El ejercicio "
                            + ejercicio
                            + " no tiene un conjunto de parametros sellado. Calcular con uno"
                            + " abierto produciria una cifra que manana puede ser otra, y el"
                            + " contribuyente ya tendria el recibo (ADR-0007)");
            this.ejercicio = ejercicio;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.empty();
        }
    }

    /**
     * El conjunto que la determinacion referencia no existe o no esta sellado. Que una
     * determinacion apunte a un conjunto abierto significa que se emitio sin sellar: no se calcula
     * sobre eso, se investiga.
     */
    final class ConjuntoNoSellado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ConjuntoNoSellado(IdentificadorDeConjunto identificador) {
            super(
                    "El "
                            + identificador
                            + " no existe o no esta sellado. Una determinacion que lo referencia no"
                            + " se puede reproducir, y sustituirlo por el vigente del ejercicio"
                            + " daria otra cifra sin avisar (ARQ-09 §3)");
        }
    }
}
