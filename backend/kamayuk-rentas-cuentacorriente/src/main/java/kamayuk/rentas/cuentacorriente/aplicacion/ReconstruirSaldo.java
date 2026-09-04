package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Divergencia;
import kamayuk.rentas.cuentacorriente.dominio.ProyeccionDelSaldo;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.dominio.Dinero;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstruye y concilia el saldo proyectado contra el libro (#23).
 *
 * <p>Es la red de seguridad de ADR-0006: «el saldo es cache, no verdad; si discrepa del libro, el
 * libro gana». Aqui viven las dos mitades de esa frase —comprobar que coinciden, y rehacer la
 * proyeccion cuando no—, y estan <b>separadas a proposito</b>: {@link #conciliar} no repara nada.
 *
 * <p>Los dos metodos son de <b>un</b> contribuyente. El recorrido del padron entero vive en {@link
 * ReconstruirPadron}, que es un {@code @Service} distinto, y no es una separacion estetica: llamar
 * a {@link #deContribuyente} desde otro metodo de <i>esta</i> clase pasaria por dentro del proxy de
 * Spring y no abriria transaccion ninguna, con lo que el padron entero caeria en una sola —o en
 * ninguna—. Es el mismo motivo por el que {@code ImportarVias} y {@code RegistrarVia} son dos
 * clases.
 *
 * <p><b>Ninguna auditoria.</b> Reconstruir no modifica ningun dato del contribuyente: recalcula un
 * cache desde una fuente que no se toca. Por eso tampoco pide {@code Observacion}: la regla 10
 * gobierna las modificaciones de datos, y aqui el unico dato que existe —el libro— queda intacto.
 * Si esto exigiera observacion, la exigiria un proceso automatico de madrugada, que no tiene
 * ninguna que dar.
 */
@Service
public class ReconstruirSaldo {

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final Clock reloj;

    public ReconstruirSaldo(AsientoRepository asientos, SaldoRepository saldos, Clock reloj) {
        this.asientos = asientos;
        this.saldos = saldos;
        this.reloj = reloj;
    }

    /**
     * Rehace <b>todos</b> los saldos de un contribuyente desde su libro.
     *
     * @return los saldos que quedaron proyectados
     */
    @Transactional
    public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
        List<SaldoProyectado> proyectados =
                ProyeccionDelSaldo.de(asientos.deContribuyente(contribuyenteId), reloj.instant());
        for (SaldoProyectado saldo : proyectados) {
            saldos.proyectar(saldo);
        }
        return proyectados;
    }

    /**
     * Compara la proyeccion de un contribuyente contra su libro y <b>reporta</b> lo que no cuadra.
     *
     * <p>No repara: ver el javadoc de {@link Divergencia}. Devolver la lista vacia significa que la
     * proyeccion coincide con el libro obligacion por obligacion.
     */
    @Transactional(readOnly = true)
    public List<Divergencia> conciliar(long contribuyenteId) {
        List<Asiento> libro = asientos.deContribuyente(contribuyenteId);
        Map<ClaveDeSaldo, Dinero> segunElLibro = new LinkedHashMap<>();
        for (SaldoProyectado saldo : ProyeccionDelSaldo.de(libro, reloj.instant())) {
            segunElLibro.put(saldo.clave(), saldo.insolutoSaldo());
        }

        Map<ClaveDeSaldo, Dinero> proyectados = new LinkedHashMap<>();
        for (SaldoProyectado saldo : saldos.deContribuyente(contribuyenteId)) {
            proyectados.put(saldo.clave(), saldo.insolutoSaldo());
        }

        List<Divergencia> divergencias = new ArrayList<>();
        segunElLibro.forEach(
                (clave, delLibro) -> {
                    Dinero proyectado = proyectados.get(clave);
                    if (proyectado == null || !proyectado.equals(delLibro)) {
                        divergencias.add(new Divergencia(clave, proyectado, delLibro));
                    }
                });

        // Y al reves: una fila proyectada de una obligacion que el libro no tiene. Pasa
        // si alguien escribio en la proyeccion algo que nunca se asento, y es tan
        // divergencia como la otra —el libro dice cero y la fila dice otra cosa—.
        proyectados.forEach(
                (clave, proyectado) -> {
                    if (!segunElLibro.containsKey(clave)) {
                        divergencias.add(new Divergencia(clave, proyectado, Dinero.CERO));
                    }
                });

        return List.copyOf(divergencias);
    }
}
