package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Las licencias de funcionamiento. Ningun metodo recibe la municipalidad (regla 2): la filtra la
 * politica RLS con el valor que {@code SET LOCAL} fijo al abrir la transaccion.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V37 le retira a {@code
 * sgtm_app} el privilegio de {@code UPDATE} y {@code DELETE} nunca lo tuvo (V7). Una licencia se
 * cancela con un movimiento; no se corrige.
 */
public interface LicenciaRepository {

    /**
     * El siguiente correlativo del ejercicio, reservado.
     *
     * <p>Un {@code INSERT ... ON CONFLICT DO UPDATE SET ultimo = ultimo + 1} sobre {@code
     * licencia_correlativo}: una sola sentencia, que bloquea la fila del contador mientras la
     * actualiza. Nunca un {@code SELECT} seguido de un {@code UPDATE} —entre los dos cabe otra
     * emision, y las dos leerian el mismo numero—.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /** Guarda la licencia con sus giros. Devuelve la licencia con su identificador. */
    LicenciaDeFuncionamiento emitir(LicenciaDeFuncionamiento licencia);

    /** La licencia con ese numero impreso, con sus giros resueltos contra el catalogo. */
    Optional<LicenciaDeFuncionamiento> porNumero(String numero);

    /** La grilla, paginada, con los giros de cada fila ya resueltos. */
    Pagina<LicenciaDeFuncionamiento> buscar(CriterioDeLicencias criterio, Paginacion paginacion);

    /**
     * El padron: la misma consulta con el <b>estado derivado en el motor</b> a la fecha de corte
     * (#54, RF-115).
     *
     * <p>Existe aparte de {@link #buscar} por una razon y no por comodidad: el estado no es una
     * columna —se deriva de {@code licencia_movimiento} y de la vigencia (V37 §1)—, asi que
     * filtrarlo <b>despues</b> de paginar, en memoria, daria una pagina con menos filas de las
     * pedidas y, peor, un {@link #resumen} que no cuadraria con ella. Aqui la condicion se escribe
     * en SQL, con la misma expresion que el resumen usa.
     *
     * @param estado el filtro por estado derivado; {@code null} los trae todos
     * @param aLaFecha el dia al que se deriva el estado (regla 9, RNF-075)
     */
    Pagina<LicenciaDeFuncionamiento> padron(
            CriterioDeLicencias criterio,
            @Nullable EstadoDeLicencia estado,
            LocalDate aLaFecha,
            Paginacion paginacion);

    /**
     * El resumen del padron: cuantas licencias encuentra el criterio y como se reparten entre los
     * tres estados a la fecha de corte.
     *
     * <p>Es un agregado del motor y no un recuento en Java sobre la pagina devuelta, y esa es toda
     * su razon de existir: contar la pagina daria una cifra que parece un total y no lo es (#25,
     * #51).
     */
    ResumenDelPadronDeLicencias resumen(
            CriterioDeLicencias criterio, @Nullable EstadoDeLicencia estado, LocalDate aLaFecha);

    /**
     * Los conteos de un año para el resumen anual (#54, RF-115).
     *
     * <p>Una sola consulta por año, con los cuatro conteos y los recibos de las licencias emitidas.
     * Los recibos NO se usan para sumar la recaudacion —eso se le pide a {@code tesoreria} por su
     * API publica, agregado, sin traer miles de identificadores—: se devuelven para poder decir
     * cuantas licencias del año llevaban recibo, que es lo que permite explicar una discrepancia
     * entre la cifra recaudada y el numero de emitidas.
     *
     * @param tipo el filtro por tipo de licencia; {@code null} los trae todos
     * @param alCierre el dia al que se deriva «vigentes al cierre» (regla 9)
     */
    ConteosDelAno conteosDelAno(
            Ejercicio ejercicio, @Nullable TipoDeLicencia tipo, LocalDate alCierre);

    /**
     * Lo que un año aporta al resumen anual.
     *
     * @param emitidas cuantas licencias se emitieron ese año
     * @param canceladas cuantas cancelaciones se dictaron ese año, sean del año que sean
     * @param duplicados cuantos duplicados se autorizaron ese año
     * @param vigentesAlCierre cuantas de las emitidas ese año seguian vigentes en {@code alCierre}
     * @param recibos los recibos de las licencias emitidas ese año
     */
    record ConteosDelAno(
            long emitidas,
            long canceladas,
            long duplicados,
            long vigentesAlCierre,
            Set<Long> recibos) {

        public ConteosDelAno {
            recibos = Set.copyOf(recibos);
        }

        /** Un año sin ninguna licencia. */
        public static ConteosDelAno vacio() {
            return new ConteosDelAno(0, 0, 0, 0, Set.of());
        }
    }

    /** Ese numero de licencia ya existe. Lo decide {@code licencia_numero_uq}, no un SELECT. */
    final class NumeroDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public NumeroDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
