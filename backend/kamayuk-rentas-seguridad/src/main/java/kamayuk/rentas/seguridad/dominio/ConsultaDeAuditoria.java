package kamayuk.rentas.seguridad.dominio;

import java.time.LocalDate;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Filtros de la consulta de auditoria (RF-124): quien modifico que, cuando, desde que maquina e IP,
 * y con que observacion.
 *
 * <p>El <b>ejercicio es obligatorio</b> y no es un filtro mas: la tabla esta particionada por el, y
 * una consulta sin ejercicio recorre todas las particiones. Con el volumen que la auditoria alcanza
 * —una fila por cada modificacion del sistema— eso es la diferencia entre una pantalla que responde
 * y una que hay que cancelar. Obligarlo aqui es preferible a descubrirlo en produccion.
 *
 * <p>Los demas filtros son opcionales y se combinan con Y.
 *
 * <p>La <b>operacion es un {@link Operacion}, no una cadena</b> (#544): su vocabulario es cerrado
 * —el mismo que el CHECK de {@code auditoria.operacion}— y con un texto libre una palabra que no
 * existe no da error, da una pagina vacia, que se lee como «no hubo ninguna». El prototipo llama a
 * este filtro «Acción» y ofrece ademas ELIMINACIÓN, que no puede ocurrir: la aplicacion no borra
 * (RNF-051, regla 4).
 *
 * @param ejercicio clave de particion; obligatorio
 * @param usuario quien hizo el cambio, si se filtra por el
 * @param tabla sobre que tabla, si se filtra por ella
 * @param operacion que clase de acto, si se filtra por ella
 * @param desde primer dia del rango, inclusive
 * @param hasta ultimo dia del rango, inclusive
 */
public record ConsultaDeAuditoria(
        Ejercicio ejercicio,
        @Nullable String usuario,
        @Nullable String tabla,
        @Nullable Operacion operacion,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta) {

    public ConsultaDeAuditoria {
        if (ejercicio == null) {
            throw new IllegalArgumentException(
                    "La consulta de auditoria necesita su ejercicio: es la clave de particion, y"
                            + " sin el la consulta recorre todas");
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El rango termina antes de empezar: " + desde + " a " + hasta);
        }
    }

    public static ConsultaDeAuditoria delEjercicio(Ejercicio ejercicio) {
        return new ConsultaDeAuditoria(ejercicio, null, null, null, null, null);
    }
}
