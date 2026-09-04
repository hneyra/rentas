package kamayuk.rentas.tesoreria.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.AbonadoEnElLibro;
import kamayuk.rentas.cuentacorriente.ConciliacionDeCaja;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.dominio.ArqueoDelTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.ReciboDelTurno;
import org.springframework.stereotype.Service;

/**
 * Arma el arqueo de un turno y lo <b>cuadra contra el libro</b> (#36, RF-087).
 *
 * <p>Lo usan los dos lados de la misma pregunta: el {@link CerrarTurno cierre}, que lo congela, y
 * el {@link ConsultaDeRecaudacion avance en vivo}, que lo mira sin escribir nada. Que sea el mismo
 * codigo no es ahorro: es lo que impide que la cifra que el cajero ve antes de cerrar sea distinta
 * de la que el acta acaba diciendo.
 *
 * <h2>Sin transaccion propia, a proposito</h2>
 *
 * <p>No lleva {@code @Transactional}: la abre quien llama. El cierre necesita que el arqueo se lea
 * <b>dentro</b> de la transaccion que ya tiene el turno bloqueado —si el arqueo abriera la suya,
 * entre leer y escribir cabria otra cobranza y el acta congelaria una cifra que ya no es—, y el
 * avance necesita la suya de solo lectura. Ninguna de las dos se puede decidir aqui.
 *
 * <h2>El cuadre</h2>
 *
 * <p>Lo que el turno recaudo <b>en deuda tributaria</b> tiene que ser, centimo a centimo, lo que el
 * libro dice que se abono por esos recibos. Se pregunta por el puerto publico {@link
 * ConciliacionDeCaja} y nunca a las tablas de {@code cuentacorriente} (ARQ-01 §4).
 *
 * <p>Y se distingue lo que no abona: un recibo de caja de tasas y uno de cuota inicial de convenio
 * cobran dinero de verdad y no dejan ni un asiento (ver {@link
 * kamayuk.rentas.tesoreria.dominio.TipoDePago#abonaEnElLibro}). Esos <b>cuadran contra el
 * recibo</b>. Meterlos en el cuadre haria que todo turno que cobrara una tasa saliera descuadrado,
 * y la salida comoda ante eso —relajar la comprobacion— dejaria de detectar el descuadre de verdad.
 */
@Service
public class ArqueoDeTurno {

    private final CierreDeTurnoRepository cierres;
    private final ConciliacionDeCaja libro;

    public ArqueoDeTurno(CierreDeTurnoRepository cierres, ConciliacionDeCaja libro) {
        this.cierres = cierres;
        this.libro = libro;
    }

    /**
     * El arqueo del turno a esa fecha, con lo que el cajero declaro.
     *
     * @param turnoId el turno
     * @param declarado lo contado en el cajon por medio de pago; vacio en el avance en vivo, donde
     *     todavia no hay nada declarado
     * @param aLaFecha la fecha con la que se responde (regla 9); entra, no se lee del reloj
     */
    public ArqueoDelTurno del(
            long turnoId, Map<FormaDePago, Dinero> declarado, LocalDate aLaFecha) {
        Objects.requireNonNull(declarado, "El mapa es vacio, no nulo");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        return ArqueoDelTurno.de(turnoId, cierres.recibosDelTurno(turnoId), declarado, aLaFecha);
    }

    /**
     * Comprueba que lo recaudado en deuda tributaria coincide con lo que el libro asento.
     *
     * @throws ElArqueoNoCuadraConElLibro si no coincide
     */
    public Cuadre cuadrar(long turnoId, LocalDate aLaFecha) {
        List<ReciboDelTurno> recibos = cierres.recibosDelTurno(turnoId);

        Dinero segunLosRecibos = Dinero.CERO;
        List<String> documentos = new ArrayList<>();
        Dinero fueraDelLibro = Dinero.CERO;
        for (ReciboDelTurno recibo : recibos) {
            if (!recibo.abonaEnElLibro()) {
                fueraDelLibro = fueraDelLibro.mas(recibo.neto());
                continue;
            }
            // El NETO del recibo: cero si se anulo. Es lo que se compara contra el libro,
            // que responde por lo que SIGUE abonado y no por lo que abono alguna vez.
            segunLosRecibos = segunLosRecibos.mas(recibo.neto());
            documentos.add(recibo.numero().documentoDeLaCobranza());
        }

        // Solo se pregunta por los documentos de cobranza, y no tambien por los de sus
        // anulaciones. El libro contesta «cuanto sigue abonado este documento», asi que un
        // recibo anulado —cuyos abonos estan reversados— vale cero sin que este arqueo
        // tenga que saber que la reversion de un abono se escribe como cargo, ni con que
        // documento viaja. Ese conocimiento se queda en `cuentacorriente`, que es de donde
        // no debe salir.
        AbonadoEnElLibro abonado = libro.abonadoPor(documentos, aLaFecha);

        Dinero segunElLibro = Dinero.CERO;
        for (String documento : documentos) {
            segunElLibro = segunElLibro.mas(abonado.de(documento));
        }

        if (!segunElLibro.equals(segunLosRecibos)) {
            throw new ElArqueoNoCuadraConElLibro(
                    turnoId, segunLosRecibos, segunElLibro, fueraDelLibro);
        }
        return new Cuadre(segunLosRecibos, fueraDelLibro, aLaFecha);
    }

    /**
     * Lo que el turno recaudo, partido en las dos mitades que se comprueban distinto.
     *
     * @param conAsientos lo que abono en el libro, y que el libro confirma
     * @param sinAsientos lo que se cobro sin tocar el libro —tasas y cuotas iniciales—, que cuadra
     *     contra el recibo
     * @param aLaFecha la fecha a la que se comprobo (regla 9, RNF-075)
     */
    public record Cuadre(Dinero conAsientos, Dinero sinAsientos, LocalDate aLaFecha) {

        public Cuadre {
            Objects.requireNonNull(conAsientos, "El cuadre trae las dos mitades");
            Objects.requireNonNull(sinAsientos, "El cuadre trae las dos mitades");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }

        /** Las dos mitades: tiene que ser el neto del arqueo. */
        public Dinero total() {
            return conAsientos.mas(sinAsientos);
        }
    }

    /**
     * Lo que el turno cobro en deuda tributaria no es lo que el libro asento.
     *
     * <p>Es un {@link IllegalStateException} y no un error de validacion: la peticion esta bien: lo
     * que esta mal es el estado del sistema. Alguien escribio o reverso asientos con el documento
     * de origen de un recibo por otro camino, y firmar el cierre dejaria un acta que no coincide
     * con el estado de cuenta de nadie.
     */
    public static final class ElArqueoNoCuadraConElLibro extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ElArqueoNoCuadraConElLibro(
                long turnoId, Dinero segunLosRecibos, Dinero segunElLibro, Dinero fueraDelLibro) {
            super(
                    "El turno "
                            + turnoId
                            + " recaudo "
                            + segunLosRecibos
                            + " en deuda tributaria y el libro asento "
                            + segunElLibro
                            + ": el cierre no se firma. Los "
                            + fueraDelLibro
                            + " de tasas y cuotas iniciales quedan fuera de esta comparacion a"
                            + " proposito -no tocan el libro-, asi que la diferencia no viene de"
                            + " ahi: viene de asientos escritos con el documento de origen de un"
                            + " recibo por un camino que no es la caja");
        }
    }
}
