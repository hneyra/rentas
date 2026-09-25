package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Cuanto se debe en un expediente coactivo <b>a una fecha</b> (#40, regla 9, RNF-075).
 *
 * <p><b>No existe «la deuda del expediente»:</b> existe la deuda actualizada a un dia. El interes
 * moratorio corre, asi que la misma carpeta debe otra cifra pasado mañana sin que ninguna fila haya
 * cambiado. Por eso {@link #actualizadaA} no es opcional y viaja pegada a las cinco cifras.
 *
 * <p><b>Y no sale de los importes congelados de los valores.</b> Un valor guarda su desglose tal
 * como estaba el dia de la emision, y reimprimirlo dos anios despues devuelve ese mismo desglose
 * (AC de #37). Sumar eso y llamarlo «la deuda del expediente» daria la cifra de un dia pasado con
 * la etiqueta de hoy. Lo que se suma es lo que {@code cuentacorriente} dice, a la fecha pedida, de
 * las obligaciones que los valores del expediente formalizan.
 *
 * <h2>Las costas</h2>
 *
 * <p>{@link #costas} nacio en cero con #40 —«el enganche, y ningun importe inventado»— y desde #42
 * <b>trae cifra</b>. La cifra no es un campo del expediente: es lo que el libro dice, a la misma
 * fecha que las otras cuatro, de las obligaciones de costas que las liquidaciones del expediente
 * abrieron (concepto {@code GASTO}, fase {@code COACTIVA}). Se lee por el mismo camino y con la
 * misma pregunta, y por eso no puede discrepar de lo que se cobra en ventanilla.
 *
 * <p>Lo que sigue sin haber aqui es un importe inventado: cuanto vale la costa de cada acto sale
 * del arancel sellado (D-02c, #193), y sin el parametro la liquidacion falla nombrando la llave en
 * vez de escribir un numero.
 *
 * <h2>Lo acogido a un convenio, aparte (#403)</h2>
 *
 * <p>{@link #enConvenio} es la deuda de las obligaciones del expediente que el libro tiene en fase
 * {@code CONVENIO}. <b>No es exigible</b> por coactiva mientras el convenio viva —la cobra el
 * cronograma—, asi que no suma a ninguna de las cuatro partes ni a {@link #total()}, que es la
 * cifra con la que el expediente decide si hay que ejecutar y la que la REC-2 imprime como «total
 * exigible». Tampoco se esconde: viaja como cifra propia para que la ficha pueda decir que hay
 * deuda fraccionada sin llamarla exigible. Hasta #403 se sumaba a las cuatro partes, y la guarda de
 * los actos veia deuda viva donde solo habia cuotas de un convenio.
 *
 * @param insoluto el tributo debido, sin reajuste ni interes
 * @param reajuste el ajuste de cuotas por el indice vigente
 * @param interes el interes moratorio
 * @param gasto los gastos administrativos y de cobranza asentados
 * @param costas las costas y gastos del procedimiento coactivo, releidas del libro a la misma fecha
 *     (#42); cero mientras el expediente no tenga ninguna liquidada
 * @param enConvenio lo que el libro tiene acogido a un convenio de las obligaciones del expediente,
 *     a la misma fecha; <b>fuera</b> de lo exigible (#403)
 * @param actualizadaA el dia al que corresponden las cinco cifras (regla 9, RNF-075)
 */
public record DeudaDelExpediente(
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto,
        Dinero costas,
        Dinero enConvenio,
        LocalDate actualizadaA) {

    public DeudaDelExpediente {
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(costas, "Las costas viajan, aunque sean cero (#42)");
        Objects.requireNonNull(enConvenio, "Lo acogido viaja, aunque sea cero (#403)");
        Objects.requireNonNull(
                actualizadaA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }

    /**
     * Un expediente sin nada que cobrar a esa fecha.
     *
     * <p>Cero con <b>dos decimales</b>, no {@code Dinero.CERO}: los importes salen por HTTP como
     * texto con {@code toPlainString()}, y {@code BigDecimal.ZERO} se imprime «0» mientras que
     * cualquier otra cifra sale «535.50». Una columna de dinero que a veces trae dos decimales y a
     * veces ninguno es la clase de detalle que acaba en un formateador distinto por pantalla. La
     * suma conserva la escala mayor, asi que basta con arrancar aqui.
     */
    public static DeudaDelExpediente ninguna(LocalDate actualizadaA) {
        Dinero cero = Dinero.de("0.00");
        return new DeudaDelExpediente(cero, cero, cero, cero, cero, cero, actualizadaA);
    }

    /** Suma una obligacion mas, a la misma fecha. */
    public DeudaDelExpediente mas(Dinero insoluto, Dinero reajuste, Dinero interes, Dinero gasto) {
        return new DeudaDelExpediente(
                this.insoluto.mas(insoluto),
                this.reajuste.mas(reajuste),
                this.interes.mas(interes),
                this.gasto.mas(gasto),
                costas,
                enConvenio,
                actualizadaA);
    }

    /**
     * Suma una obligacion acogida a un convenio, <b>fuera</b> de lo exigible (#403).
     *
     * <p>Un metodo aparte de {@link #mas} y no una bandera suya: quien compone la deuda tiene que
     * decidir, obligacion por obligacion, a cual de las dos cifras va, y que la decision se lea en
     * el nombre del metodo es lo que impide sumarla a la que no toca sin que nadie lo note.
     */
    public DeudaDelExpediente masEnConvenio(Dinero acogida) {
        Objects.requireNonNull(acogida, "Lo acogido viaja, aunque sea cero");
        return new DeudaDelExpediente(
                insoluto, reajuste, interes, gasto, costas, enConvenio.mas(acogida), actualizadaA);
    }

    /**
     * La misma deuda con sus costas puestas (#42).
     *
     * <p>Un metodo aparte de {@link #mas} y no un quinto sumando suyo: las cuatro partes se
     * acumulan <b>obligacion por obligacion</b> del expediente, mientras que las costas se leen una
     * sola vez de sus propias obligaciones. Sumarlas dentro de {@code mas} las multiplicaria por el
     * numero de obligaciones del expediente, que es la clase de defecto que no se ve hasta que un
     * expediente tiene dos.
     */
    public DeudaDelExpediente conCostas(Dinero delProcedimiento) {
        Objects.requireNonNull(delProcedimiento, "Las costas viajan, aunque sean cero");
        return new DeudaDelExpediente(
                insoluto, reajuste, interes, gasto, delProcedimiento, enConvenio, actualizadaA);
    }

    /** La deuda materia de cobranza: las cuatro partes, sin costas. */
    public Dinero materiaDeCobranza() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }

    /**
     * El total exigible: la deuda materia de cobranza mas las costas. Lo acogido a un convenio no
     * esta aqui (#403).
     */
    public Dinero total() {
        return materiaDeCobranza().mas(costas);
    }

    /**
     * Si lo unico que el expediente tiene por cobrar esta acogido a un convenio (#403): nada
     * exigible, y algo en convenio.
     *
     * <p>Es lo que separa las dos razones por las que un acto de cobranza no procede. Sin deuda
     * alguna, el obligado pago y lo que corresponde es concluir; con deuda acogida, el obligado
     * <b>no</b> pago, y decirle que si seria afirmar un pago que no hubo.
     */
    public boolean soloQuedaDeudaEnConvenio() {
        return !total().esPositivo() && enConvenio.esPositivo();
    }
}
