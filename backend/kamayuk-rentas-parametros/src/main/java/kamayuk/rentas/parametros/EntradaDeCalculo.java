package kamayuk.rentas.parametros;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;

/**
 * Lo que entra al motor para calcular <b>una partida</b> —un predio, un vehiculo—: los datos
 * declarados que siembran el grafo, mas el ejercicio, el conjunto sellado y las politicas de
 * redondeo, una por punto.
 *
 * <p>El ejercicio y no una fecha: la implementacion aplicable es la del <b>ejercicio del hecho
 * imponible</b> (ARQ-09 §1.3). Con una fecha, «hoy» es un argumento valido y calcularia 2027 con
 * las reglas de 2037.
 *
 * <p>Los datos declarados son un {@link EstadoDelCalculo} y no un solo importe: un predio entra con
 * su area, su antiguedad y su porcentaje de propiedad, y de ahi salen tres ramas —terreno,
 * edificacion y obras complementarias— que convergen despues.
 *
 * <p>Las {@link CaracteristicasDeLaPartida} son lo que el predio <b>es</b> y no cuanto vale: la
 * via, la categoria, el material. Son las llaves con que las reglas de valuacion buscan su
 * parametro.
 */
public record EntradaDeCalculo(
        Ejercicio ejercicio,
        EstadoDelCalculo declarados,
        CaracteristicasDeLaPartida caracteristicas,
        ParametrosSellados parametros,
        PoliticasDeRedondeo redondeo) {

    public EntradaDeCalculo {
        Objects.requireNonNull(ejercicio, "El ejercicio del hecho imponible entra como argumento");
        Objects.requireNonNull(declarados, "Los datos declarados son un estado, vacio si no hay");
        Objects.requireNonNull(
                caracteristicas,
                "Las caracteristicas de la partida son un objeto, vacio si no hay");
        Objects.requireNonNull(parametros, "La regla necesita el conjunto sellado");
        Objects.requireNonNull(
                redondeo, "Las politicas de redondeo se reciben, no se fijan (D-03)");
        if (!ejercicio.equals(parametros.ejercicio())) {
            throw new IllegalArgumentException(
                    "Se pidio calcular el ejercicio "
                            + ejercicio
                            + " con el conjunto sellado de "
                            + parametros.ejercicio()
                            + ". Cruzar los ejercicios produce una cifra plausible y equivocada");
        }
    }

    public InsumosDeLaAgregacion paraAgregacion() {
        return new InsumosDeLaAgregacion(ejercicio, parametros, redondeo);
    }
}
