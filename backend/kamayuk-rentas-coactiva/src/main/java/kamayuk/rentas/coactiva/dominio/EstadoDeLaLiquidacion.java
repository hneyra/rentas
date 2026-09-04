package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * En que situacion esta una liquidacion de costas <b>a una fecha</b> (#42, RF-104).
 *
 * <p><b>No es una columna.</b> V35 no le puso ninguna a {@code liquidacion_costas}, por lo mismo
 * que V30 se la retiro al recibo, V31 al convenio, V32 al turno, V33 al expediente y V34 al acto:
 * la tabla no admite {@code UPDATE}, asi que diria {@code ACTIVA} para siempre —tambien de una
 * liquidacion ya cobrada— y cualquier consulta ad hoc la leeria como la verdad.
 *
 * <p>Se <b>deriva</b> del libro, que es donde el hecho ocurre: la liquidacion esta cancelada cuando
 * su obligacion de costas ya no debe nada. {@link #segunLoPendiente} es el unico sitio donde se
 * deriva, y es una funcion pura (regla 6): entra un importe y sale un estado, sin base y sin reloj.
 *
 * <h2>Los dos estados del prototipo que no estan aqui</h2>
 *
 * <p>El desplegable «Estado» de {@code costas_procesales} ofrece cuatro: {@code A — ACTIVA}, {@code
 * N — NOTIFICADA}, {@code C — CANCELADA} y {@code X — ANULADA}. Estan los dos que el sistema puede
 * responder hoy con lo que tiene, y faltan dos a proposito:
 *
 * <ul>
 *   <li><b>NOTIFICADA</b> exigiria diligenciar la liquidacion como se diligencia un acto coactivo
 *       (#41). Es un acto con su acuse y su reintento, no una bandera, y #42 no lo cubre.
 *   <li><b>ANULADA</b> exigiria un movimiento de anulacion que reversara el cargo del libro —el
 *       dinero ya esta cargado—, con su motivo y su autorizacion, como el quiebre del convenio
 *       (#35). Tampoco es una bandera.
 * </ul>
 *
 * <p>Los dos se rechazan explicitamente en el filtro en vez de traducirse a algo parecido, que es
 * el mismo criterio con que {@code ConvenioController} rechaza «CUMPLIDO» y «EN RIESGO»: devolver
 * una lista bajo una etiqueta que el sistema no sabe calcular es peor que decir que no se sabe.
 */
public enum EstadoDeLaLiquidacion {

    /** Liquidada y con su cargo vivo: queda costa por cobrar a la fecha consultada. */
    ACTIVA("A"),

    /** Su obligacion de costas no debe nada a la fecha consultada. */
    CANCELADA("C");

    private final String codigo;

    EstadoDeLaLiquidacion(String codigo) {
        this.codigo = codigo;
    }

    /** El codigo de una letra que usa el desplegable del manual. */
    public String codigo() {
        return codigo;
    }

    /**
     * El estado que describe ese pendiente.
     *
     * <p>Funcion pura: no consulta nada. Quien llama ya le pregunto al libro cuanto queda de la
     * obligacion de costas <b>a la fecha</b>, que es la unica fuente de esa cifra.
     */
    public static EstadoDeLaLiquidacion segunLoPendiente(Dinero pendiente) {
        Objects.requireNonNull(pendiente, "El estado se deriva de lo pendiente a una fecha");
        return pendiente.esPositivo() ? ACTIVA : CANCELADA;
    }

    /**
     * El estado cuyo nombre o codigo coincide.
     *
     * @throws IllegalArgumentException si es uno de los dos que el sistema no sabe calcular, o si
     *     no es ninguno
     */
    public static EstadoDeLaLiquidacion porNombre(String nombre) {
        String limpio =
                Objects.requireNonNull(nombre, "Falta el estado").strip().toUpperCase(Locale.ROOT);
        // «A — ACTIVA» tal como lo manda el desplegable: se queda con la primera palabra.
        String primera = limpio.split("[^A-Z]", 2)[0];
        for (EstadoDeLaLiquidacion estado : values()) {
            if (estado.name().equals(limpio)
                    || estado.name().equals(primera)
                    || estado.codigo.equals(limpio)
                    || estado.codigo.equals(primera)) {
                return estado;
            }
        }
        throw new IllegalArgumentException(
                "Estado de liquidacion desconocido: '"
                        + nombre
                        + "'. Se admite ACTIVA o CANCELADA, que se derivan del libro. «NOTIFICADA»"
                        + " exige diligenciar la liquidacion con su acuse y «ANULADA» exige"
                        + " reversar su cargo con su motivo: los dos son actos, no banderas, y #42"
                        + " no los cubre");
    }
}
