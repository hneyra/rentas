package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Optional;

/** Las tarifas del TUPA. */
public interface TasaRepository {

    /**
     * La tarifa de ese concepto <b>vigente a esa fecha</b>, no la ultima.
     *
     * <p>La diferencia importa el dia que una ordenanza sube un derecho: reimprimir un recibo de
     * marzo con la tarifa de setiembre daria un papel que no es el que se entrego. Y cobrar hoy con
     * la del ano pasado es cobrar de menos, sin que nada falle.
     */
    Optional<Tasa> vigenteA(String codigo, LocalDate fecha);
}
