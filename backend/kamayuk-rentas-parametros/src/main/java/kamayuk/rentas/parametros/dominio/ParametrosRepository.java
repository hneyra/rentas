package kamayuk.rentas.parametros.dominio;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Puerto de persistencia de los conjuntos de parametros.
 *
 * <p><b>No hay ningun metodo que cree un {@link ParametroTributario}</b>, y no es una omision: la
 * aplicacion solo tiene {@code SELECT} sobre {@code parametro_tributario} (V7). Cargar valores
 * normativos es trabajo de {@code rol_carga_parametros}, un rol distinto con su propia conexion. Es
 * la separacion de funciones de REQ-03: quien opera el sistema no publica las cifras con las que se
 * calcula.
 *
 * <p>Lo que la aplicacion si hace es armar el conjunto del ejercicio con parametros ya publicados,
 * y sellarlo.
 */
public interface ParametrosRepository {

    Pagina<ConjuntoDeParametros> conjuntos(Paginacion paginacion);

    Optional<ConjuntoDeParametros> conjunto(long id);

    /**
     * El conjunto sellado de mayor version del ejercicio: el que rige hoy. Puede haber mas de uno
     * sellado —ARQ-09 §3 lo exige— y entonces el vigente es el ultimo.
     */
    Optional<ConjuntoDeParametros> selladoVigenteDe(Ejercicio ejercicio);

    /**
     * El conjunto sellado con ese identificador, sea o no el vigente. Devuelve vacio si no existe o
     * si sigue abierto: un conjunto abierto no se lee para calcular.
     */
    Optional<ConjuntoDeParametros> selladoPorId(long id);

    /** La ultima version del ejercicio, sellada o no. 0 si no hay ninguna. */
    int ultimaVersionDe(Ejercicio ejercicio);

    ConjuntoDeParametros crear(ConjuntoDeParametros conjunto);

    /** Sella el conjunto. Falla si ya estaba sellado: lo impide un disparador de la base. */
    ConjuntoDeParametros sellar(long conjuntoId, Instant cuando, String quien);

    /** Agrega un parametro ya publicado al conjunto. Falla si el conjunto esta sellado. */
    void agregarParametro(long conjuntoId, long parametroId);

    List<ParametroTributario> parametrosDe(long conjuntoId);

    /**
     * Los parametros publicados que responden a esa llave.
     *
     * <p>Devuelve una <b>lista</b> y no un {@code Optional} a proposito: {@code
     * parametro_tributario} no tiene ninguna restriccion de unicidad sobre (tipo, clave,
     * vigencia_desde) —V1 no la puso—, asi que dos filas homonimas son posibles. Resolverlas aqui
     * quedandose con «la primera» meteria en un conjunto sellado un valor que nadie eligio, y no
     * habria ningun sintoma. Quien llama decide, y lo que decide es rechazar.
     */
    List<ParametroTributario> publicados(LlaveDeParametro llave);

    /** Solo lectura: la aplicacion no publica valores normativos. */
    Pagina<ParametroTributario> parametros(Paginacion paginacion);
}
