package kamayuk.rentas.contribuyentes.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.FichaRepository;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import kamayuk.rentas.contribuyentes.dominio.TipoDomicilio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que cuelga del contribuyente, leido de una vez: domicilios, contactos y responsables.
 *
 * <p>Existe por dos motivos, y el segundo no se ve hasta que falla.
 *
 * <p>El primero es que <b>las escrituras de la ficha necesitan un identificador que nadie
 * publicaba</b>: dar de baja un contacto o cerrar un vinculo exige decir cual, y hasta aqui la
 * unica lectura del padron era la grilla de busqueda, que no baja de {@code contribuyente}. Un
 * endpoint que solo escribe sobre filas cuyos identificadores no se pueden conocer no es
 * utilizable.
 *
 * <p>El segundo es el de {@code ConsultaDeLaFichaVigente} en catastro (#486): las cuatro consultas
 * —el contribuyente, sus domicilios, sus contactos y sus responsables— van en <b>una sola</b>
 * transaccion. No es solo el {@code SET LOCAL} que RLS exige: cuatro transacciones distintas
 * dejarian sitio, entre una y otra, a una mudanza que cierra un domicilio, y la ficha saldria
 * diciendo a la vez que el contribuyente vive en dos sitios y en ninguno.
 *
 * <p><b>El domicilio vigente se resuelve a una fecha</b>, no «el ultimo» (regla 9). Reimprimir en
 * 2029 la ficha con que se atendio en marzo tiene que dar la direccion de marzo; con «el ultimo»,
 * el documento no explicaria la notificacion que se hizo. Es el defecto que #24 midio en el
 * dominio, y aqui se hereda en vez de reinventarse.
 */
@Service
public class ConsultaDeLaFichaDelContribuyente {

    private final ContribuyenteRepository padron;
    private final FichaRepository ficha;

    public ConsultaDeLaFichaDelContribuyente(
            ContribuyenteRepository padron, FichaRepository ficha) {
        this.padron = padron;
        this.ficha = ficha;
    }

    @Transactional(readOnly = true)
    public Optional<Ficha> de(long contribuyenteId, LocalDate cuando) {
        return padron.findById(contribuyenteId)
                .map(
                        contribuyente ->
                                new Ficha(
                                        contribuyente,
                                        ficha.domicilioVigenteA(
                                                        contribuyenteId,
                                                        TipoDomicilio.FISCAL,
                                                        cuando)
                                                .orElse(null),
                                        ficha.domicilioVigenteA(
                                                        contribuyenteId,
                                                        TipoDomicilio.PROCESAL,
                                                        cuando)
                                                .orElse(null),
                                        ficha.historialDeDomicilios(contribuyenteId),
                                        ficha.contactosDe(contribuyenteId, false),
                                        ficha.responsablesDe(contribuyenteId, cuando),
                                        cuando));
    }

    /**
     * La ficha completa a una fecha.
     *
     * <p>{@code domicilioFiscal} y {@code domicilioProcesal} son los <b>vigentes a {@code
     * cuando}</b>, y pueden ser nulos: un contribuyente recien dado de alta todavia no tiene
     * ninguno, y decirlo es mas honesto que devolver el ultimo que hubo.
     *
     * <p>{@code historialDeDomicilios} los trae todos, cerrados incluidos: nada se borra (regla 4),
     * y una notificacion de hace tres anios se defiende ensenando la direccion que regia entonces.
     */
    public record Ficha(
            Contribuyente contribuyente,
            @Nullable Domicilio domicilioFiscal,
            @Nullable Domicilio domicilioProcesal,
            List<Domicilio> historialDeDomicilios,
            List<Contacto> contactos,
            List<ResponsableSolidario> responsables,
            LocalDate aLaFecha) {}
}
