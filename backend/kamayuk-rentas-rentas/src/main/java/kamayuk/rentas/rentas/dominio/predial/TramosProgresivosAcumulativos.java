package kamayuk.rentas.rentas.dominio.predial;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;

/**
 * RT-013 — Tramos y alicuotas progresivas acumulativas (TUO Ley de Tributacion Municipal, D.S.
 * 156-2004-EF, art. 13; NEG-05 §RT-013).
 *
 * <p><b>«Acumulativas» significa que cada tramo se aplica solo a la porcion de base que cae en
 * el</b>, no a toda la base: es la diferencia entre un impuesto progresivo y uno con saltos. Corre
 * <b>sobre la base del contribuyente</b> —el resultado de {@link
 * RT011BaseImponibleDelContribuyente}—, nunca sobre la base de un predio: aplicar los tramos predio
 * por predio es exactamente el error sistematico a la baja que NEG-05 §1 advierte.
 *
 * <p><b>No es una {@code ReglaTributaria} ni una {@code ReglaDeAgregacion}.</b> Las dos formas del
 * motor de #14 (ver {@code kamayuk.rentas.parametros.ReglaTributaria}, {@code ReglaDeAgregacion})
 * asumen que una regla opera sobre una partida o suma partidas; esta regla transforma un valor ya
 * agregado en otro, un tercer caso que el motor todavia no cubre. Por eso vive como funcion pura
 * aparte, en la misma linea que {@code CalculoDeDeuda.deudaActualizadaA} de {@code
 * cuentacorriente}: sin base de datos, sin reloj, sin configuracion global (regla 6).
 *
 * <p><b>Los tramos son argumento, nunca literales del codigo</b> (regla 5): el cuadro de {@code
 * ‹VERIFICAR›} tramos y alicuotas del art. 13 sigue bloqueado por D-02, asi que esta clase no sabe
 * cuantos tramos hay ni cuanto vale ninguno. Lo unico que codifica es el algoritmo confirmado por
 * NEG-05: progresivo, acumulativo, sobre la base del contribuyente.
 */
public final class TramosProgresivosAcumulativos {

    private TramosProgresivosAcumulativos() {}

    /**
     * El impuesto resultante de aplicar los tramos, en orden, a {@code baseAfecta}.
     *
     * <p>Redondea en {@link PuntoDeRedondeo#IMPUESTO_POR_TRAMO}, y falla si ese punto no esta
     * parametrizado: mientras D-03c no diga si el SRTM redondea tramo a tramo o solo al cerrar el
     * impuesto, no redondear es una respuesta tan inventada como cualquier otra, con el agravante
     * de que no se nota.
     *
     * @param tramos en orden ascendente de limite; el ultimo, sin tope, cierra la lista
     * @throws IllegalArgumentException si la base es negativa o la lista de tramos esta vacia
     */
    public static Dinero calcular(
            Dinero baseAfecta, List<Tramo> tramos, PoliticasDeRedondeo redondeo) {
        Objects.requireNonNull(
                redondeo, "Las politicas de redondeo se reciben, no se fijan (D-03)");
        Dinero impuesto = Dinero.CERO;
        for (AporteDeTramo aporte : desglosar(baseAfecta, tramos)) {
            impuesto = impuesto.mas(aporte.aporte());
        }
        return impuesto.redondeadoEn(PuntoDeRedondeo.IMPUESTO_POR_TRAMO, redondeo);
    }

    /**
     * Lo mismo que {@link #calcular}, pero diciendo tramo a tramo que hizo: hasta donde llega, con
     * que alicuota, sobre que porcion corrio y cuanto puso.
     *
     * <p>Es la memoria de calculo del articulo 13, y se publica desde aqui —no se recompone en la
     * capa web ni en la interfaz (RNF-083)—: quien aplica los tramos es el unico que sabe cuanto de
     * la base cayo en cada uno, porque depende del limite del tramo anterior.
     *
     * <p><b>Sin redondear.</b> Los aportes salen tal como se calcularon (ADR-0018: los intermedios
     * corren sin redondear); el unico redondeo es el de {@link #calcular}, sobre la suma. Un tramo
     * que no recibio nada de la base no aparece en la lista: no aporto.
     *
     * @param tramos en orden ascendente de limite; el ultimo, sin tope, cierra la lista
     * @throws IllegalArgumentException si la base es negativa o la lista de tramos esta vacia
     */
    public static List<AporteDeTramo> desglosar(Dinero baseAfecta, List<Tramo> tramos) {
        Objects.requireNonNull(baseAfecta, "El calculo necesita la base afecta");
        Objects.requireNonNull(tramos, "El calculo necesita el cuadro de tramos");
        if (baseAfecta.esNegativo()) {
            throw new IllegalArgumentException(
                    "La base afecta no puede ser negativa: " + baseAfecta);
        }
        if (tramos.isEmpty()) {
            throw new IllegalArgumentException(
                    "El cuadro de tramos esta vacio: sin tramos no hay como aplicar el articulo 13");
        }

        List<AporteDeTramo> aportes = new ArrayList<>();
        Dinero baseRestante = baseAfecta;
        Dinero limiteAnterior = Dinero.CERO;
        int orden = 0;

        for (Tramo tramo : tramos) {
            orden++;
            if (baseRestante.esCero()) {
                break;
            }
            Dinero anchoDelTramo =
                    tramo.tieneTope()
                            ? Objects.requireNonNull(tramo.limiteSuperior()).menos(limiteAnterior)
                            : baseRestante;
            Dinero porcionEnEsteTramo =
                    anchoDelTramo.esMenorQue(baseRestante) ? anchoDelTramo : baseRestante;

            BigDecimal fraccion = tramo.alicuota().valor().movePointLeft(2);
            aportes.add(
                    new AporteDeTramo(
                            orden,
                            tramo.limiteSuperior(),
                            tramo.alicuota(),
                            porcionEnEsteTramo,
                            porcionEnEsteTramo.por(fraccion)));
            baseRestante = baseRestante.menos(porcionEnEsteTramo);
            if (tramo.tieneTope()) {
                limiteAnterior = Objects.requireNonNull(tramo.limiteSuperior());
            }
        }

        return List.copyOf(aportes);
    }
}
