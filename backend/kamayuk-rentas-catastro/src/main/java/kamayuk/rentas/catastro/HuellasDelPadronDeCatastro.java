package kamayuk.rentas.catastro;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lo que el padron de {@code catastro} dice de si mismo. El puerto de la anti-entropia.
 *
 * <p>Lo define quien lo consume, como los otros dieciocho puertos de este modulo: la firma esta en
 * el vocabulario de {@code rentas} y su implementacion sale por HTTP. Que este declarado aqui es lo
 * que permite que la comparacion se pruebe sin levantar el otro sistema — y lo que permitiria
 * cambiar el transporte sin tocar la comparacion.
 */
public interface HuellasDelPadronDeCatastro {

    /** Una cifra por sector. Es lo que se compara a diario. */
    List<AntiEntropia.HuellaDeSector> porSector();

    /**
     * El detalle de UN sector: sus lotes con su huella.
     *
     * <p>Solo se pide del sector que no cuadro. Pedirlo siempre seria leer el catastro entero cada
     * dia, que es exactamente lo que la escalera de huellas existe para no hacer.
     */
    List<HuellaDeLote> deUnSector(@Nullable String sectorCodigo);

    /** La huella de un lote del origen. */
    record HuellaDeLote(long predioId, String huella) {}
}
