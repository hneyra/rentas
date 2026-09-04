package kamayuk.rentas.rentas.dominio.predial;

import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.Concepto;
import kamayuk.rentas.parametros.IdentificadorDeRegla;
import kamayuk.rentas.parametros.InsumosDeLaRegla;
import kamayuk.rentas.parametros.RangoDeEjercicios;
import kamayuk.rentas.parametros.ReglaTributaria;

/**
 * RT-001 — Valor de terreno: {@code area_terreno × arancel(via, ejercicio)} (NEG-05 §RT-001; TUO
 * Ley de Tributacion Municipal, D.S. 156-2004-EF, art. 11).
 *
 * <p><b>Es la primera rama del grafo de NEG-05 §1 que se puede escribir entera</b>, y por eso
 * existe: no lleva ninguno de los cuatro factores sin fuente de D-11 —ni el 5 %, ni el {@code %
 * actualizacion}, ni el factor de oficializacion, ni la deduccion de Amazonia—. Las otras dos ramas
 * (RT-002 edificacion y RT-005 obras complementarias) los llevan, y hasta que D-11 cierre no se
 * escriben ni estructuralmente.
 *
 * <p><b>Ninguna cifra vive aqui</b> (regla 5). El arancel es un parametro del conjunto sellado, y
 * la clave con que se busca —la via— es una caracteristica de la partida. Con un conjunto vacio
 * esta regla no devuelve cero: lanza {@code ParametroAusente} nombrando el arancel que falta, y eso
 * es lo que le dice a E-3 (#200) que hay que transcribir.
 *
 * <p>Casos borde que NEG-05 §RT-001 deja abiertos y que <b>esta regla no resuelve</b>, porque
 * resolverlos es decidir: un predio con frente a <b>dos vias de arancel distinto</b> —¿cual manda,
 * el mayor?—, un predio <b>sin arancel asignado</b> y un terreno en zona <b>sin habilitacion
 * urbana</b>. Los tres estan en el corpus de casos con su fila, sin cifra esperada, y los tres caen
 * hoy del mismo lado: la caracteristica o el parametro faltan, y la regla falla nombrandolos.
 * Fallar es la respuesta correcta mientras nadie haya decidido la otra.
 */
public final class RT001ValorDeTerreno implements ReglaTributaria {

    /** El area del terreno, que entra declarada con la partida. */
    public static final Concepto AREA_TERRENO = Concepto.de("AREA_TERRENO");

    /** Lo que vale el terreno del predio. Primera rama de {@code RT-010}. */
    public static final Concepto VALOR_TERRENO = Concepto.de("VALOR_TERRENO");

    /** Nombre de la caracteristica que dice a que via da el predio. */
    public static final String VIA = "via";

    /** Tipo del parametro que trae el arancel; la clave es la via. */
    public static final String ARANCEL = "ARANCEL";

    @Override
    public IdentificadorDeRegla identificador() {
        return IdentificadorDeRegla.de("RT-001");
    }

    @Override
    public RangoDeEjercicios vigencia() {
        return RangoDeEjercicios.desde(new Ejercicio(2004));
    }

    @Override
    public String descripcion() {
        return "RT-001 — Valor de terreno: area por el arancel de su via (TUO Ley de Tributacion"
                + " Municipal, D.S. 156-2004-EF, art. 11; NEG-05 §RT-001)";
    }

    @Override
    public Set<Concepto> requiere() {
        return Set.of(AREA_TERRENO);
    }

    @Override
    public Concepto produce() {
        return VALOR_TERRENO;
    }

    @Override
    public Dinero calcular(InsumosDeLaRegla insumos) {
        Dinero area = insumos.de(AREA_TERRENO);
        String via = insumos.caracteristica(VIA);
        return area.por(insumos.numero(ARANCEL, via).valor());
    }
}
