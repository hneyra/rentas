package kamayuk.rentas.plataforma;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recorre el registro de municipalidades, <b>una a la vez</b>, cada una bajo su propio contexto y
 * su propia transaccion (ADR-0020, ARQ-03 §6).
 *
 * <h2>Lo que este componente es, y lo que no</h2>
 *
 * <p>La frase que lo gobierna, y que hay que poder repetir:
 *
 * <blockquote>
 * No es una consulta multi-municipalidad; son <i>N</i> consultas de una municipalidad cuya union se
 * filtra a un documento firmado.
 * </blockquote>
 *
 * <p>La base de datos <b>no</b> aprende a cruzar municipalidades: sigue sin poder hacerlo. Cada
 * rama abre su transaccion, emite su {@code SET LOCAL app.municipalidad_id} y queda sujeta a la
 * misma politica RLS que cualquier consulta de ventanilla. Lo unico nuevo es que el proceso recorre
 * el registro de tenants, que ya es legitimo y ya se hace: {@code municipalidad} es catalogo con
 * {@code USING (true)}, {@code kamayuk_app} tiene {@code SELECT} sobre ella (V6, V7) y todo proceso
 * masivo del perfil {@code batch} itera municipalidad por municipalidad.
 *
 * <h2>El unico del perfil {@code web} que mueve el contexto</h2>
 *
 * <p>En el perfil {@code web} el contexto lo fija el filtro del borde y nadie mas: los otros nueve
 * llamadores de {@link TenantContext#fijar} del sistema son todos {@code @Profile("batch")}. Ese
 * invariante existia y no lo comprobaba nadie; con este componente pasa a ser regla de ArchUnit
 * —{@code SOLO_EL_RECORRIDO_MUEVE_EL_TENANT_EN_WEB}—, con su clase de muestra que la viola. Sin la
 * regla, cualquier caso de uso podria «mirar en otra municipalidad» y el aislamiento dejaria de
 * tener un solo sitio donde comprobarse.
 *
 * <h2>Tres reglas, y las tres se pagan si se olvidan</h2>
 *
 * <ol>
 *   <li><b>El contexto se limpia entre ramas, pase lo que pase.</b> El {@code finally} no es
 *       higiene: sin el, la rama cuya lectura falla deja puesto el contexto de la <b>anterior</b>,
 *       y la siguiente devuelve datos reales bajo el nombre de otro municipio. Es la fuga que no se
 *       ve —las cifras son ciertas, la etiqueta no— y no la caza ninguna comprobacion de la base,
 *       porque la base hizo exactamente lo que se le pidio.
 *   <li><b>Ninguna transaccion envolvente.</b> Es la leccion de #54 y #72: {@code
 *       EjercicioSinSellar} es lo que ocurre <b>hoy</b> en todas las municipalidades, y una rama
 *       que lance dentro de la transaccion del anfitrion la marca <i>rollback-only</i>; entonces la
 *       respuesta entera muere con {@code UnexpectedRollbackException} por culpa de una
 *       municipalidad. Cada rama abre la suya —{@link TransactionTemplate}, {@code REQUIRED}— y la
 *       que falla se informa.
 *   <li><b>La lectura del registro va fuera de toda transaccion.</b> {@code municipalidad} no es
 *       tabla de tenant y su politica es {@code USING (true)}: no necesita contexto, y pedirlo
 *       dentro de una transaccion con {@code SET LOCAL} obligaria a elegir bajo que municipalidad
 *       se lee el registro <b>de todas</b>.
 * </ol>
 *
 * <p>Y una consecuencia que el guardia del pool ya vigila: como cada rama es una transaccion y
 * {@code SET LOCAL} muere con ella, ninguna conexion vuelve al pool con {@code
 * app.municipalidad_id} puesto. Recorrer con {@code SET SESSION} —o fijando el parametro fuera de
 * transaccion— lo detectaria {@code TenantConnectionGuard} y descartaria la conexion.
 */
@Component
public class RecorridoPorMunicipalidades {

    /**
     * Las activas, en orden estable.
     *
     * <p>{@code activa = false} queda fuera del recorrido: una municipalidad dada de baja no tiene
     * padron que consultar, y listarla vacia diria que la persona no figura alli cuando lo que pasa
     * es que alli ya no se atiende.
     *
     * <p>Ordenadas por {@code id} y no por nombre: el orden tiene que ser el mismo en dos
     * peticiones seguidas, y el nombre de una municipalidad se puede corregir.
     */
    private static final String ACTIVAS =
            "SELECT id, ubigeo, nombre FROM municipalidad WHERE activa ORDER BY id";

    private static final Logger log = LoggerFactory.getLogger(RecorridoPorMunicipalidades.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate porRama;

    public RecorridoPorMunicipalidades(JdbcClient jdbc, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        // Una transaccion por rama, no una para el recorrido. Ver la regla 2 del javadoc.
        this.porRama = new TransactionTemplate(transacciones);
    }

    /** Las municipalidades activas del registro, sin leer una sola fila de ninguna de ellas. */
    public List<Municipalidad> activas() {
        return jdbc.sql(ACTIVAS)
                .query(
                        (fila, numero) ->
                                new Municipalidad(
                                        fila.getLong("id"),
                                        fila.getString("ubigeo").strip(),
                                        fila.getString("nombre")))
                .list();
    }

    /**
     * Aplica la rama a cada municipalidad activa y devuelve lo que cada una contesto.
     *
     * <p>La rama devuelve {@link Optional}: vacio significa «aqui no hay nada que contar» —el
     * sondeo del padron no encontro a nadie— y por eso no aparece en el resultado. Es lo que hace
     * que una municipalidad donde la persona no figura <b>no reciba ninguna fila de auditoria</b>:
     * no se llega a leer nada suyo.
     *
     * <p>Una rama que lanza <b>no interrumpe el recorrido</b>: se anota en {@link
     * Resultado#fallidas()} con su motivo y las demas siguen. Quien compone decide que hacer con
     * eso; lo que no puede hacer es totalizar como si estuvieran todas.
     *
     * @param rama lo que se lee dentro de la municipalidad, ya con contexto y dentro de transaccion
     */
    // `IllegalCatch` prohibe atrapar RuntimeException, y con razon: casi siempre esconde
    // un defecto. Aqui es justo lo contrario, y es la decision de ADR-0020 §2. Lo que se
    // atrapa no es «un error» sino **una municipalidad que no se pudo leer**, y no se
    // traga: se anota en `fallidas`, se registra con su tipo y **quita el total
    // consolidado**, que es la consecuencia que importa. Estrecharlo a un tipo concreto
    // seria peor: la rama puede lanzar `EjercicioSinSellar`, un fallo de persistencia o
    // un `ProblemaDeNegocio`, y el que no estuviera en la lista tumbaria el recorrido
    // entero por culpa de un solo municipio.
    @SuppressWarnings("checkstyle:IllegalCatch")
    public <T> Resultado<T> recorrer(Function<Municipalidad, Optional<T>> rama) {
        Objects.requireNonNull(rama, "El recorrido necesita saber que leer en cada municipalidad");
        // Y no desde dentro de una peticion que ya tiene municipalidad: eso dejaria el
        // contexto de esa peticion cambiado a mitad de camino, que es la forma mas
        // silenciosa de contaminar una transaccion en curso. Bajo `/api/v1/portal/**` no
        // corre el filtro de tenant, asi que aqui no puede haber contexto puesto; si lo
        // hay, alguien esta llamando a esto desde donde no debe.
        TenantContext.actualSiHay()
                .ifPresent(
                        actual -> {
                            throw new IllegalStateException(
                                    "El recorrido por municipalidades no puede correr dentro de"
                                            + " una peticion que ya tiene contexto de"
                                            + " municipalidad ("
                                            + actual.valor()
                                            + "): lo dejaria cambiado a mitad de camino");
                        });

        List<Municipalidad> activas = activas();
        List<T> leidas = new ArrayList<>();
        List<Fallo> fallidas = new ArrayList<>();

        for (Municipalidad municipalidad : activas) {
            TenantContext.fijar(new MunicipalidadId(municipalidad.id()));
            try {
                porRama.execute(estado -> rama.apply(municipalidad)).ifPresent(leidas::add);
            } catch (RuntimeException fallo) {
                // El motivo se registra, no se publica: el mensaje de una excepcion de
                // persistencia lleva dentro el nombre de la tabla y de la restriccion
                // (ManejadorDeErrores existe justamente para que eso no salga por HTTP).
                log.warn(
                        "La rama de la municipalidad {} ({}) no se pudo leer",
                        municipalidad.id(),
                        municipalidad.nombre(),
                        fallo);
                fallidas.add(new Fallo(municipalidad, fallo.getClass().getSimpleName()));
            } finally {
                // SIEMPRE, y aunque la rama haya lanzado. Sin esto, la rama siguiente
                // leeria con el contexto de la anterior: datos reales bajo otra etiqueta.
                TenantContext.limpiar();
            }
        }
        return new Resultado<>(List.copyOf(leidas), List.copyOf(fallidas), activas.size());
    }

    /**
     * Una municipalidad del registro de tenants.
     *
     * @param id el identificador con el que se fija el contexto
     * @param ubigeo el codigo de seis digitos con el que se la nombra fuera del sistema
     * @param nombre como se llama, para poder decirlo en pantalla
     */
    public record Municipalidad(long id, String ubigeo, String nombre) {

        public Municipalidad {
            Objects.requireNonNull(ubigeo, "La municipalidad del registro tiene su ubigeo");
            Objects.requireNonNull(nombre, "La municipalidad del registro tiene su nombre");
        }
    }

    /**
     * Lo que el recorrido encontro, y lo que no pudo leer.
     *
     * @param leidas lo que cada rama con datos contesto, en el orden del recorrido
     * @param fallidas las municipalidades cuya rama lanzo, con el motivo
     * @param recorridas cuantas municipalidades activas se visitaron en total
     */
    public record Resultado<T>(List<T> leidas, List<Fallo> fallidas, int recorridas) {

        public Resultado {
            leidas = List.copyOf(leidas);
            fallidas = List.copyOf(fallidas);
        }

        /**
         * Si el recorrido esta completo.
         *
         * <p>Es la unica condicion bajo la que se puede totalizar: un total al que le falta una
         * municipalidad es un importe plausible y equivocado, que es la clase de error que este
         * proyecto trata como el peor.
         */
        public boolean completo() {
            return fallidas.isEmpty();
        }
    }

    /**
     * Una municipalidad que no se pudo leer.
     *
     * @param motivo el <b>tipo</b> del fallo, nunca su mensaje: el mensaje de una excepcion de
     *     persistencia nombra la tabla y la restriccion
     */
    public record Fallo(Municipalidad municipalidad, @Nullable String motivo) {

        public Fallo {
            Objects.requireNonNull(municipalidad, "Un fallo es de una municipalidad concreta");
        }
    }
}
