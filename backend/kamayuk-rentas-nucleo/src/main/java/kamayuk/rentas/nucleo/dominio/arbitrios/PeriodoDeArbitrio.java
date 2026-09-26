package kamayuk.rentas.nucleo.dominio.arbitrios;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El mes de un arbitrio y el dia al que se le atribuye (#443).
 *
 * <p>El arbitrio se cobra mes a mes, y quien lo paga, sobre que rasgos del predio y con que
 * beneficio se decide <b>a la fecha de cada mes</b>, no a la de la corrida: un predio independizado
 * en julio no debe de enero a junio, y una inafectacion que acaba el 31 de diciembre no exonera el
 * ejercicio siguiente aunque la emision se corra el 20 de diciembre.
 *
 * <p>Que dia exacto del mes atribuye el arbitrio es de ordenanza (D-02b), y por eso esta en
 * <b>una</b> funcion: hoy es el primero del mes —el predio y el titular que existen cuando el mes
 * empieza—, y si la ordenanza dice otro, cambia aqui y en ningun otro sitio.
 */
public final class PeriodoDeArbitrio {

    /** Enero: el arbitrio es mensual y el ejercicio es el año calendario. */
    public static final int PRIMERO = 1;

    /** Diciembre. */
    public static final int ULTIMO = 12;

    private PeriodoDeArbitrio() {}

    /** El dia al que se atribuye el arbitrio del mes {@code periodo} de {@code ejercicio}. */
    public static LocalDate fechaDeAtribucion(Ejercicio ejercicio, int periodo) {
        Objects.requireNonNull(ejercicio, "El periodo es de un ejercicio");
        if (periodo < PRIMERO || periodo > ULTIMO) {
            throw new IllegalArgumentException(
                    "El periodo de un arbitrio es un mes, de 1 a 12: " + periodo);
        }
        return LocalDate.of(ejercicio.valor(), periodo, 1);
    }
}
