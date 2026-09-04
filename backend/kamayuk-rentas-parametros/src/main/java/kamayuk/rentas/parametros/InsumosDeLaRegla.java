package kamayuk.rentas.parametros;

import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;

/**
 * Lo unico que una regla puede ver mientras calcula: los conceptos que <b>declaro</b> necesitar,
 * los parametros sellados, el ejercicio y las politicas de redondeo.
 *
 * <p>La restriccion es el punto. Si la regla recibiera el estado completo podria leer un concepto
 * que no declaro en {@link ReglaTributaria#requiere()}, y entonces el grafo declarado seria
 * documentacion en vez de contrato: el motor ordenaria las reglas por unas dependencias y la regla
 * usaria otras. Pedir un concepto no declarado es {@link ConceptoNoDeclarado}, no un valor vacio.
 *
 * <p>No hay reloj, no hay base de datos y no hay configuracion global (regla 6, ARQ-09 §1.2). Todo
 * lo que la regla puede leer esta aqui dentro, y todo entro como argumento.
 */
public final class InsumosDeLaRegla {

    private final IdentificadorDeRegla regla;
    private final Set<Concepto> declarados;
    private final EstadoDelCalculo estado;
    private final Ejercicio ejercicio;
    private final CaracteristicasDeLaPartida caracteristicas;
    private final ParametrosSellados parametros;
    private final PoliticasDeRedondeo redondeo;

    InsumosDeLaRegla(
            IdentificadorDeRegla regla,
            Set<Concepto> declarados,
            EstadoDelCalculo estado,
            Ejercicio ejercicio,
            CaracteristicasDeLaPartida caracteristicas,
            ParametrosSellados parametros,
            PoliticasDeRedondeo redondeo) {
        this.regla = regla;
        this.declarados = declarados;
        this.estado = estado;
        this.ejercicio = ejercicio;
        this.caracteristicas = caracteristicas;
        this.parametros = parametros;
        this.redondeo = redondeo;
    }

    /** El importe de un concepto declarado. */
    public Dinero de(Concepto concepto) {
        Objects.requireNonNull(concepto, "Pedir un insumo exige su concepto");
        if (!declarados.contains(concepto)) {
            throw new ConceptoNoDeclarado(regla, concepto);
        }
        return estado.valor(concepto)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El motor aplico "
                                                + regla
                                                + " sin tener "
                                                + concepto
                                                + ". Es un defecto del motor, no de la regla"));
    }

    /** El ejercicio del hecho imponible, no el ano en curso (ARQ-09 §1.3). */
    public Ejercicio ejercicio() {
        return ejercicio;
    }

    /**
     * La llave con que esta partida busca un parametro: la via del terreno, la categoria de la
     * construccion, el material. Si la partida no la trae es {@code CaracteristicaAusente}, no un
     * valor por omision.
     */
    public String caracteristica(String nombre) {
        return caracteristicas.exigir(nombre);
    }

    /** El conjunto sellado. Los parametros entran como argumento, nunca se leen dentro. */
    public ParametrosSellados parametros() {
        return parametros;
    }

    /**
     * La politica del punto que la regla redondea. Se recibe, no se elige: D-03 sigue abierta
     * (ARQ-09 §1.4).
     *
     * <p><b>La regla nombra su punto</b>, y por eso no hay un {@code redondeo()} sin argumento: con
     * una politica unica para todo el calculo, el punto que nadie observo no falla —no redondea— y
     * el importe sale plausible. Que el punto no este parametrizado es {@code PuntoSinPolitica},
     * como que falte un parametro es {@code ParametroAusente}.
     */
    public PoliticaDeRedondeo redondeoEn(PuntoDeRedondeo punto) {
        return redondeo.en(punto);
    }

    /**
     * Atajo al parametro numerico que la regla necesita. Si falta es {@code ParametroAusente}, no
     * un valor por omision: un calculo al que le falta un factor no debe producir un importe
     * (ARQ-09 §2.5).
     */
    public ValorNormativo numero(String tipo, String clave) {
        return parametros.exigirNumero(tipo, clave);
    }

    /**
     * La regla leyo un concepto que no declaro. El grafo que el motor ordeno no seria el que la
     * regla usa, y el resultado dependeria de un orden que nadie declaro.
     */
    public static final class ConceptoNoDeclarado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ConceptoNoDeclarado(IdentificadorDeRegla regla, Concepto concepto) {
            super(
                    regla
                            + " leyo "
                            + concepto
                            + " sin declararlo en requiere(). El motor ordena las reglas por lo"
                            + " declarado: si la regla usa otra cosa, el orden que calculo no es el"
                            + " que hace falta");
        }
    }
}
