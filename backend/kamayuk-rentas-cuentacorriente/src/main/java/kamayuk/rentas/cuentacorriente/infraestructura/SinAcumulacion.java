package kamayuk.rentas.cuentacorriente.infraestructura;

import java.time.LocalDate;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.springframework.stereotype.Component;

/**
 * La unica {@link PoliticaDeMora} que existe hoy: no acumula nada.
 *
 * <p>Es un lugar reservado, no una regla de calculo. Mientras D-02 no fije la TIM y el indice de
 * reajuste, y D-03 no fije donde se redondea, no hay ninguna cifra que esta clase pudiera devolver
 * sin inventarla —y regla 5 lo prohibe—. {@link
 * kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda#deudaActualizadaA} sigue funcionando con
 * esto: el insoluto, el reajuste y el interes que ya estan asentados en el libro se siguen leyendo
 * y sumando igual, y lo unico que falta es el tramo de reajuste e interes que todavia no se asento.
 *
 * <p>Cuando D-02 y D-03 cierren, esta clase se sustituye por la implementacion real —o deja de ser
 * el unico bean de {@link PoliticaDeMora}—, y ningun llamador de {@code deudaActualizadaA} cambia:
 * es exactamente para eso que la politica se recibe como argumento (regla 6, ARQ-09).
 *
 * <p><b>Lo que haria cierta esa promesa no es esta clase, sino que todo camino que escribe en el
 * libro cristalice antes el devengo</b>, y <b>todavia no lo es</b>. Hasta #365 cuatro de los seis
 * caminos que ese issue nombra —la extincion, la baja manual, el pase a valor y la reversion—
 * escribian sin cargarlo, y con una mora que devengue habrian dejado interes negativo y
 * condonaciones sin acto. Esos seis llaman hoy a {@link
 * kamayuk.rentas.cuentacorriente.dominio.CristalizacionDelDevengo}, y {@code
 * ElDevengoSeCristalizaAntesDeEscribirJdbcTest} los recorre con una mora que si devenga.
 *
 * <p><b>Pero quedan dos escritores que no cristalizan</b>, y sustituir esta clase sin resolverlos
 * antes produce importes equivocados en silencio:
 *
 * <ul>
 *   <li>{@code MovimientoDeFaseCuentaCorriente.moverACoactiva}: su par {@code AJUSTE} adelanta el
 *       ancla sin cargar lo devengado despues de la OP, y ese interes se pierde en cada paso a
 *       coactiva. No se cristaliza porque alli el monto lo decide el libro —el neto en VALOR— y
 *       cristalizar antes cambia cuanto entra en coactiva: es una decision de cobranza que #365 no
 *       toma;
 *   <li>{@code GeneradorDeCargosCuentaCorriente} —el cargo de insoluto que piden otros contextos y
 *       la costa del procedimiento—: sobre una obligacion que ya devenga, su asiento tambien
 *       adelanta el ancla.
 * </ul>
 *
 * <p>El encendido de la mora (D-02) exige, por tanto, resolver esos dos antes —o empujar la
 * cristalizacion a {@code RegistrarAsiento.asentar}, el embudo comun, que es el paso que #365 deja
 * para despues—. Un escritor nuevo tiene que llamarla tambien; mientras esta clase devuelva cero,
 * olvidarlo no se ve en ninguna cifra.
 */
@Component
public class SinAcumulacion implements PoliticaDeMora {

    @Override
    public Dinero reajusteAcumulado(
            Dinero insolutoPendiente,
            LocalDate desde,
            LocalDate hasta,
            PoliticaDeRedondeo redondeo) {
        return Dinero.CERO;
    }

    @Override
    public Dinero interesAcumulado(
            Dinero insolutoPendiente,
            LocalDate desde,
            LocalDate hasta,
            PoliticaDeRedondeo redondeo) {
        return Dinero.CERO;
    }
}
