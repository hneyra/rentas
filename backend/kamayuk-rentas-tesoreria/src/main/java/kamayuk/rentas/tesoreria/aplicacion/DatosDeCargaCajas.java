package kamayuk.rentas.tesoreria.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar las ventanillas de una municipalidad (#430).
 *
 * <p>Son propiedades y no argumentos de línea de comandos, por lo mismo que {@code
 * DatosDeCargaVial}: quedan fuera del historial del proceso y de los registros del orquestador.
 *
 * <p>{@code municipalidadId} llega como {@code long} y no como {@code MunicipalidadId} a propósito:
 * ese tipo no puede aparecer en la firma de nada fuera de {@code compartido}/{@code plataforma}
 * (ARQ-03 §3.1).
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyas cajas se cargan
 * @param archivo ruta al CSV {@code codigo,nombre,serie,codigoArea,nombreArea}
 * @param usuarioDelProceso con qué nombre firma la auditoría lo que hace este proceso
 * @param observacion el «por qué» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-cajas")
public record DatosDeCargaCajas(
        long municipalidadId, String archivo, String usuarioDelProceso, String observacion) {

    public DatosDeCargaCajas {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-cajas.municipalidad-id, o no es un identificador valido");
        }
        archivo = exigir(archivo, "sgtm.carga-cajas.archivo");
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-cajas"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Alta de las ventanillas de la municipalidad (#430)"
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
