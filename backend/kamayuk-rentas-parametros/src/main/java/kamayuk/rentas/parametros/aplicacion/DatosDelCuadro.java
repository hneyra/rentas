package kamayuk.rentas.parametros.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para publicar un cuadro normativo nacional (D-13, ADR-0017).
 *
 * <p>Una sola cosa, y no es una cifra: el <b>manifiesto</b> de ediciones. Cada fila del manifiesto
 * nombra la edicion —tipo, clave, vigencia, documento fuente y las dos firmas del corpus— y el
 * archivo de filas del que sale el cuadro, con su {@code sha256}. Las cifras estan en ese archivo
 * de filas, que es el derivado mecanico de la fuente y vive en el corpus.
 *
 * <p><b>No hay {@code municipalidadId}</b>: lo que se publica es de ambito nacional y va con {@code
 * municipalidad_id} nulo. <b>Tampoco hay observacion</b>, por lo mismo que en {@link
 * DatosDeLaPublicacion}: el «por que» son las columnas del propio parametro, y {@code
 * rol_carga_parametros} no tiene {@code INSERT} sobre {@code auditoria}.
 *
 * @param archivo ruta al manifiesto (ver {@code
 *     docs/10-negocio/valores-normativos/publicacion/README.md})
 * @param usuarioDelProceso con que nombre aparece este proceso en el registro. No firma nada
 */
@ConfigurationProperties("sgtm.publicacion-cuadros")
public record DatosDelCuadro(String archivo, String usuarioDelProceso) {

    public DatosDelCuadro {
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.publicacion-cuadros.archivo: sin el manifiesto no hay ninguna"
                            + " edicion que publicar, y publicar un cuadro de otra parte es lo que"
                            + " ADR-0007 impide");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "publicacion-cuadros"
                        : usuarioDelProceso;
    }
}
