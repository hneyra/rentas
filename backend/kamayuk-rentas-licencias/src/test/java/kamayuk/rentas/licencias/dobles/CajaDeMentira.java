package kamayuk.rentas.licencias.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;

/**
 * Un {@link RecibosDeTramite} con los recibos que la prueba le siembra.
 *
 * <p>Es el <b>puerto publico de tesoreria</b>, no su tabla, y ese es justamente el punto: si esta
 * prueba pudiera montar un doble del repositorio de recibos en vez de este, seria porque {@code
 * licencias} conoce {@code tesoreria.dominio}, que es lo que el AC de #44 prohibe y lo que Spring
 * Modulith verifica.
 */
public final class CajaDeMentira implements RecibosDeTramite {

    private final List<ReciboDeTramite> recibos = new ArrayList<>();
    private final AtomicInteger siguiente = new AtomicInteger();

    public CajaDeMentira con(ReciboDeTramite recibo) {
        recibos.add(recibo);
        return this;
    }

    /**
     * Como si la caja hubiera cobrado esos conceptos, y devuelve el numero impreso (P5D).
     *
     * <p>Hasta `V7` las pruebas de repositorio de este modulo cobraban <b>de verdad</b>, con {@code
     * CobrarTasa} sobre las tablas de la caja, porque estaban en la misma base. Ya no lo estan, y
     * ese camino se prueba ahora en {@code caja}. Lo que este modulo puede verificar —y lo unico
     * que le tocaba verificar— es que la emision dependa de lo que {@code RecibosDeTramite}
     * conteste: {@code EmitirLicenciaDeFuncionamiento} nunca leyo {@code recibo}, porque el AC de
     * #44 y Spring Modulith no se lo permiten.
     *
     * <p>El identificador se genera aqui y <b>no existe en ninguna tabla</b>: desde `V7` no hay
     * clave foranea que lo valide, y que la licencia lo guarde igual es parte de lo que se mide.
     */
    public String cobro(long contribuyenteId, LocalDate fecha, Dinero total, String... conceptos) {
        int correlativo = siguiente.incrementAndGet();
        String numero = String.format("001-%07d", correlativo);
        recibos.add(
                new ReciboDeTramite(
                        9_000L + correlativo,
                        numero,
                        fecha,
                        contribuyenteId,
                        true,
                        false,
                        List.of(conceptos),
                        total,
                        fecha));
        return numero;
    }

    /**
     * Como si {@code caja} hubiera registrado la anulacion de ese recibo.
     *
     * <p>Un recibo anulado conserva sus filas —no se borran, se reversan (#34)— y lo que cambia es
     * que {@code anulado} pasa a ser cierto. Ese estado lo resuelve {@code caja} leyendo sus
     * movimientos, y quien pregunta solo ve el resultado, que es lo que este doble reproduce.
     */
    public CajaDeMentira anular(String numeroImpreso) {
        for (int i = 0; i < recibos.size(); i++) {
            ReciboDeTramite recibo = recibos.get(i);
            if (recibo.numero().equals(numeroImpreso)) {
                recibos.set(
                        i,
                        new ReciboDeTramite(
                                recibo.reciboId(),
                                recibo.numero(),
                                recibo.fechaDePago(),
                                recibo.contribuyenteId(),
                                recibo.esDeTasas(),
                                true,
                                recibo.conceptos(),
                                recibo.total(),
                                recibo.actualizadoA()));
                return this;
            }
        }
        throw new IllegalArgumentException("No hay ningun recibo " + numeroImpreso + " que anular");
    }

    @Override
    public Optional<ReciboDeTramite> porNumeroImpreso(String numeroImpreso) {
        String buscado = numeroImpreso == null ? "" : numeroImpreso.strip();
        return recibos.stream().filter(recibo -> recibo.numero().equals(buscado)).findFirst();
    }
}
