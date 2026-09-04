package kamayuk.rentas.parametros.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para publicar valores normativos en {@code parametro_tributario} (#188, #247
 * §4).
 *
 * <p>Son dos cosas, y ninguna es una cifra: el archivo derivado del corpus y con que nombre queda
 * el proceso en el registro. <b>No hay {@code municipalidadId}</b> y no es un olvido: lo que se
 * publica aqui es de ambito nacional —la UIT, los tramos del TUO— y va con {@code municipalidad_id}
 * nulo. Pedirlo obligaria a inventar una municipalidad para un dato que no es de ninguna.
 *
 * <p><b>Tampoco hay observacion</b>, al reves que en {@link DatosDelConjunto}. La regla 10 exige
 * que toda modificacion de datos lleve el «por que» del usuario, y aqui lo lleva: son las cuatro
 * columnas del propio parametro —{@code documento_fuente}, {@code usuario_carga}, {@code
 * usuario_aprueba} y {@code fecha_carga}—, que dicen de que norma sale la cifra y quien la leyo dos
 * veces. Una observacion suelta seria ademas irrecuperable: {@code rol_carga_parametros} no tiene
 * {@code INSERT} sobre {@code auditoria} (V7), y no lo tiene precisamente porque no le hace falta.
 *
 * @param archivo ruta al CSV derivado del corpus (ver {@code
 *     docs/10-negocio/valores-normativos/publicacion/README.md})
 * @param usuarioDelProceso con que nombre aparece este proceso en el registro. No firma nada: las
 *     dos firmas que llegan a la base son las del corpus, columna por columna del archivo
 */
@ConfigurationProperties("sgtm.publicacion-parametros")
public record DatosDeLaPublicacion(String archivo, String usuarioDelProceso) {

    public DatosDeLaPublicacion {
        if (archivo == null || archivo.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta sgtm.publicacion-parametros.archivo: sin el derivado del corpus no hay"
                            + " nada que publicar, y publicar cifras de otra parte es lo que"
                            + " ADR-0007 impide");
        }
        archivo = archivo.strip();
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "publicacion-parametros"
                        : usuarioDelProceso;
    }
}
