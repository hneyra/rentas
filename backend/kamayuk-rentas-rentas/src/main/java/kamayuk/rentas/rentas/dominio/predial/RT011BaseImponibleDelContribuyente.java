package kamayuk.rentas.rentas.dominio.predial;

import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.Concepto;
import kamayuk.rentas.parametros.IdentificadorDeRegla;
import kamayuk.rentas.parametros.InsumosDeLaAgregacion;
import kamayuk.rentas.parametros.RangoDeEjercicios;
import kamayuk.rentas.parametros.ReglaDeAgregacion;

/**
 * RT-011 — Base imponible del contribuyente: {@code base_contribuyente = Σ base_imponible_predio}
 * (NEG-05 §RT-011, confirmado por M02).
 *
 * <p><b>Solo la mitad agregable de RT-011.</b> NEG-05 describe la regla en dos pasos:
 *
 * <pre>
 * autovaluo → × % actualizacion → × % propiedad → base_imponible_predio   (por predio)
 * base_contribuyente = Σ base_imponible_predio                            (del conjunto)
 * </pre>
 *
 * <p>Esta clase es <b>solo el segundo paso</b>, la suma. El primero —ponderar el autovaluo de cada
 * predio— no esta aqui a proposito: el {@code % actualizacion} es uno de los cuatro factores que
 * NEG-05 §0.1 marca sin fuente identificada (D-11), y CLAUDE.md es explicito en no implementar
 * ninguno de los cuatro ni estructuralmente. {@code BASE_IMPONIBLE_PREDIO} entra aqui como un
 * concepto ya calculado por quien ensambla la entrada de cada predio —hoy, en las pruebas, un valor
 * declarado; el dia que D-11 se cierre, el resultado de la regla que pondera—.
 *
 * <p>Es exactamente el punto critico de NEG-05 §1: «un contribuyente con tres predios pequenos
 * puede caer en un tramo superior. Confundir esto —calcular predio por predio— produce un error
 * sistematico a la baja en todo el padron». Esta regla, junto con {@link
 * TramosProgresivosAcumulativos} (RT-013), es lo que demuestra AC1 de #30: los dos resultados
 * difieren, y el correcto es el agregado.
 */
public final class RT011BaseImponibleDelContribuyente implements ReglaDeAgregacion {

    /** Lo que cada predio aporta a la base, ya ponderado por su % de propiedad. */
    public static final Concepto BASE_IMPONIBLE_PREDIO = Concepto.de("BASE_IMPONIBLE_PREDIO");

    /**
     * La suma sobre todos los predios del contribuyente: la base sobre la que corren los tramos.
     */
    public static final Concepto BASE_IMPONIBLE_CONTRIBUYENTE =
            Concepto.de("BASE_IMPONIBLE_CONTRIBUYENTE");

    @Override
    public IdentificadorDeRegla identificador() {
        return IdentificadorDeRegla.de("RT-011");
    }

    @Override
    public RangoDeEjercicios vigencia() {
        return RangoDeEjercicios.desde(new Ejercicio(2004));
    }

    @Override
    public String descripcion() {
        return "RT-011 — Base imponible del contribuyente: suma de la base imponible de cada"
                + " predio (TUO Ley de Tributacion Municipal, D.S. 156-2004-EF, NEG-05 §RT-011)";
    }

    @Override
    public Concepto deCadaPartida() {
        return BASE_IMPONIBLE_PREDIO;
    }

    @Override
    public Concepto produce() {
        return BASE_IMPONIBLE_CONTRIBUYENTE;
    }

    @Override
    public Dinero agregar(List<Dinero> aportes, InsumosDeLaAgregacion insumos) {
        Dinero total = Dinero.CERO;
        for (Dinero aporte : aportes) {
            total = total.mas(aporte);
        }
        return total;
    }
}
