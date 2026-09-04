package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para sembrar predios y fichas <b>ficticios</b> en una instalacion de
 * demostracion (#290).
 *
 * <p>Misma forma que {@link DatosDeCargaVial}, y {@code municipalidadId} como {@code long} por la
 * misma razon (ARQ-03 §3.1).
 *
 * <p>Que ese numero apunte a una municipalidad marcada como de demostracion se comprueba contra la
 * base, en {@link CargarFichasDeDemostracion}, no aqui.
 *
 * @param municipalidadId identificador de la municipalidad de demostracion que se siembra
 * @param archivo ruta al CSV de predios fichados ficticios
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la siembra (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-fichas-demo")
public record DatosDeCargaFichasDemo(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaFichasDemo {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-fichas-demo.municipalidad-id, o no es un identificador"
                            + " valido");
        }
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-fichas-demo.archivo, que no tiene valor por omision");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-demostracion"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Siembra de predios y fichas ficticios para la demostracion (#290)"
                        : observacion;
    }
}
