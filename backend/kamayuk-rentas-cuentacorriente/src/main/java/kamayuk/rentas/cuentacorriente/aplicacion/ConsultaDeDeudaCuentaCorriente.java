package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeLoOriginado;
import kamayuk.rentas.cuentacorriente.ObligacionOriginada;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ConsultaDeDeudaPublica} (#25) y {@link ConsultaDeLoOriginado} (#342) sobre
 * {@link ConsultarDeuda}.
 *
 * <p>Filtrar aqui, o en {@code ConsultarDeuda#todasLasObligacionesDe}, dejaria sin sus filas en
 * 0,00 a la constancia de no adeudo, que las imprime como «Cancelado»: {@link #todasDe} las
 * devuelve todas, y {@link #pendientesDe} las filtra con la regla del puerto (#401).
 */
@Service
public class ConsultaDeDeudaCuentaCorriente
        implements ConsultaDeDeudaPublica, ConsultaDeLoOriginado {

    private final ConsultarDeuda consulta;

    public ConsultaDeDeudaCuentaCorriente(ConsultarDeuda consulta) {
        this.consulta = consulta;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObligacionPublica> todasDe(long contribuyenteId, LocalDate fecha) {
        return consulta.todasLasObligacionesDe(contribuyenteId, fecha).stream()
                .map(ConsultaDeDeudaCuentaCorriente::aPublica)
                .toList();
    }

    /**
     * La regla es la del puerto; lo unico que se anade aqui es la transaccion (#401).
     *
     * <p>Sin esta redefinicion, {@code pendientesDe} llegaba al proxy como el metodo por omision de
     * la interfaz, sin {@code @Transactional}, y su llamada interna a {@link #todasDe} no pasaba
     * por el proxy: sin transaccion no hay {@code SET LOCAL} y RLS contesta «unrecognized
     * configuration parameter "app.municipalidad_id"». Medido con {@code
     * ConsultaDeDeudaCuentaCorrienteTest}; y {@code EmitirOrdenDeCobro}, que no abre transaccion a
     * proposito, habria fallado asi en cada emision.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ObligacionPublica> pendientesDe(long contribuyenteId, LocalDate fecha) {
        return ConsultaDeDeudaPublica.super.pendientesDe(contribuyenteId, fecha);
    }

    /**
     * La deuda que originaron esos documentos (#342). Mismo mapeo que {@link #todasDe}: la fila
     * publica de cada clave de saldo es la de una obligacion, mas su periodo y sus documentos.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ObligacionOriginada> deLoOriginadoPor(
            long contribuyenteId, Set<String> documentosDeOrigen, LocalDate fecha) {
        return consulta.deLoOriginadoPor(contribuyenteId, documentosDeOrigen, fecha).stream()
                .map(
                        originada ->
                                new ObligacionOriginada(
                                        aPublica(originada.obligacion()),
                                        originada.obligacion().periodoDesde(),
                                        originada.documentos()))
                .toList();
    }

    private static ObligacionPublica aPublica(ObligacionConDeuda obligacion) {
        DeudaActualizada deuda = obligacion.deuda();
        return new ObligacionPublica(
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId(),
                deuda.fecha(),
                deuda.insoluto(),
                deuda.reajuste(),
                deuda.interes(),
                deuda.gasto(),
                // La fase ya estaba calculada y se descartaba aqui (#403): sin ella, coactiva
                // sumaba la deuda acogida a un convenio como si fuera exigible.
                obligacion.fase().name());
    }
}
