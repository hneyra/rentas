package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * La obligacion ya esta formalizada por un valor <b>vivo del mismo tipo</b> (#366).
 *
 * <p>Es una <i>Specification</i>: una regla con nombre que se evalua sobre una obligacion y se
 * cumple o no, respaldada por {@link ValorRepository#vivosSobre}. Si se cumple, emitir otro valor
 * de ese tipo sobre esa obligacion es un segundo acto exigible por la misma deuda —con su numero,
 * su plazo, su notificacion y su pase a coactiva— y {@code RegistrarValor} lo rechaza nombrando el
 * valor que ya la formaliza.
 *
 * <h2>Por que hacia falta</h2>
 *
 * <p>{@code RegistrarValor} decide que obligaciones hay leyendo la deuda de {@code
 * cuentacorriente}, y esa lectura no dice la fase: formalizar mueve la deuda de ORDINARIA a VALOR
 * con un par AJUSTE que no cambia el total, asi que despues de emitir la obligacion <b>sigue
 * enseñando la misma deuda</b>. Un doble envio —un reintento tras un timeout, dos operadores—
 * encontraba lo mismo y emitia OP-000011 por la deuda que OP-000010 ya estaba cobrando.
 *
 * <h2>Del mismo tipo, y no de cualquiera</h2>
 *
 * <p>Rechazar toda obligacion con un valor vivo seria mas simple y estaria mal: una RD posterior a
 * una OP sobre el mismo predial es un acto legitimo —la determinacion que sigue a la orden de
 * pago—, y {@code coactiva} ya la da por buena al deduplicar sus expedientes. Lo que si cambia
 * cuando hay un valor vivo de <b>cualquier</b> tipo es la fase: la deuda ya esta en VALOR, y volver
 * a moverla dejaria escrito en el libro que salio de ORDINARIA dos veces. Eso lo contesta {@link
 * Formalizacion#yaEstaEnFaseValor}.
 *
 * <h2>Solo vale despues del candado</h2>
 *
 * <p>Evaluada a secas, la regla la pasan las dos peticiones simultaneas: ninguna ve el valor de la
 * otra hasta que confirma. Quien la evalua toma antes {@link
 * ValorRepository#bloquearLasObligaciones} sobre las mismas obligaciones, en la misma transaccion.
 */
public final class ObligacionYaFormalizada {

    private final ValorRepository valores;

    public ObligacionYaFormalizada(ValorRepository valores) {
        this.valores = Objects.requireNonNull(valores, "La regla se respalda en los valores");
    }

    /**
     * Lo que la regla sabe de esa obligacion: los valores vivos que ya la formalizan.
     *
     * @param contribuyenteId de quien es la obligacion
     * @param obligacion la obligacion, con su clave del libro
     */
    public Formalizacion de(long contribuyenteId, SelectorDeObligacion obligacion) {
        return new Formalizacion(obligacion, valores.vivosSobre(contribuyenteId, obligacion));
    }

    /**
     * La regla evaluada sobre una obligacion.
     *
     * @param obligacion la obligacion evaluada
     * @param vivos los valores vivos que la formalizan, del primero que se emitio al ultimo
     */
    public record Formalizacion(SelectorDeObligacion obligacion, List<Valor> vivos) {

        public Formalizacion {
            Objects.requireNonNull(obligacion, "La regla se evalua sobre una obligacion");
            vivos = List.copyOf(vivos);
        }

        /**
         * El valor vivo de ese tipo que ya la formaliza, si lo hay. Si lo hay, la regla se cumple.
         */
        public Optional<Valor> porUnValorDe(TipoValor tipo) {
            return vivos.stream().filter(valor -> valor.tipo() == tipo).findFirst();
        }

        /** Si la regla se cumple para ese tipo: emitir otro seria un segundo titulo. */
        public boolean seCumplePara(TipoValor tipo) {
            return porUnValorDe(tipo).isPresent();
        }

        /**
         * Si la deuda ya salio de ORDINARIA: basta un valor vivo, de cualquier tipo, porque el
         * primero que la formalizo la movio.
         */
        public boolean yaEstaEnFaseValor() {
            return !vivos.isEmpty();
        }
    }
}
