package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * Como se acumula reajuste e interes sobre un insoluto pendiente, entre dos fechas (RF-042).
 *
 * <p>ADR-0012 de {@code ../srtm} es explicito: «el interes moratorio no genera asientos diarios ...
 * el interes se calcula, no se asienta». Esta interfaz es el punto donde {@link
 * CalculoDeDeuda#deudaActualizadaA} pide ese calculo, sin saber como se hace.
 *
 * <p><b>Aqui no hay ninguna formula.</b> Ni la TIM ni el indice de reajuste tienen todavia una
 * estructura verificada para compilar: el glosario de {@code ../srtm} confirma que el interes
 * moratorio es «de calculo diario», pero no trae la formula ni la imputacion; y RT-016 —reajuste de
 * cuotas— sigue marcado {@code ‹VERIFICAR›} en NEG-05, sin indice ni formula identificados.
 * Escribir cualquiera de las dos aqui seria repetir el defecto que ya paso una vez en este
 * proyecto: una estructura inventada sin poder leer el documento que la resuelve (ver NEG-05 y
 * ARQ-09, CLAUDE.md).
 *
 * <p>Es el mismo mecanismo de {@link kamayuk.rentas.parametros.ReglaTributaria} para #14: una
 * interfaz pura, sin base de datos, sin reloj y sin configuracion global (regla 6, regla 2 —la
 * politica de redondeo se recibe, no se elige, D-03—), lista para que D-02 y D-03 le den una
 * implementacion real sin tocar {@link CalculoDeDeuda}. Hasta entonces, la unica implementacion que
 * existe es {@code SinAcumulacion}, que no acumula nada.
 */
public interface PoliticaDeMora {

    /**
     * El reajuste acumulado sobre {@code insolutoPendiente} entre {@code desde} y {@code hasta}.
     */
    Dinero reajusteAcumulado(
            Dinero insolutoPendiente,
            LocalDate desde,
            LocalDate hasta,
            PoliticaDeRedondeo redondeo);

    /** El interes moratorio acumulado sobre {@code insolutoPendiente} entre las dos fechas. */
    Dinero interesAcumulado(
            Dinero insolutoPendiente,
            LocalDate desde,
            LocalDate hasta,
            PoliticaDeRedondeo redondeo);
}
