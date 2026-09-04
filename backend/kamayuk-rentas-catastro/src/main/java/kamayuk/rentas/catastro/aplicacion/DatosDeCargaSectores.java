package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar el catalogo de sectores de una municipalidad (#121).
 *
 * <p>Gemela de {@link DatosDeCargaVial}, y por los mismos motivos: son propiedades y no argumentos
 * de linea de comandos para que queden fuera del historial del proceso, y {@code municipalidadId}
 * llega como {@code long} porque {@code MunicipalidadId} no puede aparecer en la firma de nada
 * fuera de {@code compartido}/{@code plataforma} (ARQ-03 §3.1).
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyos sectores se cargan
 * @param archivo ruta al CSV {@code codigo,nombre,zona}
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-sectores")
public record DatosDeCargaSectores(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaSectores {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-sectores.municipalidad-id, o no es un identificador valido");
        }
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-sectores.archivo, que no tiene valor por omision");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-sectores"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Carga inicial del catalogo de sectores (#121)"
                        : observacion;
    }
}
