package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar los lotes del plano de una municipalidad (ADR-0021, #400).
 *
 * <p>Mismo trato que {@link DatosDeCargaVial}: propiedades y no argumentos de linea de comandos,
 * para que no queden en el historial del proceso ni en los registros del orquestador.
 *
 * <p><b>No hay una propiedad «es de demostracion».</b> Este cargador escribe en municipalidades de
 * verdad —es su unico motivo de existir— y por eso no lleva la guarda {@code SoloEnDemostracion}
 * que si llevan los seis pasos de la siembra de ejemplo. Lo que carga no son datos inventados: es
 * el plano catastral de la propia municipalidad.
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyo plano se carga
 * @param archivo ruta al CSV que produce {@code importar_predios_gpkg.py}
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-predios")
public record DatosDeCargaPredios(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaPredios {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-predios.municipalidad-id, o no es un identificador valido");
        }
        archivo = exigir(archivo, "sgtm.carga-predios.archivo");
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-predios"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Carga de lotes desde el plano catastral (ADR-0021)"
                        : observacion;
    }

    private static String exigir(String valor, String propiedad) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta " + propiedad + ", que no tiene valor por omision");
        }
        return valor.strip();
    }
}
