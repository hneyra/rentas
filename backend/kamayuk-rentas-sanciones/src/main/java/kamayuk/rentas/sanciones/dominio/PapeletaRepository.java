package kamayuk.rentas.sanciones.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

public interface PapeletaRepository {

    Papeleta insertar(Papeleta papeleta);

    Optional<Papeleta> porNumero(String numero);

    /**
     * La papeleta de esa familia con ese número (#50).
     *
     * <p>{@link #porNumero(String)} resuelve solo tránsito, que es lo que {@code
     * CambiarNumeroDePapeleta} necesita (RF-067, la corrección del número solo existe ahí). Las
     * resoluciones de gerencia y los descargos alcanzan a las dos familias, y {@code
     * papeleta_numero_uq} es {@code (municipalidad, familia, numero)}: sin la familia, dos
     * papeletas distintas pueden compartir número y la consulta devolvería la que el motor
     * entregara primero.
     */
    Optional<Papeleta> porNumero(Familia familia, String numero);

    Optional<Papeleta> porId(long id);

    Pagina<Papeleta> buscar(CriterioDePapeleta criterio, Paginacion paginacion);

    /**
     * Cambia el número dejando traza en {@code papeleta_cambio_numero} (RF-067): un {@code UPDATE}
     * de la columna {@code numero}, nunca un alta ni una baja —la papeleta sigue siendo la misma
     * fila, con el mismo {@code id}, así que cualquier enlace que ya la referencie por {@code id}
     * sigue intacto. El usuario que hace el cambio no es un parámetro: lo resuelve la
     * implementación desde {@link kamayuk.rentas.auditoria.OrigenContext}, igual que {@code
     * usuario_registro} en {@link #insertar}.
     */
    Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo);
}
