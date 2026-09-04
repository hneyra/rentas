package kamayuk.rentas.rentas.aplicacion;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Traduce los <b>codigos</b> que escribe un archivo de siembra a los identificadores internos que
 * los casos de uso reciben: el codigo del padron a {@code contribuyenteId}, el codigo de referencia
 * catastral a {@code predioId} y la placa al vehiculo.
 *
 * <h2>Por que es un servicio aparte y no tres metodos privados del importador</h2>
 *
 * <p>Por la transaccion. {@code VehiculoRepository} es un repositorio, no un caso de uso: llamarlo
 * fuera de una transaccion deja la consulta <b>sin</b> {@code SET LOCAL}, y entonces la politica
 * RLS no devuelve cero filas —<b>falla</b>, con «unrecognized configuration parameter»—. Es el
 * mismo defecto que {@code ConsultaDeVias} cerro en su dia y el que la marcha blanca de los valores
 * masivos volvio a destapar. Un metodo privado del propio importador tampoco valdria: la llamada a
 * si mismo no pasa por el proxy de Spring y la anotacion no haria nada.
 *
 * <p>Los otros dos ya vienen envueltos —{@link DirectorioDeContribuyentes} y {@link
 * PrediosDelContribuyente} abren la suya— y se exponen aqui de todos modos para que un importador
 * tenga <b>un</b> colaborador de resolucion y no tres, y para que este javadoc sea el unico sitio
 * donde hay que explicar por que la lectura necesita transaccion.
 *
 * <h2>El predio se busca entre los del titular, y eso es una comprobacion</h2>
 *
 * <p>{@link #predioDe} no pregunta «que predio tiene este codigo» sino «cual de los predios de esta
 * persona tiene este codigo». La diferencia importa en un archivo de siembra: una fila que nombra
 * un predio ajeno se rechaza por no encontrarlo, en vez de asentar una deuda o firmar una
 * transferencia sobre el predio de otro. Es tambien la unica forma de resolverlo sin un puerto
 * nuevo: {@link PrediosDelContribuyente} ya esta publicado.
 */
@Service
public class ReferenciasDeLaSiembra {

    private final DirectorioDeContribuyentes directorio;
    private final PrediosDelContribuyente predios;
    private final VehiculoRepository vehiculos;

    public ReferenciasDeLaSiembra(
            DirectorioDeContribuyentes directorio,
            PrediosDelContribuyente predios,
            VehiculoRepository vehiculos) {
        this.directorio = directorio;
        this.predios = predios;
        this.vehiculos = vehiculos;
    }

    /** El identificador interno del contribuyente cuyo codigo de padron es ese. */
    public Optional<Long> contribuyenteDe(String codigo) {
        return directorio
                .porCodigo(codigo.strip().toUpperCase(java.util.Locale.ROOT))
                .map(resumen -> resumen.id());
    }

    /**
     * El identificador del predio de ese contribuyente cuyo codigo de referencia catastral es ese,
     * resuelto a la fecha que se pide (regla 9).
     */
    public Optional<Long> predioDe(
            long contribuyenteId, String codigoReferenciaCatastral, LocalDate fecha) {
        String buscado = codigoReferenciaCatastral.strip();
        for (PredioDelContribuyente predio : predios.de(contribuyenteId, fecha)) {
            if (predio.codigoReferenciaCatastral().equals(buscado)) {
                return Optional.of(predio.predioId());
            }
        }
        return Optional.empty();
    }

    /** El vehiculo de esa placa, si esta en el padron. */
    @Transactional(readOnly = true)
    public Optional<Vehiculo> vehiculoDe(Placa placa) {
        return vehiculos.findByPlaca(placa);
    }
}
