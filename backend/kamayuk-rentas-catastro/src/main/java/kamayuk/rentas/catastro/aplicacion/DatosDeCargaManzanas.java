package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar las manzanas de una municipalidad (#121).
 *
 * <p>Gemela de {@link DatosDeCargaVial} y de {@link DatosDeCargaSectores}. El archivo referencia
 * cada sector por su <b>codigo</b>, asi que los sectores tienen que estar cargados antes: ver
 * {@link CargarManzanas}.
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyas manzanas se cargan
 * @param archivo ruta al CSV {@code sectorCodigo,codigo}
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-manzanas")
public record DatosDeCargaManzanas(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaManzanas {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-manzanas.municipalidad-id, o no es un identificador valido");
        }
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-manzanas.archivo, que no tiene valor por omision");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-manzanas"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Carga inicial de manzanas (#121)"
                        : observacion;
    }
}
