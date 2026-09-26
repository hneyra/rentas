package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Los descargos contra PostgreSQL. Ningún método recibe la municipalidad (regla 2): sale del token
 * y la aplica la política RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}.</b> {@link #insertar} es el único punto de
 * escritura: V41 le retira a {@code kamayuk_app} el privilegio de {@code UPDATE} sobre {@code
 * descargo}, y V7 nunca le dio {@code DELETE}. Lo que en otro dominio sería corregir el resultado
 * aquí es dictar una {@link ResolucionDeGerencia}.
 */
public interface DescargoRepository {

    /**
     * Guarda un descargo nuevo.
     *
     * @throws DescargoRepetido si ya hay otro con ese numero de expediente en la municipalidad. Lo
     *     detecta la base con {@code descargo_numero_uq}: el doble envio del mismo formulario pasa
     *     dos veces por cualquier comprobacion en Java (#422)
     */
    Descargo insertar(Descargo descargo);

    /** El descargo con ese número de expediente, si existe en esta municipalidad. */
    Optional<Descargo> porNumeroDeExpediente(String numeroExpediente);

    Optional<Descargo> porId(long id);

    /** Los descargos presentados contra una papeleta, del más antiguo al más reciente. */
    List<Descargo> dePapeleta(long papeletaId);

    /**
     * Los recursos contra la papeleta que estaban <b>sin resolver</b> a esa fecha (#414):
     * presentados hasta {@code aLaFecha} y sin ninguna resolucion de gerencia que los resuelva
     * fechada hasta ese dia. Uno presentado despues no cuenta, y uno resuelto despues sigue
     * pendiente a esa fecha. Una sola consulta, y no {@code queResuelve} por descargo.
     */
    List<Descargo> pendientesDe(long papeletaId, java.time.LocalDate aLaFecha);

    /**
     * Ya hay un descargo con ese numero de expediente en esta municipalidad (#422).
     *
     * <p>El numero de expediente es el de mesa de partes, y lo teclea quien atiende: un doble envio
     * o un expediente ya usado salian como el 500 del indice unico, con incidencia ERROR.
     */
    final class DescargoRepetido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public DescargoRepetido(String numeroExpediente) {
            super(
                    "Ya hay un descargo registrado con el expediente '"
                            + numeroExpediente
                            + "' en esta municipalidad");
        }
    }
}
