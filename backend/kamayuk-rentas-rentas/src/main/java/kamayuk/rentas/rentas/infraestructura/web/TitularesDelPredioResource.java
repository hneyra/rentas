package kamayuk.rentas.rentas.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeTitulares.TitularResuelto;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeTitulares.TitularesResueltos;
import org.jspecify.annotations.Nullable;

/**
 * Los titulares de un predio tal como salen por HTTP (#366, ADR-0015 §2.4).
 *
 * <p><b>Lleva {@code vigenteA} siempre</b>, venga o no venga en la peticion: no existe «el titular»
 * —existe el titular vigente a una fecha— y quien dibuje un enlace con esta respuesta tiene que
 * poder decir a que dia corresponde (regla 9, RNF-075).
 *
 * <p><b>Y lleva la lista, no «el titular».</b> Un predio con dos conyuges al 50 % son dos filas;
 * una sucesion, tantas como herederos. La suma de los porcentajes no se publica ni se calcula aqui:
 * los vigentes no exceden 100 pero tampoco tienen que sumarlo, y un total invitaria a leer como
 * error del sistema lo que es titularidad parcialmente identificada (DAT-01 §4.2).
 *
 * <p>Lo que no lleva, y es el motivo de que este tipo exista en vez de publicar la titularidad: ni
 * el identificador interno del contribuyente —el codigo es con lo que se entra a su ficha—, ni su
 * documento, ni las fechas de la titularidad, ni el documento que la sustenta, ni nada del predio
 * mas alla del identificador con que se pregunto.
 */
public record TitularesDelPredioResource(
        long predioId, String vigenteA, List<TitularResource> titulares) {

    public static TitularesDelPredioResource de(TitularesResueltos resueltos) {
        List<TitularResource> filas = new ArrayList<>();
        for (TitularResuelto titular : resueltos.titulares()) {
            filas.add(
                    new TitularResource(
                            titular.codigo(),
                            titular.nombre(),
                            titular.condicion(),
                            titular.porcentaje()));
        }
        return new TitularesDelPredioResource(
                resueltos.predioId(), resueltos.vigenteA().toString(), filas);
    }

    /**
     * Un titular.
     *
     * <p>{@code codigo} y {@code nombre} nulos significan que el titular ya no esta en el padron.
     * Sale asi, y sale en la lista: es el predio que catastro tiene que revisar, y ocultarlo
     * escondería el defecto en vez de ensenarlo.
     */
    public record TitularResource(
            @Nullable String codigo,
            @Nullable String nombre,
            String condicion,
            Porcentaje porcentaje) {}
}
