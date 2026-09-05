package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellasDeLaProyeccion;
import kamayuk.rentas.catastro.HuellasDelPadronDeCatastro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La anti-entropia: compara las huellas de la proyeccion con las del padron (P6, punto 4).
 *
 * <h2>La escalera, y por que es una escalera</h2>
 *
 * <p>Primero una cifra por sector —decenas—, y solo del sector que no cuadra se piden sus lotes.
 * Comparar lote a lote seria leer los 14 422 predios de Catacaos cada dia por los dos lados;
 * comparar solo el resumen no diria nunca cual lote difiere. Con la escalera, el caso normal cuesta
 * decenas de cifras y el caso malo cuesta un sector.
 *
 * <h2>Este caso de uso NO abre transaccion, y es deliberado</h2>
 *
 * <p>La abre {@link #huellasDeLaProyeccion}, que es quien lee la base. Envolver aqui el recorrido
 * entero dejaria dentro de la transaccion la llamada HTTP al otro sistema —una conexion del pool
 * retenida durante toda la peticion de red— y, peor, un fallo del otro lado marcaria como
 * *rollback-only* una transaccion que solo leia. Es el reparto que #54 midio para el resumen anual
 * y que #72 volvio a medir para la campana de beneficio.
 */
@Service
public class ConciliarConElPadron {

    private final HuellasDeLaProyeccion proyeccion;
    private final HuellasDelPadronDeCatastro padron;

    public ConciliarConElPadron(
            HuellasDeLaProyeccion proyeccion, HuellasDelPadronDeCatastro padron) {
        this.proyeccion = proyeccion;
        this.padron = padron;
    }

    /**
     * Compara y devuelve el informe.
     *
     * @param aLaFecha la fecha del informe, que entra como argumento (regla 9)
     */
    public AntiEntropia.Informe conciliar(LocalDate aLaFecha) {
        return AntiEntropia.comparar(padron.porSector(), huellasDeLaProyeccion(), aLaFecha);
    }

    /**
     * Los lotes de un sector que no cuadra, con los dos lados enfrentados.
     *
     * <p>Se pide <b>despues</b> de conciliar y solo para los sectores que el informe nombra.
     * Devuelve los identificadores que difieren, que es lo que un operador necesita para ir a
     * mirar: no las filas, porque esta comparacion no necesita ver el dato para saber que dos lados
     * no cuadran, y traerlo convertiria la anti-entropia en una segunda copia del padron.
     */
    public List<Long> lotesQueDifieren(String sectorCodigo, List<Long> enLaProyeccion) {
        List<Long> difieren = new ArrayList<>();
        for (HuellasDelPadronDeCatastro.HuellaDeLote lote : padron.deUnSector(sectorCodigo)) {
            if (!enLaProyeccion.contains(lote.predioId())) {
                difieren.add(lote.predioId());
            }
        }
        return List.copyOf(difieren);
    }

    /** Las huellas de este lado, dentro de su propia transaccion. */
    @Transactional(readOnly = true)
    public List<AntiEntropia.HuellaDeSector> huellasDeLaProyeccion() {
        return proyeccion.porSector();
    }
}
