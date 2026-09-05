package kamayuk.rentas.documentos;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import kamayuk.rentas.compartido.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lee {@code municipalidad.es_demostracion} de la municipalidad en curso.
 *
 * <h2>La consulta no lleva la municipalidad en Java</h2>
 *
 * <p>{@code municipalidad} no es una tabla de tenant —su politica es {@code FOR SELECT USING
 * (true)}, porque los procesos masivos la recorren entera—, asi que aqui si hace falta un {@code
 * WHERE}. Lo que no hace falta es que el identificador pase por Java: lo pone el motor con {@code
 * current_setting('app.municipalidad_id')}, el mismo parametro de sesion que fija {@code SET LOCAL}
 * y que consultan las politicas RLS. Asi una lectura sin contexto <b>falla</b> en lugar de
 * responder por la municipalidad equivocada (regla 2).
 *
 * <h2>Y por eso la pregunta abre su propia transaccion (#535)</h2>
 *
 * <p>{@code SET LOCAL} lo emite {@code TenantTransactionManager} <b>al abrir una transaccion</b>, y
 * es el unico sitio donde el identificador llega a la base (ARQ-03 §2). Preguntada fuera de una,
 * esta consulta no responde por la municipalidad equivocada: <b>revienta</b> —«unrecognized
 * configuration parameter» sobre una conexion recien abierta, «invalid input syntax for type
 * bigint: ""» sobre una del pool que ya llevo el parametro alguna vez—, y el borde lo traduce a un
 * {@code 500}.
 *
 * <p>Eso es lo que hacia que los tres formatos de la ficha del contribuyente contestaran 500: el
 * controlador leia dentro de la transaccion del caso de uso y <b>dibujaba el documento despues de
 * que cerrara</b>. No es de una pantalla: {@link GeneradorDeDocumentos#marcar} se pregunta por cada
 * documento, y hay <b>dieciocho</b> endpoints en cinco modulos con esa misma forma, mas {@link
 * EmitirDocumento#emitirEnLote}. Se arregla aqui, en el unico sitio que necesita la base, y no en
 * cada uno de ellos.
 *
 * <p>La transaccion se abre con un {@link TransactionTemplate} y no con {@code @Transactional}
 * porque tiene que abrirse <b>solo cuando la cache no sabe la respuesta</b>: la anotacion va en el
 * borde del metodo y abriria una transaccion por documento, que es justo lo que la cache existe
 * para evitar. Es el mismo reparto que {@code RecorridoPorMunicipalidades} y {@code
 * ReconstruirPadron} ya hacen en el sistema —la transaccion se abre en un punto de dentro del
 * metodo, no en su borde—, y es lo que {@code SoloEnDemostracion} resuelve con un {@code @Service}
 * aparte porque alli el punto si coincide con el borde. Con propagacion {@code REQUIRED} —la de por
 * omision—, un llamador que ya tenga transaccion abierta —{@link EmitirDocumento}, {@code
 * DuplicadoDeRecibo}— <b>participa en la suya</b> y no toma otra conexion.
 *
 * <h2>Por que hay cache, y por que se puede</h2>
 *
 * <p>Se pregunta una vez por documento, y {@code emitirEnLote} emite miles. Sin cache, cada recibo
 * del padron llevaria su propia consulta.
 *
 * <p>Se puede porque el valor no cambia en caliente: quitarle la marca a una instalacion es un
 * {@code UPDATE} de {@code kamayuk_owner}, una operacion de implantacion, y las de implantacion
 * reinician el proceso. No hay ninguna pantalla que lo cambie, y ese es justamente el punto de que
 * el hecho viva en la base y no en configuracion.
 *
 * <p>La cache es por municipalidad y no global: una sola instalacion atiende a muchas, y una cache
 * de un solo valor haria que la primera que emitiera decidiera por todas. Ese fallo no se ve
 * probando con una.
 */
@Component
public class RegimenDeLaInstalacionJdbc implements RegimenDeLaInstalacion {

    private static final String CONSULTA =
            "SELECT es_demostracion FROM municipalidad"
                    + " WHERE id = current_setting('app.municipalidad_id')::bigint";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaccion;
    private final Map<Long, Boolean> sabido = new ConcurrentHashMap<>();

    public RegimenDeLaInstalacionJdbc(JdbcClient jdbc, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.transaccion = new TransactionTemplate(transacciones);
        this.transaccion.setReadOnly(true);
    }

    @Override
    public boolean esDeDemostracion() {
        return sabido.computeIfAbsent(
                TenantContext.actual().valor(), municipalidad -> consultarEnSuTransaccion());
    }

    /**
     * La consulta, dentro de una transaccion propia si el llamador no traia ninguna.
     *
     * <p>El {@code requireNonNull} no es ceremonia: {@link TransactionTemplate#execute} devuelve lo
     * que devuelva el cuerpo, y de las dos respuestas posibles la comoda —{@code false}— es la que
     * emite un papel <b>sin</b> marca.
     */
    private boolean consultarEnSuTransaccion() {
        return Objects.requireNonNull(
                transaccion.execute(estado -> consultar()),
                "La consulta del regimen no devolvio nada, y sin saberlo no se emite ningun"
                        + " documento: saldria sin marca");
    }

    private boolean consultar() {
        return jdbc.sql(CONSULTA)
                .query(Boolean.class)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La municipalidad en curso no esta en el registro de"
                                                + " municipalidades. Sin saber si la instalacion es de"
                                                + " demostracion no se puede emitir ningun documento:"
                                                + " saldria sin marca."));
    }
}
