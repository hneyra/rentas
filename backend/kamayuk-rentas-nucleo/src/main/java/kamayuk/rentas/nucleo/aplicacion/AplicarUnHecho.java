package kamayuk.rentas.nucleo.aplicacion;

import java.time.Instant;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica UN hecho a la proyeccion, en SU PROPIA transaccion (C-8).
 *
 * <h2>Por que es una clase aparte del bucle que la llama</h2>
 *
 * <p>Por los dos motivos que este proyecto ha medido cuatro veces:
 *
 * <ol>
 *   <li><b>Una transaccion por hecho, no una para la vuelta.</b> Envolver el bucle es el defecto de
 *       #328, #54, #430 y #247 §2: la fila que se rechaza marca la transaccion como
 *       <i>rollback-only</i> y se lleva por delante <b>lo que ya iba bien</b> —aqui, hechos ya
 *       aplicados que se volverian a aplicar— y ademas el informe.
 *   <li><b>La anotacion no se aplica por auto-invocacion.</b> En la misma clase que el bucle,
 *       {@code @Transactional} no la interceptaria y la separacion seria una promesa del javadoc:
 *       #430 con {@code ImportarCajas}, #536 con la carga cartografica y {@code RecibirPago} con su
 *       tercera vuelta.
 * </ol>
 *
 * <h2>El gestor de transacciones se nombra, y no es un detalle</h2>
 *
 * <p>{@code transaccionesDelIngestor} es el que abre transacciones sobre el pool de {@code
 * rol_ingestor_catastro}. Sin nombrarlo, Spring tomaria el primario —el de {@code kamayuk_app}— y
 * el {@code INSERT} moriria con {@code 42501}: `V4` y `V5` no le dan a la aplicacion mas que {@code
 * SELECT} sobre las cuatro proyecciones. Es la mitad de ADR-0027 §3 que no es una promesa.
 *
 * <h2>Y el {@code SET LOCAL} viene de ahi</h2>
 *
 * <p>Ese gestor es un {@code TenantTransactionManager}: al abrir la transaccion emite el {@code SET
 * LOCAL app.municipalidad_id} con el contexto que el runner fijo. Sin transaccion no hay contexto y
 * la politica RLS no devuelve vacio: <b>revienta</b> (#486).
 */
public class AplicarUnHecho {

    /** El nombre del bean de {@link kamayuk.rentas.nucleo.infraestructura.ingestor}. */
    public static final String TRANSACCIONES = "transaccionesDelIngestor";

    private final ProyeccionDeCatastro proyeccion;

    public AplicarUnHecho(ProyeccionDeCatastro proyeccion) {
        this.proyeccion = proyeccion;
    }

    /**
     * Aplica el hecho.
     *
     * @throws ProyeccionDeCatastro.NoSePuedeAplicar si no se podra aplicar nunca
     */
    @Transactional(transactionManager = TRANSACCIONES, propagation = Propagation.REQUIRES_NEW)
    public ProyeccionDeCatastro.Aplicacion aplicar(HechoRecibido hecho, Instant cuando) {
        return proyeccion.aplicar(hecho, cuando);
    }

    /**
     * Aparta un hecho que no se puede aplicar.
     *
     * <p>En SU PROPIA transaccion, y eso es lo mismo que {@code RechazoDelPago} tuvo que aprender
     * en tres vueltas (#P5D): la transaccion en la que el hecho fallo esta deshecha, asi que marcar
     * algo dentro de ella no sirve de nada — el {@code commit} muere igual y se lleva la marca por
     * delante.
     */
    @Transactional(transactionManager = TRANSACCIONES, propagation = Propagation.REQUIRES_NEW)
    public void matar(HechoRecibido hecho, String motivo, Instant cuando) {
        proyeccion.matar(hecho, motivo, cuando);
    }

    /** Cuantos hechos hay apartados y sin explicar. */
    @Transactional(transactionManager = TRANSACCIONES, readOnly = true)
    public long muertosSinExplicar() {
        return proyeccion.muertosSinExplicar();
    }
}
