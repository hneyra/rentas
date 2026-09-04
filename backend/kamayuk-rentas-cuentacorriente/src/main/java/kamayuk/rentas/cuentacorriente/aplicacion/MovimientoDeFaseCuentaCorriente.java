package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link MovimientoDeFase} como un par de asientos: un abono en fase ordinaria y un
 * cargo por el mismo importe en fase {@link Fase#VALOR}, con {@link Concepto#AJUSTE} —el mismo
 * concepto que ya usa cualquier movimiento administrativo que no altera el total adeudado, y que ya
 * exige {@code motivo} por {@code asiento_motivo_ck} (RNF-052)—.
 *
 * <p>Las dos escrituras van en la misma transaccion: si la segunda fallara, la primera se revierte
 * con ella. Un abono sin su cargo dejaria una obligacion con menos deuda de la que en realidad
 * tiene, y eso es peor que no mover nada.
 */
@Service
public class MovimientoDeFaseCuentaCorriente implements MovimientoDeFase {

    private final RegistrarAsiento registrar;

    public MovimientoDeFaseCuentaCorriente(RegistrarAsiento registrar) {
        this.registrar = registrar;
    }

    @Override
    @Transactional
    public void moverAValor(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {

        // nuevoConMotivo y no nuevo: AJUSTE exige motivo y el constructor de Asiento lo
        // comprueba, asi que sin el la fila NI SIQUIERA SE PUEDE CONSTRUIR -y este metodo
        // fallaba con IllegalArgumentException cada vez que la obligacion tenia deuda-.
        // Lo definitivo lo pone RegistrarAsiento#asentar con la observacion del usuario.
        Asiento abonoOrdinario =
                Asiento.nuevoConMotivo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        Concepto.AJUSTE,
                        TipoAsiento.ABONO,
                        Fase.ORDINARIA,
                        periodo,
                        predioId,
                        vehiculoId,
                        referenciaExterna,
                        monto,
                        fechaValor,
                        documentoOrigen,
                        observacion.texto());
        registrar.asentar(abonoOrdinario, observacion);

        Asiento cargoEnValor =
                Asiento.nuevoConMotivo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        Concepto.AJUSTE,
                        TipoAsiento.CARGO,
                        Fase.VALOR,
                        periodo,
                        predioId,
                        vehiculoId,
                        referenciaExterna,
                        monto,
                        fechaValor,
                        documentoOrigen,
                        observacion.texto());
        registrar.asentar(cargoEnValor, observacion);
    }
}
