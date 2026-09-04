package kamayuk.rentas.contribuyentes.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para sembrar el padron de contribuyentes <b>ficticio</b> de una instalacion
 * de demostracion (#290).
 *
 * <p>Misma forma que {@code DatosDeCargaVial}: propiedades y no argumentos de linea de comandos
 * —quedan fuera del historial del proceso—, y {@code municipalidadId} como {@code long} porque
 * {@code MunicipalidadId} no puede aparecer en la firma de nada fuera de {@code compartido}/{@code
 * plataforma} (ARQ-03 §3.1).
 *
 * <p>Que este numero apunte a una municipalidad marcada como de demostracion <b>no se comprueba
 * aqui</b>: se comprueba contra la base, en {@link CargarContribuyentesDeDemostracion}. Una
 * propiedad no puede validarse a si misma contra una fila.
 *
 * @param municipalidadId identificador de la municipalidad de demostracion que se siembra
 * @param archivo ruta al CSV de contribuyentes ficticios
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la siembra (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-contribuyentes-demo")
public record DatosDeCargaContribuyentesDemo(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaContribuyentesDemo {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-contribuyentes-demo.municipalidad-id, o no es un"
                            + " identificador valido");
        }
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-contribuyentes-demo.archivo, que no tiene valor por omision");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-demostracion"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Siembra de contribuyentes ficticios para la demostracion (#290)"
                        : observacion;
    }
}
