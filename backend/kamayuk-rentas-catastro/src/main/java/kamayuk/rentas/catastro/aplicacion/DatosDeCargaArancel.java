package kamayuk.rentas.catastro.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para cargar el arancel de terreno por via de una municipalidad, contra un
 * conjunto de parametros ya abierto.
 *
 * <p>{@code conjuntoId} llega resuelto porque abrir la version es un acto administrativo de {@code
 * parametros} —{@code AdministrarParametros.abrirVersion}—, y {@code catastro} solo importa el
 * paquete raiz de ese contexto (ARQ-01 §4, regla 1): Spring Modulith rechaza cualquier import a
 * {@code parametros.aplicacion} desde aqui. Cargar el arancel y abrir la version del ejercicio son
 * dos actos distintos a proposito, igual que {@code aranceles-2026.md} S1.4 los documenta en ese
 * orden.
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyo arancel se carga
 * @param conjuntoId el conjunto de parametros, ya abierto y sin sellar, contra el que cuelga este
 *     arancel; lo imprime {@code AbrirConjuntoDeParametros} (#247 §2)
 * @param archivo ruta al CSV {@code viaCodigo,tramo,valorM2,documentoFuente} de un ejercicio, que
 *     produce {@code importar_arancel_via_gpkg.py}
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso
 * @param observacion el «por que» de la carga (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.carga-arancel")
public record DatosDeCargaArancel(
        long municipalidadId,
        long conjuntoId,
        String archivo,
        String usuarioDelProceso,
        String observacion) {

    public DatosDeCargaArancel {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-arancel.municipalidad-id, o no es un identificador valido");
        }
        if (conjuntoId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.carga-arancel.conjunto-id, o no es un identificador valido. Lo abre"
                            + " AbrirConjuntoDeParametros antes de correr esta carga");
        }
        archivo = exigir(archivo, "sgtm.carga-arancel.archivo");
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "carga-arancel"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Carga del arancel de terreno por via, plano grafico del MEF"
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
