package kamayuk.rentas.cuentacorriente.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Proyecta un conjunto de asientos a los saldos que les corresponden. <b>Funcion pura</b> (regla
 * 6): entran asientos y una fecha, sale la proyeccion.
 *
 * <p>Es <b>una sola definicion</b> de «que saldo produce este libro», y ahi esta el punto. El
 * mantenimiento incremental, la reconstruccion y la conciliacion la usan los tres: si cada uno
 * agrupara y sumara por su cuenta, la conciliacion estaria comparando dos implementaciones en vez
 * de comparar la proyeccion contra el libro, y una discrepancia entre ellas se leeria como una
 * corrupcion de datos que no existe.
 *
 * <p>Sin base de datos y sin reloj: la fecha de calculo entra como argumento. Es lo que permite
 * probarla sin levantar nada, y lo que hace que reconstruir dos veces el mismo libro de dos filas
 * identicas.
 */
public final class ProyeccionDelSaldo {

    private ProyeccionDelSaldo() {}

    /**
     * Los saldos que produce este conjunto de asientos, uno por obligacion.
     *
     * <p>Solo se netea {@link Concepto#INSOLUTO}: ver {@link SaldoProyectado} para por que el
     * reajuste y el interes no se precalculan. Un asiento de otro concepto sigue contando para la
     * {@code fase} y el {@code ultimoAsientoId} de su obligacion —es un movimiento del libro—, pero
     * no mueve el importe.
     *
     * @param asientos los asientos a proyectar; pueden ser de varias obligaciones
     * @param fechaCalculo el instante que se estampa en cada fila proyectada (regla 6)
     */
    public static List<SaldoProyectado> de(List<Asiento> asientos, Instant fechaCalculo) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(fechaCalculo, "La fecha de calculo entra como argumento (regla 6)");

        Map<ClaveDeSaldo, Acumulado> porObligacion = new LinkedHashMap<>();
        for (Asiento asiento : asientos) {
            porObligacion
                    .computeIfAbsent(ClaveDeSaldo.de(asiento), clave -> new Acumulado())
                    .agregar(asiento);
        }

        List<SaldoProyectado> saldos = new ArrayList<>();
        porObligacion.forEach(
                (clave, acumulado) -> saldos.add(acumulado.proyectar(clave, fechaCalculo)));
        return List.copyOf(saldos);
    }

    /** Lo que se va acumulando de una obligacion mientras se recorren sus asientos. */
    private static final class Acumulado {
        private Dinero insoluto = Dinero.CERO;
        private @Nullable Long ultimoAsientoId;
        private Fase fase = Fase.ORDINARIA;

        void agregar(Asiento asiento) {
            if (asiento.concepto() == Concepto.INSOLUTO) {
                insoluto =
                        asiento.tipo() == TipoAsiento.CARGO
                                ? insoluto.mas(asiento.monto())
                                : insoluto.menos(asiento.monto());
            }
            // El ultimo por identificador, no por fecha valor: es el ultimo que se
            // asento, y es lo que permite saber si la proyeccion se quedo atras. Dos
            // asientos pueden compartir fecha valor; el identificador no se repite.
            Long id = asiento.id();
            if (id != null && (ultimoAsientoId == null || id > ultimoAsientoId)) {
                ultimoAsientoId = id;
                fase = asiento.fase();
            }
        }

        SaldoProyectado proyectar(ClaveDeSaldo clave, Instant fechaCalculo) {
            return new SaldoProyectado(clave, insoluto, fase, ultimoAsientoId, fechaCalculo);
        }
    }
}
