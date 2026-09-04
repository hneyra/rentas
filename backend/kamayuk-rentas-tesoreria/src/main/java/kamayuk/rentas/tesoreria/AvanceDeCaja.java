package kamayuk.rentas.tesoreria;

import java.time.LocalDate;

/**
 * Cuanto lleva cobrado la caja en un dia (#56, reutilizando #36 / RF-088).
 *
 * <p>Es la <b>sexta</b> API publica de este modulo, tras {@link ConveniosDelContribuyente}, {@link
 * CobrosDeTasas}, {@link RecibosDeTramite}, {@link ConvenioCoactivo} y {@link
 * FraccionamientoCoactivo}. Vive en el paquete raiz por lo mismo que las otras cinco: Spring
 * Modulith trata como interno todo lo que esta en un subpaquete, asi que esto es exactamente lo que
 * otro modulo puede ver de la caja. {@code ConsultaDeRecaudacion} vive en {@code .aplicacion} y por
 * tanto <b>no</b> se puede inyectar desde fuera, y eso es lo correcto: es el caso de uso completo,
 * con el avance del turno vivo y la distribucion por partida, y nada de eso pinta en un panel.
 *
 * <h2>Por que un puerto y no la clase de dentro</h2>
 *
 * <p>Porque el panel de inicio (#56) necesita una sola cifra —lo que va del dia— y no las siete
 * columnas del reporte de recaudacion. Publicar el caso de uso entero para eso ataria el panel a
 * cada cambio de RF-088, y ataria a RF-088 a no poder cambiar sin mirar quien mas lo usa.
 *
 * <h2>Sin bloquear la ventanilla</h2>
 *
 * <p>La lectura no toma el turno con {@code FOR UPDATE} —que es lo que hace la cobranza—, asi que
 * consultarla mientras el cajero cobra no pone a nadie a esperar. Es el punto de RF-088 y sigue
 * siendo el punto aqui: el avance se mira <b>durante</b> la jornada, no despues.
 */
public interface AvanceDeCaja {

    /**
     * Lo cobrado y lo anulado en los turnos de ese dia, en todas las cajas.
     *
     * <p>El dia es el <b>del turno</b> y no el del reloj, igual que en el arqueo: si el rango se
     * aplicara sobre el instante del recibo, la frontera de la medianoche dependeria de la zona
     * horaria con que se consultara, y el avance del dia podria no sumar lo mismo que la suma de
     * sus arqueos.
     *
     * <p>Un dia sin ningun turno abierto devuelve ceros, no un vacio: «hoy no ha entrado nada
     * todavia» es una respuesta, y es la que un panel de inicio da a las ocho de la manana.
     *
     * @param dia el dia de los turnos que se suman
     * @param aLaFecha la fecha con la que se responde; viaja con la cifra (regla 9, RNF-075)
     */
    RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha);
}
