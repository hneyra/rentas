package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.tesoreria.dominio.RecaudacionRepository;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;

/**
 * La recaudacion agregada, en memoria.
 *
 * <p>No agrega nada: devuelve las filas que se le declaran. Lo que se prueba con ella es el
 * <b>transporte</b> —que la respuesta lleve su fecha, que el area nula salga nula, que un turno que
 * no existe sea 404—; que la agregacion sume bien lo prueba {@code CierreDeCajaJdbcTest} contra
 * PostgreSQL, porque es una consulta SQL y contra un doble se probaria el doble.
 */
public final class RecaudacionEnMemoria implements RecaudacionRepository {

    private final List<RecaudacionDeTributo> porTributo = new ArrayList<>();
    private final List<RecaudacionDePartida> porPartida = new ArrayList<>();
    private final List<TurnoDeCaja> turnos = new ArrayList<>();

    public RecaudacionEnMemoria con(RecaudacionDeTributo fila) {
        porTributo.add(fila);
        return this;
    }

    public RecaudacionEnMemoria con(RecaudacionDePartida fila) {
        porPartida.add(fila);
        return this;
    }

    public RecaudacionEnMemoria conTurno(TurnoDeCaja turno) {
        turnos.add(turno);
        return this;
    }

    @Override
    public List<RecaudacionDeTributo> porTributo(CriterioDeRecaudacion criterio) {
        return List.copyOf(porTributo);
    }

    @Override
    public List<RecaudacionDePartida> porPartida(CriterioDeRecaudacion criterio) {
        return List.copyOf(porPartida);
    }

    @Override
    public Optional<TurnoDeCaja> turnoDe(String codigoDeCaja, String cajero, LocalDate fecha) {
        return turnos.stream()
                .filter(turno -> turno.cajero().equals(cajero) && turno.fecha().equals(fecha))
                .findFirst();
    }
}
