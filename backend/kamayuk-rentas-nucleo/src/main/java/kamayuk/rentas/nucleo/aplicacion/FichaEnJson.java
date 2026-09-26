package kamayuk.rentas.nucleo.aplicacion;

import java.util.LinkedHashMap;
import java.util.Map;
import kamayuk.rentas.nucleo.dominio.Vehiculo;

/**
 * Los campos del vehiculo como JSON, para los campos {@code datos_anteriores} y {@code
 * datos_nuevos} de la auditoria.
 *
 * <p>Sin Jackson en la capa de aplicacion, y desde #434 tampoco a mano: entrega los campos con
 * nombre y {@code JsonDeAuditoria} los escribe con el escape entero. Hasta #434 aqui solo se
 * escapaban la barra y la comilla, y un caracter de control en la marca o el modelo daba 500.
 *
 * <p>Solo salen los campos que <b>identifican</b> al vehiculo. La auditoria no es una copia de la
 * fila: es el rastro de que cambio, y una copia entera de cada version convertiria la tabla que mas
 * crece del sistema en la que mas crece por mucho.
 */
final class FichaEnJson {

    private FichaEnJson() {}

    static Map<String, Object> de(Vehiculo vehiculo) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("placa", vehiculo.placa().valor());
        campos.put("marca", vehiculo.marca());
        campos.put("modelo", vehiculo.modelo());
        campos.put("anioFabricacion", vehiculo.anioFabricacion().valor());
        campos.put("estado", vehiculo.estado());
        return campos;
    }

    /** Solo la placa: es lo que cambia, y es lo que el historial reconstruye. */
    static Map<String, Object> soloLaPlaca(Vehiculo vehiculo) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("placa", vehiculo.placa().valor());
        return campos;
    }
}
