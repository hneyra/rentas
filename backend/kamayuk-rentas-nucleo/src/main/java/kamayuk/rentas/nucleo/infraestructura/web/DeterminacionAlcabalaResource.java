package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;

/**
 * Una determinación de alcabala tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04
 * §3).
 *
 * <p>{@code baseImponible} y {@code montoDeterminado} viajan como texto, no como {@link
 * kamayuk.rentas.dominio.Dinero}: son la cifra fija con que se determinó el acto, no un saldo que
 * cambie con el tiempo, así que no necesitan {@code ImporteActualizado} (regla 9, mismo motivo que
 * {@code ArbitrioResource}).
 *
 * <h2>{@code fechaCalculo}: la mitad de la regla 9 que faltaba (#276, salida (b) de #261)</h2>
 *
 * <p>Que una cifra no se actualice con el tiempo no la exime de decir <b>a qué fecha está</b>: la
 * regla 9 —RNF-075— pide las dos cosas, y hasta #276 esta operación publicaba seis campos y ninguno
 * era una fecha. Eso no era una omisión sin consecuencia: es lo único que separaba a la hoja de la
 * alcabala de poder conectarse, porque dibujar {@code montoDeterminado} sin decir a qué día
 * corresponde es exactamente lo que la regla prohíbe. La guarda del frontend —{@code
 * verificaciones/un-importe-sin-su-fecha-esta-declarado.test.ts}— lo llevaba escrito con el
 * veredicto «NO SE DIBUJA» desde #261, y caduca sola el día que esta forma publica la fecha.
 *
 * <p>Es un {@code String} con la fecha ISO, y no un {@code LocalDate}, porque así la publican las
 * otras tres determinaciones —{@code DeterminacionPredialResource} y {@code
 * CalculoVehicularResource}—: un campo que ya existe en tres sitios no se rediseña en el cuarto. Y
 * la manda el controlador desde el reloj inyectado, que es de donde la saca {@code
 * VehicularController}: no del reloj del sistema, para que una prueba pueda fijarla.
 */
public record DeterminacionAlcabalaResource(
        long id,
        String ejercicio,
        long predioId,
        long contribuyenteId,
        String fechaCalculo,
        String baseImponible,
        String montoDeterminado) {

    public DeterminacionAlcabalaResource {
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9, RNF-075)");
    }

    /**
     * La determinación recién hecha, con el día en que se hizo.
     *
     * @param fechaCalculo el día al que corresponden las dos cifras de aquí (regla 9)
     * @param determinacion lo que el servicio determinó y asentó
     */
    public static DeterminacionAlcabalaResource de(
            LocalDate fechaCalculo, Determinacion determinacion) {
        return new DeterminacionAlcabalaResource(
                determinacion.id() == null ? 0L : determinacion.id(),
                determinacion.ejercicio().toString(),
                determinacion.predioId() == null ? 0L : determinacion.predioId(),
                determinacion.contribuyenteId(),
                fechaCalculo.toString(),
                determinacion.baseImponible().valor().toPlainString(),
                determinacion.montoDeterminado().valor().toPlainString());
    }
}
