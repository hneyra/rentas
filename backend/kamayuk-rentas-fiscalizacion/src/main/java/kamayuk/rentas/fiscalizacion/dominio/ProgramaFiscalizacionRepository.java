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
