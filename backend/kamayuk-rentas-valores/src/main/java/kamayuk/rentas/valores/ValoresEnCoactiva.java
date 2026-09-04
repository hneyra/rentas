package kamayuk.rentas.valores;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;

/**
 * Lo que {@code coactiva} puede ver y responder de los valores de un contribuyente (ARQ-01 §4, #40,
 * RF-100).
 *
 * <p>Es la <b>segunda</b> API publica de {@code valores}, y existe porque {@link
 * ValoresDelContribuyente} no alcanza: aquella publica el numero impreso y no el identificador —el
 * expediente necesita la clave foranea—, no dice si el valor ya tiene su pase a coactiva, y no trae
 * las obligaciones que el valor formaliza, que son con las que el expediente pregunta cuanto se
 * debe hoy. Ampliar aquella habria obligado a la consulta unificada a arrastrar tres datos que no
 * pinta.
 *
 * <p>Vive en el paquete raiz, no en {@code .aplicacion} ni en {@code .dominio}: Spring Modulith
 * trata como interno todo lo que esta en un subpaquete, asi que un {@code import} desde {@code
 * coactiva} de {@code valores.dominio.Valor} no pasa la verificacion. <b>Esto es exactamente lo que
 * coactiva puede ver de los valores. Sus tablas, no.</b>
 *
 * <h2>Una sola escritura, y es la respuesta al pase</h2>
 *
 * <p>#39 dejo escrito que {@code TipoDeMovimiento.ACO} y {@code RCO} «son la respuesta de coactiva
 * y los escribe #40, cuando exista el expediente que responde». {@link #aceptarEnCoactiva} es esa
 * respuesta: cierra el ciclo que el pase (PCO) abrio. Emitir, notificar, prescribir y pasar a
 * coactiva siguen viviendo en {@code valores} y no se tocan desde aqui.
 */
public interface ValoresEnCoactiva {

    /**
     * Todos los valores emitidos a ese contribuyente, con su situacion mirada a esa fecha.
     *
     * <p>Todos, no solo los importables: quien importa tiene que poder decir <b>por que</b> rechaza
     * cada uno de los que no lo son, y para eso necesita verlos. Filtrar aqui dejaria a la pantalla
     * de importacion con una lista corta y sin explicacion.
     *
     * <p>Vacia si no se le ha emitido ninguno.
     *
     * @param contribuyenteId a quien se le emitieron
     * @param aLaFecha desde que dia se mira la situacion de cada uno (regla 9): sin ella «exigible»
     *     no significa nada
     */
    List<ValorParaCoactiva> delContribuyente(long contribuyenteId, LocalDate aLaFecha);

    /**
     * Un valor por su numero impreso, mirado a esa fecha.
     *
     * <p>Existe porque la pantalla de importacion admite pedir un valor concreto, y porque el
     * informe de importacion tiene que poder decir «no existe» de un numero que alguien tecleo.
     */
    Optional<ValorParaCoactiva> porNumero(String numero, LocalDate aLaFecha);

    /**
     * Registra que coactiva <b>acepto</b> el valor: el movimiento {@code ACO} de {@code
     * valor_movimiento} (V28).
     *
     * <p>La diligencia que lo hizo exigible y la fecha desde la que lo era se copian del pase, no
     * se vuelven a resolver: si se recalcularan, un plazo sellado despues daria otra fecha y el
     * expediente pareceria haber nacido en otro dia (#39).
     *
     * @param valorId el valor aceptado
     * @param fecha el dia de la aceptacion; entra como argumento, no sale del reloj
     * @param observacion por que se acepta (regla 10)
     * @throws SinPaseACoactiva si el valor no tiene su movimiento {@code PCO}. No es defensivo: sin
     *     pase no hay nada que responder, y aceptar lo que nadie paso dejaria un {@code ACO}
     *     huerfano que ninguna resolucion podria explicar
     */
    void aceptarEnCoactiva(long valorId, LocalDate fecha, Observacion observacion);

    /** El valor no tiene pase a coactiva, asi que no hay nada que aceptar. */
    final class SinPaseACoactiva extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinPaseACoactiva(String mensaje) {
            super(mensaje);
        }
    }
}
