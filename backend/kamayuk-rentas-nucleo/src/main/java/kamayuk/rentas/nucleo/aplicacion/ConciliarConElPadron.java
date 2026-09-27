package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellasDelPadronDeCatastro;
import org.springframework.stereotype.Service;

/**
 * La anti-entropia: compara las huellas de la proyeccion con las del padron (P6, punto 4).
 *
 * <h2>Una cifra por sector</h2>
 *
 * <p>Comparar lote a lote seria leer los 14 422 predios de Catacaos cada dia por los dos lados; una
 * cifra por sector cuesta decenas y nombra el sector que no cuadra. El segundo escalon —pedir los
 * lotes de ese sector— estaba escrito y no lo llamaba nadie; #611 lo retiro, porque para
 * compararlos hacen falta los lotes de la proyeccion por sector y ningun puerto los publica.
 *
 * <h2>Este caso de uso NO abre transaccion, y es deliberado</h2>
 *
 * <p>La abre {@link LecturaDeLasHuellasDeLaProyeccion}, que es quien lee la base, y es otra clase a
 * proposito: hasta #611 era un metodo de esta, llamado sobre {@code this}, y el proxy no lo veia.
 * Envolver aqui el recorrido entero dejaria dentro de la transaccion la llamada HTTP al otro
 * sistema —una conexion del pool retenida durante toda la peticion de red— y, peor, un fallo del
 * otro lado marcaria como *rollback-only* una transaccion que solo leia. Es el reparto que #54
 * midio para el resumen anual y que #72 volvio a medir para la campana de beneficio.
 */
@Service
public class ConciliarConElPadron {

    private final LecturaDeLasHuellasDeLaProyeccion proyeccion;
    private final HuellasDelPadronDeCatastro padron;

    public ConciliarConElPadron(
            LecturaDeLasHuellasDeLaProyeccion proyeccion, HuellasDelPadronDeCatastro padron) {
        this.proyeccion = proyeccion;
        this.padron = padron;
    }

    /**
     * Compara y devuelve el informe.
     *
     * @param aLaFecha la fecha del informe, que entra como argumento (regla 9)
     */
    public AntiEntropia.Informe conciliar(LocalDate aLaFecha) {
        return AntiEntropia.comparar(padron.porSector(), proyeccion.porSector(), aLaFecha);
    }
}
