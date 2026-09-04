package kamayuk.rentas.auditoria;

/**
 * Escribe la pista de auditoria (ADR-0008).
 *
 * <p>Es una interfaz para que los casos de uso se puedan probar sin base de datos, no porque se
 * espere otra implementacion.
 *
 * <p><b>Se llama dentro de la transaccion de la operacion auditada</b>, nunca despues ni en otra.
 * Es lo que hace que la promesa se cumpla en los dos sentidos: si la operacion se deshace, su
 * auditoria se deshace con ella —no queda constancia de algo que no paso—, y si la auditoria no se
 * puede escribir —observacion vacia—, la operacion se deshace entera.
 */
public interface Auditoria {

    /**
     * Asienta un registro.
     *
     * <p>El usuario, el equipo y la IP no se pasan: salen de {@link OrigenContext}, fijado una vez
     * en el borde de la aplicacion. Lo que si se pasa es la observacion, dentro del registro,
     * porque esa la escribe el usuario para esta operacion concreta.
     */
    void registrar(RegistroDeAuditoria registro);
}
