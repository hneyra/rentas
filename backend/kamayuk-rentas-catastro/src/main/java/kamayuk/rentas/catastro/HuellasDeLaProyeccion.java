package kamayuk.rentas.catastro;

import java.util.List;

/**
 * Las huellas de la proyeccion local del padron, para compararlas con las del origen.
 *
 * <p>Es la mitad de este lado de la anti-entropia (P6, punto 4). La otra —lo que el padron dice de
 * si mismo— la trae {@link HuellasDelPadronDeCatastro}, que es un puerto y sale por HTTP.
 */
public interface HuellasDeLaProyeccion {

    /** Una cifra por sector, sobre {@code predio_ref}. */
    List<AntiEntropia.HuellaDeSector> porSector();
}
