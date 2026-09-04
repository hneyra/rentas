package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar el catalogo vial inicial de una municipalidad (#121).
 *
 * <p>Son propiedades y no argumentos de linea de comandos por la misma razon que {@code
 * DatosDeImplantacion}: quedan fuera del historial del proceso y de los registros del orquestador.
 *
 * <p>{@code municipalidadId} llega como {@code long} y no como {@code MunicipalidadId} a proposito:
 * ese tipo no puede aparecer en la firma de nada fuera de {@code compartido}/{@code plataforma}
 * (ARQ-03 §3.1, {@code ReglasDeArquitectura}). Se obtiene del resultado de {@code
 * ImplantarMunicipalidad} —su registro deja «id N» en el log— o consultando {@code municipalidad}
 * por su {@code ubigeo}.
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyo catalogo se carga
 * @param archivo ruta al CSV {@code codigo,tipo,nombre,ubigeo} que produce {@code
 *     importar_arancel_via_gpkg.py} o que se preparo a mano
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-vial")
public record DatosDeCargaVial(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaVial {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-vial.municipalidad-id, o no es un identificador valido");
        }
        archivo = exigir(archivo, "sgtm.carga-vial.archivo");
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-vial"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Carga inicial del catalogo vial (#121)"
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
