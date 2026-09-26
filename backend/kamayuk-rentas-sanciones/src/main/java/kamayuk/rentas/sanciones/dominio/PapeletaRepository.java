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
     *
     * @throws NumeroDePapeletaEnUso si otra papeleta de la misma familia ya tiene ese numero. Lo
     *     detecta la base con {@code papeleta_numero_uq}, no una comprobacion en Java (#422)
     */
    Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo);

    /**
     * Anula la papeleta: la <b>única</b> escritura que mueve su estado (#267).
     *
     * <p>Mueve <b>una columna</b> y ninguna más, y no es una convención: desde {@code V20} {@code
     * kamayuk_app} no tiene {@code UPDATE} sobre la tabla sino sobre {@code (numero, estado)}, así
     * que un {@code UPDATE} que tocara la placa, la hora, el lugar o el importe saldría con {@code
     * 42501}. Lo que el inspector escribió en la calle y firmó el infractor no se corrige en la
     * base: se anula la papeleta y se levanta otra.
     *
     * <p>La transición la decide el dominio ({@link Papeleta#anulada}) <b>antes</b> de escribir,
     * como en {@code ActaFiscalizacionRepository#anular}: si es ilegal no se escribe nada.
     *
     * @return la papeleta ya anulada
     * @throws Papeleta.TransicionIlegal si en ese estado ya no se debe nada
     */
    Papeleta anular(long papeletaId);

    /**
     * Si una resolución de gerencia dejó sin efecto la multa de esta papeleta (#385).
     *
     * <p>Lo contesta la base con {@link EstadoDePapeleta#DEJADA_SIN_EFECTO}, <b>el mismo
     * predicado</b> con que la fase la deja sin palabra y los padrones la dejan de contar como
     * pendiente. Es lo que preguntan las guardas de {@code RegistrarDescargo} y {@code
     * ResolverConResolucionDeGerencia}: el estado no lo dice —sigue {@code IMPUESTA}, porque la
     * resolución no lo toca— y una segunda copia del predicado en Java es la que divergiría.
     *
     * @return {@code false} también si la papeleta no existe en esta municipalidad
     */
    boolean dejadaSinEfecto(long papeletaId);

    /**
     * El numero nuevo ya lo tiene otra papeleta de la misma familia en esta municipalidad (#422).
     *
     * <p>Hasta #422 salia como el 500 del indice unico, que invita a reintentar algo que no va a
     * funcionar nunca: el numero es de otra papeleta, y lo que hay que corregir es el que se
     * escribio.
     */
    final class NumeroDePapeletaEnUso extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public NumeroDePapeletaEnUso(String numero) {
            super(
                    "El numero '"
                            + numero
                            + "' ya lo tiene otra papeleta en esta municipalidad: una papeleta no"
                            + " puede tomar el numero de otra");
        }
    }
}
