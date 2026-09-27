package kamayuk.rentas.nucleo.aplicacion;

import java.util.List;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellasDeLaProyeccion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las huellas de este lado de la anti-entropia, leidas dentro de su propia transaccion (#611).
 *
 * <p>Es una clase aparte y no un metodo de {@link ConciliarConElPadron}, y esa es toda la razon de
 * existir: hasta #611 era {@code ConciliarConElPadron#huellasDeLaProyeccion()}, anotado {@code
 * Transactional}, y {@code conciliar} lo llamaba sobre {@code this}. Una llamada a {@code this} no
 * pasa por el proxy de Spring, asi que la anotacion no se aplicaba: sin transaccion no hay {@code
 * SET LOCAL}, y la politica RLS de {@code predio_ref} evaluaba la municipalidad vacia —{@code
 * BadSqlGrammarException}, medido—. La anti-entropia no llego a leer su propio lado nunca.
 */
@Service
public class LecturaDeLasHuellasDeLaProyeccion {

    private final HuellasDeLaProyeccion proyeccion;

    public LecturaDeLasHuellasDeLaProyeccion(HuellasDeLaProyeccion proyeccion) {
        this.proyeccion = proyeccion;
    }

    /** Una huella por sector de la proyeccion, con el {@code SET LOCAL} de la municipalidad. */
    @Transactional(readOnly = true)
    public List<AntiEntropia.HuellaDeSector> porSector() {
        return proyeccion.porSector();
    }
}
