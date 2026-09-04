package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Los descargos contra PostgreSQL. Ningún método recibe la municipalidad (regla 2): sale del token
 * y la aplica la política RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}.</b> {@link #insertar} es el único punto de
 * escritura: V41 le retira a {@code sgtm_app} el privilegio de {@code UPDATE} sobre {@code
 * descargo}, y V7 nunca le dio {@code DELETE}. Lo que en otro dominio sería corregir el resultado
 * aquí es dictar una {@link ResolucionDeGerencia}.
 */
public interface DescargoRepository {

    Descargo insertar(Descargo descargo);

    /** El descargo con ese número de expediente, si existe en esta municipalidad. */
    Optional<Descargo> porNumeroDeExpediente(String numeroExpediente);

    Optional<Descargo> porId(long id);

    /** Los descargos presentados contra una papeleta, del más antiguo al más reciente. */
    List<Descargo> dePapeleta(long papeletaId);
}
