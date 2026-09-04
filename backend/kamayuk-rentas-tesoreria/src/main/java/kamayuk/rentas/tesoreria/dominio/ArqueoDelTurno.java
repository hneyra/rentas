package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * El arqueo de un turno: lo cobrado, lo anulado y el neto, medio de pago por medio de pago (#36,
 * RF-087).
 *
 * <h2>Funcion pura</h2>
 *
 * <p>Regla 6: se construye desde los recibos del turno y lo que el cajero declaro, y nada mas. Sin
 * base de datos, sin reloj y sin configuracion global —la fecha entra como argumento—, de modo que
 * el arqueo de un turno de 2026 vuelve a dar el mismo centimo en 2036.
 *
 * <h2>Ni un redondeo, y no es un olvido</h2>
 *
 * <p>Aqui no hay ningun {@link kamayuk.rentas.dominio.PuntoDeRedondeo} porque <b>no hay ninguna
 * division</b>. La distribucion de la recaudacion es un <b>reparto de filas</b>: cada recibo tiene
 * un unico medio de pago y su total va entero a un cajon. Sumar importes de {@code numeric(15,2)}
 * no crea decimales nuevos, asi que la suma de las partes es el total exacto y no hay centimo
 * huerfano que asignar.
 *
 * <p>Es lo contrario del cronograma de un convenio, que si divide —repartir 100,00 en tres deja un
 * centimo suelto— y por eso recibe su politica del conjunto sellado. Redondear aqui, ademas de
 * innecesario, seria D-03 tomada por descuido: convertiria un arqueo exacto en uno plausible.
 *
 * <h2>Lo que el prototipo pide y no existe</h2>
 *
 * <p>La pantalla dibuja cuatro casillas —efectivo, tarjeta, deposito en cuenta y pago en linea— y
 * un selector de turno (MAÑANA / TARDE / CONTINUO). El arqueo se declara por {@link FormaDePago},
 * que son <b>cinco</b> y son las que el recibo guarda: las cuatro casillas son un subconjunto, y el
 * cheque no tiene casilla. Traducir «pago en linea» a {@code TRANSFERENCIA} y dejar el cheque sin
 * declarar produciria un descuadre cada vez que alguien pagara con cheque, asi que se declara por
 * lo que el recibo dice.
 *
 * <p>El <b>turno de la manana o de la tarde no existe como dato</b>: {@code cierre_uq} (V3) hace
 * unico el turno por (caja, cajero, fecha) y no hay columna que lo parta en dos. No se inventa; se
 * anota aqui y en el recurso HTTP.
 *
 * @param turnoId el turno arqueado
 * @param lineas una por medio de pago con movimiento o con declaracion, en el orden del enumerado
 * @param recibosEmitidos cuantos recibos emitio el turno
 * @param recibosAnulados cuantos de ellos se anularon
 * @param aLaFecha la fecha a la que se leyeron estas cifras (regla 9, RNF-075)
 */
public record ArqueoDelTurno(
        long turnoId,
        List<LineaDeArqueo> lineas,
        int recibosEmitidos,
        int recibosAnulados,
        LocalDate aLaFecha) {

    public ArqueoDelTurno {
        if (turnoId <= 0) {
            throw new IllegalArgumentException("El arqueo es de un turno concreto");
        }
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        lineas = List.copyOf(lineas);
        if (recibosEmitidos < 0 || recibosAnulados < 0) {
            throw new IllegalArgumentException("No se emiten ni se anulan recibos en negativo");
        }
        if (recibosAnulados > recibosEmitidos) {
            throw new IllegalArgumentException(
                    "Se anularon "
                            + recibosAnulados
                            + " recibos de "
                            + recibosEmitidos
                            + " emitidos: una anulacion lleva el turno del recibo que anula");
        }
    }

    /**
     * El arqueo de esos recibos, con lo que el cajero declaro.
     *
     * @param turnoId el turno
     * @param recibos los recibos del turno, con lo que su anulacion devolvio si la hubo
     * @param declarado lo contado en el cajon por medio de pago; los que falten van en cero
     * @param aLaFecha la fecha a la que se lee (regla 6: entra, no se lee del reloj)
     */
    public static ArqueoDelTurno de(
            long turnoId,
            List<ReciboDelTurno> recibos,
            Map<FormaDePago, Dinero> declarado,
            LocalDate aLaFecha) {

        Objects.requireNonNull(recibos, "La lista es vacia, no nula");
        Objects.requireNonNull(declarado, "El mapa es vacio, no nulo");

        Map<FormaDePago, Dinero> cobrado = new EnumMap<>(FormaDePago.class);
        Map<FormaDePago, Dinero> anulado = new EnumMap<>(FormaDePago.class);
        int anulados = 0;
        for (ReciboDelTurno recibo : recibos) {
            cobrado.merge(recibo.formaDePago(), recibo.total(), Dinero::mas);
            anulado.merge(recibo.formaDePago(), recibo.anulado(), Dinero::mas);
            if (recibo.estaAnulado()) {
                anulados++;
            }
        }

        List<LineaDeArqueo> lineas = new ArrayList<>();
        // En el orden del enumerado y no en el de llegada: dos arqueos del mismo turno
        // tienen que dibujarse igual, y el orden de los recibos no es estable.
        for (FormaDePago forma : FormaDePago.values()) {
            LineaDeArqueo linea =
                    new LineaDeArqueo(
                            forma,
                            cobrado.getOrDefault(forma, Dinero.CERO),
                            anulado.getOrDefault(forma, Dinero.CERO),
                            declarado.getOrDefault(forma, Dinero.CERO));
            // Un medio que no se uso y que nadie declaro no aporta una fila de ceros al
            // acta: seria ruido en un documento que alguien tiene que leer.
            if (!linea.estaVacia()) {
                lineas.add(linea);
            }
        }
        return new ArqueoDelTurno(turnoId, lineas, recibos.size(), anulados, aLaFecha);
    }

    /** Lo cobrado en el turno: la suma de las lineas, nunca una cifra aparte. */
    public Dinero totalCobrado() {
        return sumar(LineaDeArqueo::cobrado);
    }

    /** Lo que las anulaciones del turno sacaron del cajon. */
    public Dinero totalAnulado() {
        return sumar(LineaDeArqueo::anulado);
    }

    /** El neto del turno: lo cobrado menos lo anulado. */
    public Dinero neto() {
        return totalCobrado().menos(totalAnulado());
    }

    /** Lo que el cajero declaro haber contado, en total. */
    public Dinero totalDeclarado() {
        return sumar(LineaDeArqueo::declarado);
    }

    /**
     * Lo declarado menos el neto. Negativo si falta dinero en el cajon; positivo si sobra.
     *
     * <p><b>No se rechaza</b>: un arqueo descuadrado es precisamente lo que hay que dejar por
     * escrito. Si el cierre exigiera cero, el cajero al que le falten diez soles declararia lo que
     * el sistema diga y el descuadre desapareceria del acta.
     */
    public Dinero diferencia() {
        return totalDeclarado().menos(neto());
    }

    /** Si lo declarado coincide con el neto del sistema. */
    public boolean cuadra() {
        return diferencia().esCero();
    }

    private Dinero sumar(java.util.function.Function<LineaDeArqueo, Dinero> parte) {
        Dinero total = Dinero.CERO;
        for (LineaDeArqueo linea : lineas) {
            total = total.mas(parte.apply(linea));
        }
        return total;
    }
}
