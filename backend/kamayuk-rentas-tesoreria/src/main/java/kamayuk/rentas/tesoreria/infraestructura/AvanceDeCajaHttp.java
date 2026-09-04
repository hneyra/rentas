package kamayuk.rentas.tesoreria.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import org.springframework.stereotype.Component;

/**
 * Lo que la ventanilla lleva cobrado hoy, pedido a {@code caja} (P5D).
 *
 * <p>Sustituye a {@code AvanceDeCajaTesoreria}, que agregaba {@code recibo_detalle} por turno. Esas
 * tablas se fueron con `V7`. <b>El puerto no cambio</b>: {@code PanelDeRecaudacion} de {@code
 * indicadores} —#56, la pantalla de inicio— no cambio ni una linea.
 *
 * <p>Ruta: {@code GET {raiz}/recaudacion/avance?dia=&aLaFecha=}. Los dos van en la URL y ninguno se
 * deriva del reloj de este proceso: el dia es <b>el del turno</b> y no el del reloj —si el rango se
 * aplicara sobre el instante del recibo, la frontera de la medianoche dependeria de la zona horaria
 * con que se consultara— y {@code aLaFecha} viaja con la cifra (regla 9, RNF-075). Que los dos
 * crucen la frontera es lo que hace que la respuesta siga siendo defendible.
 *
 * <h2>Aqui NO hay 404 que valga</h2>
 *
 * <p>«Hoy no ha entrado nada todavia» es una respuesta legitima —la que un panel da a las ocho de
 * la manana— y {@code caja} la da con ceros. Un 404, o una conexion que no contesta, es otra cosa,
 * y sale como {@link ClienteHttpDeCaja.CajaInalcanzable}: publicar ceros ahi pondria el panel de
 * recaudacion en «0,00 cobrado hoy» con la ventanilla cobrando, que es una cifra plausible y falsa
 * al lado de las del libro, que si estarian bien (#48, #56).
 */
@Component
public class AvanceDeCajaHttp implements AvanceDeCaja {

    private final ClienteHttpDeCaja caja;

    public AvanceDeCajaHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
        JsonNode cuerpo =
                caja.pedir(
                        "/recaudacion/avance?dia=" + dia + "&aLaFecha=" + aLaFecha,
                        "leer el avance de caja del " + dia);
        return new RecaudadoEnCaja(
                Dinero.de(cuerpo.path("cobrado").asText("0")),
                Dinero.de(cuerpo.path("anulado").asText("0")),
                LocalDate.parse(cuerpo.path("dia").asText()),
                LocalDate.parse(cuerpo.path("aLaFecha").asText()));
    }
}
