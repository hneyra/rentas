package kamayuk.rentas.tesoreria.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que un medio de pago movio en el turno, y lo que el cajero declaro haber contado (V32, #36).
 *
 * <p>Una fila de {@code cierre_turno_detalle}. El arqueo se guarda medio por medio y no en dos
 * cajones —«efectivo» y «otros», que es lo que V3 habia previsto— porque quien concilia con el
 * banco necesita saber cuanto fue tarjeta y cuanto deposito, y un total agregado no puede
 * responderlo dentro de dos anios.
 *
 * <p><b>El neto no se guarda como una cuarta cifra independiente</b>: es {@code cobrado - anulado},
 * y lo comprueba ademas {@code cierre_turno_detalle_neto_ck} en la base. Que se calcule aqui es lo
 * que impide que el desglose y su resumen puedan discrepar, igual que {@code Recibo#total}.
 *
 * @param formaDePago con que se pago
 * @param cobrado lo que los recibos de ese medio sumaron en el turno
 * @param anulado lo que las anulaciones del turno devolvieron de ese medio, congelado
 * @param declarado lo que el cajero conto en el cajon. <b>No sale de ningun otro sitio</b>: es el
 *     unico dato del arqueo que el sistema no puede recomponer
 */
public record LineaDeArqueo(
        FormaDePago formaDePago, Dinero cobrado, Dinero anulado, Dinero declarado) {

    public LineaDeArqueo {
        Objects.requireNonNull(formaDePago, "La linea del arqueo es de un medio de pago");
        Objects.requireNonNull(cobrado, "La linea trae lo cobrado");
        Objects.requireNonNull(anulado, "La linea trae lo anulado");
        Objects.requireNonNull(declarado, "La linea trae lo declarado por el cajero");
        if (cobrado.esNegativo() || anulado.esNegativo() || declarado.esNegativo()) {
            throw new IllegalArgumentException(
                    "Un arqueo no cuenta en negativo: la unica cifra que puede serlo es la"
                            + " diferencia entre lo declarado y el neto");
        }
        if (anulado.esMayorQue(cobrado)) {
            throw new IllegalArgumentException(
                    "En "
                            + formaDePago
                            + " se anulo "
                            + anulado
                            + " de "
                            + cobrado
                            + " cobrados: una anulacion lleva el turno DEL RECIBO (V30), asi que"
                            + " no puede sacar del cajon mas de lo que entro en el");
        }
    }

    /** Una linea sin movimiento y sin declaracion: el medio de pago que no se uso. */
    public static LineaDeArqueo vacia(FormaDePago formaDePago) {
        return new LineaDeArqueo(formaDePago, Dinero.CERO, Dinero.CERO, Dinero.CERO);
    }

    /** La misma linea con lo que el cajero declaro. */
    public LineaDeArqueo declarando(Dinero cuanto) {
        return new LineaDeArqueo(formaDePago, cobrado, anulado, cuanto);
    }

    /** Lo que de verdad quedo en el cajon segun el sistema: {@code cobrado - anulado}. */
    public Dinero neto() {
        return cobrado.menos(anulado);
    }

    /** Lo declarado menos el neto. Negativo si falta dinero; positivo si sobra. */
    public Dinero diferencia() {
        return declarado.menos(neto());
    }

    /** Si esta linea no dice nada: ni se cobro por ese medio, ni se declaro nada. */
    public boolean estaVacia() {
        return cobrado.esCero() && anulado.esCero() && declarado.esCero();
    }
}
