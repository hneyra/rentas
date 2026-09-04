package kamayuk.rentas.rentas.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para sembrar deuda <b>ficticios</b> en una instalacion de demostracion.
 *
 * <p>Misma forma que {@code DatosDeCargaFichasDemo}, y {@code municipalidadId} como {@code long}
 * por la misma razon (ARQ-03 §3.1).
 *
 * <p>Que ese numero apunte a una municipalidad marcada como de demostracion se comprueba contra la
 * base, en {@link CargarDeudaDeDemostracion}, no aqui.
 *
 * @param municipalidadId identificador de la municipalidad de demostracion que se siembra
 * @param archivo ruta al CSV
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la siembra (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-deuda-demo")
public record DatosDeCargaDeudaDemo(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaDeudaDemo {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-deuda-demo.municipalidad-id, o no es un identificador valido");
        }
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-deuda-demo.archivo, que no tiene valor por omision");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-demostracion"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Siembra del saldo inicial ficticio del libro para la demostracion"
                        : observacion;
    }
}
