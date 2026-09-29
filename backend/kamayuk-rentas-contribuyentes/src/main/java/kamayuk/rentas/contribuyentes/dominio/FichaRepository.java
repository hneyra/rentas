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

    /**
     * El tramo abierto del tipo pedido: el ultimo del historial, el que una mudanza cierra (#420).
     *
     * <p><b>No es «el domicilio de hoy»</b>, y por eso no contradice lo de arriba: un tramo abierto
     * puede empezar en el futuro —una mudanza registrada con fecha de mes que viene— y entonces hoy
     * no rige. Para notificar se pregunta {@link #domicilioVigenteA} con la fecha; esto solo sirve
     * para anadir un tramo <b>al final</b> de la linea de tiempo.
     *
     * <p>Si hubiera mas de uno —el PROCESAL no tiene indice parcial, y antes de #420 una mudanza
     * hacia atras dejaba dos—, gana el que empezo despues.
     */
    Optional<Domicilio> tramoAbierto(long contribuyenteId, TipoDomicilio tipo);

    /** Todo el historial, del mas reciente al mas antiguo. Nunca se pierde nada. */
    List<Domicilio> historialDeDomicilios(long contribuyenteId);

    /**
     * Abre el domicilio si es nuevo, o lo cierra si ya existe: de un domicilio guardado solo cambia
     * su fin de vigencia, porque mudarse es abrir otro. Cerrar uno que ya estaba cerrado falla, y
     * asi dos cierres simultaneos no se pisan.
     */
    Domicilio guardar(Domicilio domicilio);

    // ---------- Contactos ----------

    /**
     * Los contactos del contribuyente por tipo; con {@code soloVigentes}, sin los dados de baja. Un
     * contacto no tiene vigencia con fechas, asi que aqui no se pregunta a una fecha.
     */
    List<Contacto> contactosDe(long contribuyenteId, boolean soloVigentes);

    /**
     * Registra el contacto si es nuevo, o reescribe sus datos si ya existe. Darlo de baja es
     * guardarlo no vigente: no se borra (regla 4).
     */
    Contacto guardar(Contacto contacto);

    // ---------- Responsables solidarios ----------

    /** Quien responde por este contribuyente en esa fecha. */
    List<ResponsableSolidario> responsablesDe(long contribuyenteId, LocalDate fecha);

    /** De quien responde este contribuyente en esa fecha. La consulta inversa. */
    List<ResponsableSolidario> responsabilidadesDe(long responsableId, LocalDate fecha);

    /**
     * Abre el vinculo si es nuevo, o lo cierra si ya existe, igual que un domicilio: lo unico que
     * cambia de un vinculo guardado es su fin de vigencia.
     */
    ResponsableSolidario guardar(ResponsableSolidario responsable);
}
