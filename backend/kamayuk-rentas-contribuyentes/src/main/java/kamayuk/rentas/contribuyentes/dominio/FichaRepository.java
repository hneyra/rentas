package kamayuk.rentas.contribuyentes.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lo que cuelga del contribuyente: donde esta, como se le ubica y quien responde con el.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). Ninguno borra: los domicilios y los vinculos
 * se cierran con su vigencia, y los contactos se dan de baja (regla 4, RNF-051).
 *
 * <p><b>Ninguna consulta de vigencia admite omitir la fecha</b> (regla 9). No hay un {@code
 * domicilioFiscalActual()}: si el metodo permitiera no pasar la fecha, la notificacion de un valor
 * de 2027 acabaria usando la direccion de hoy sin que nadie lo notara.
 */
public interface FichaRepository {

    // ---------- Domicilios ----------

    /** El domicilio del tipo pedido que rige en esa fecha. */
    Optional<Domicilio> domicilioVigenteA(
            long contribuyenteId, TipoDomicilio tipo, LocalDate fecha);

    /** Todo el historial, del mas reciente al mas antiguo. Nunca se pierde nada. */
    List<Domicilio> historialDeDomicilios(long contribuyenteId);

    Domicilio guardar(Domicilio domicilio);

    // ---------- Contactos ----------

    List<Contacto> contactosDe(long contribuyenteId, boolean soloVigentes);

    Contacto guardar(Contacto contacto);

    // ---------- Responsables solidarios ----------

    /** Quien responde por este contribuyente en esa fecha. */
    List<ResponsableSolidario> responsablesDe(long contribuyenteId, LocalDate fecha);

    /** De quien responde este contribuyente en esa fecha. La consulta inversa. */
    List<ResponsableSolidario> responsabilidadesDe(long responsableId, LocalDate fecha);

    ResponsableSolidario guardar(ResponsableSolidario responsable);
}
