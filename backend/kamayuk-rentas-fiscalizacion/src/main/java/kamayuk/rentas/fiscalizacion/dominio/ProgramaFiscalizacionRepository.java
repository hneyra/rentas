package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

public interface ProgramaFiscalizacionRepository {

    /**
     * Guarda un programa nuevo.
     *
     * @throws ProgramaRepetido si ya hay otro con ese codigo en la municipalidad. Lo detecta la
     *     base con {@code programa_codigo_uq}, no una comprobacion en Java: dos peticiones
     *     simultaneas —o el reintento de la misma— pasan las dos por cualquier {@code if} (#422)
     */
    ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa);

    Optional<ProgramaFiscalizacion> findById(long id);

    /**
     * Cierra el programa (#341): mueve {@code estado} a {@code CERRADO} y ninguna otra columna.
     *
     * <p>La transición la calcula {@link ProgramaFiscalizacion#cerrado()} sobre la fila leída
     * <b>bloqueada</b>, en la misma transacción que la escribe: dos cierres simultáneos no leen los
     * dos {@code ABIERTO} y confirman los dos, sino que el segundo espera y ve {@code CERRADO}.
     *
     * @return el programa ya cerrado
     * @throws ProgramaFiscalizacion.TransicionIlegal si ya estaba cerrado
     * @throws IllegalStateException si no hay ningún programa con ese identificador en esta
     *     municipalidad: quien llama lo comprobó antes, así que es un defecto y no una respuesta
     */
    ProgramaFiscalizacion cerrar(long id);

    /** La grilla de programas de la pantalla {@code fisc_programa} (RF-050, #431). */
    Pagina<ProgramaFiscalizacion> consultar(CriterioDeProgramas criterio, Paginacion paginacion);

    /**
     * Ya hay un programa con ese codigo en esta municipalidad (#422).
     *
     * <p>El mensaje nombra el codigo que el usuario escribio y nada del esquema: ni la tabla ni la
     * restriccion, que es lo que {@code ManejadorDeErrores} existe para no dejar salir. Hasta #422
     * el choque salia como el 500 del motor, con incidencia ERROR y todo, ante algo tan corriente
     * como un reintento.
     */
    final class ProgramaRepetido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ProgramaRepetido(String codigo) {
            super(
                    "Ya hay un programa de fiscalizacion con el codigo '"
                            + codigo
                            + "' en esta municipalidad: un programa nuevo lleva su propio codigo");
        }
    }
}
