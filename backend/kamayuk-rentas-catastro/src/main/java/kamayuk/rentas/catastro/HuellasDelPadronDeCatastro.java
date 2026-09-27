package kamayuk.rentas.catastro;

import java.util.List;

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
}
