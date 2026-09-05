package kamayuk.rentas.nucleo.aplicacion;

import kamayuk.rentas.nucleo.dominio.Vehiculo;

/**
 * Los campos del vehiculo como JSON, para los campos {@code datos_anteriores} y {@code
 * datos_nuevos} de la auditoria.
 *
 * <p>Escrito a mano y no con un serializador: traer Jackson hasta la capa de aplicacion la ataria a
 * la de presentacion, y son cinco campos. Es la misma decision que tomo {@code RegistrarVia}, y
 * aqui sale a una clase porque la comparten dos casos de uso.
 *
 * <p>Solo salen los campos que <b>identifican</b> al vehiculo. La auditoria no es una copia de la
 * fila: es el rastro de que cambio, y una copia entera de cada version convertiria la tabla que mas
 * crece del sistema en la que mas crece por mucho.
 */
final class FichaEnJson {

    private FichaEnJson() {}

    static String de(Vehiculo vehiculo) {
        return "{\"placa\":\""
                + escapar(vehiculo.placa().valor())
                + "\",\"marca\":\""
                + escapar(vehiculo.marca())
                + "\",\"modelo\":\""
                + escapar(vehiculo.modelo())
                + "\",\"anioFabricacion\":"
                + vehiculo.anioFabricacion().valor()
                + ",\"estado\":\""
                + vehiculo.estado()
                + "\"}";
    }

    /** Solo la placa: es lo que cambia, y es lo que el historial reconstruye. */
    static String soloLaPlaca(Vehiculo vehiculo) {
        return "{\"placa\":\"" + escapar(vehiculo.placa().valor()) + "\"}";
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
