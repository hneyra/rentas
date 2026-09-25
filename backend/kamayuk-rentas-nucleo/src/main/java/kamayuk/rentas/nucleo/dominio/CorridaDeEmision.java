package kamayuk.rentas.nucleo.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que hizo una corrida de emision anual del predial, <b>ya escrito</b> (#523).
 *
 * <p>Antes de esto la corrida se componia en memoria y viajaba solo en la respuesta del {@code
 * POST} que la ejecuta. Cerrar la pestana perdia el resultado de un proceso que toca decenas de
 * miles de cuentas, y el panel del modulo no se podia construir porque ocho de sus nueve cifras
 * salen de aqui (#503 F6).
 *
 * <p><b>Los observados son la parte que no se puede recomponer.</b> Un observado es, por
 * definicion, el contribuyente que <b>no</b> tiene determinacion: leer el padron despues no dice
 * quienes fueron ni por que. Lo demas —cuantos, cuanto— se podria recontar; el motivo de cada uno,
 * no.
 *
 * @param id el que asigno la base; nulo mientras no se ha guardado
 * @param ejercicio el ejercicio recalculado
 * @param alcance TODOS o SECTOR
 * @param sector obligatorio con SECTOR
 * @param modalidad el cronograma aplicado a las cuotas
 * @param simulacion si la corrida no guardo ninguna determinacion
 * @param conjunto el conjunto sellado con que se calculo; vacio si no se determino ninguna
 * @param conjuntoId el identificador de ese conjunto, con el que se vuelve a leer su cuadro; nulo
 *     si la corrida es anterior a {@code V23} o si no determino a nadie
 * @param derechoDeEmision el derecho de emision que la corrida aplico a cada cuenta, sellado el dia
 *     de la emision; nulo si la corrida es anterior a {@code V23} o si no determino a nadie
 * @param leidos cuantos contribuyentes miro en total
 * @param determinados cuantos se determinaron
 * @param montoEmitido la suma de lo determinado, impuesto mas derecho de emision
 * @param fechaCalculo el dia al que corresponden sus cifras (regla 9)
 * @param observados los que quedaron fuera, cada uno con su motivo
 */
public record CorridaDeEmision(
        @Nullable Long id,
        Ejercicio ejercicio,
        String alcance,
        @Nullable String sector,
        @Nullable String codigoDesde,
        @Nullable String codigoHasta,
        String modalidad,
        boolean simulacion,
        String conjunto,
        @Nullable Long conjuntoId,
        @Nullable Dinero derechoDeEmision,
        int leidos,
        int determinados,
        Dinero montoEmitido,
        LocalDate fechaCalculo,
        List<Observado> observados) {

    /**
     * {@code corrida_predial.sector varchar(20)} desde V29 (#408).
     *
     * <p>Es el ancho de {@code predio_ref.sector_codigo} (V4), con el que el alcance se compara: un
     * sector mas largo no puede elegir a ningun predio. Hasta #408 la columna era {@code
     * varchar(10)} y nada lo topaba antes de la base; el rastro reventaba con 22001 al final, con
     * la emision ya hecha. {@code DeterminarPredialMasivo.Peticion} topa con esta misma cifra,
     * antes de determinar a nadie.
     */
    public static final int SECTOR_MAXIMO = 20;

    public CorridaDeEmision {
        Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
        Objects.requireNonNull(alcance, "La corrida necesita su alcance");
        Objects.requireNonNull(modalidad, "La corrida necesita su modalidad");
        Objects.requireNonNull(conjunto, "La corrida necesita su conjunto, aunque sea vacio");
        Objects.requireNonNull(montoEmitido, "La corrida necesita lo que emitio");
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9)");
        observados = List.copyOf(Objects.requireNonNull(observados, "La lista es vacia, no nula"));
        if (sector != null && sector.length() > SECTOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El sector '" + sector + "' excede los " + SECTOR_MAXIMO + " caracteres");
        }
        /* **El sello viaja entero o no viaja** (#312, V23). La cifra sin su conjunto
        vuelve a ser un numero sin fuente —que es lo que esta columna existe para
        dejar de ser— y el conjunto sin su cifra deja el campo del panel vacio
        habiendola sabido. El `CHECK` de la base dice lo mismo; esto lo dice donde
        se construye, que es antes de que ninguna fila se escriba. */
        if ((conjuntoId == null) != (derechoDeEmision == null)) {
            throw new IllegalArgumentException(
                    "Una corrida sella su derecho de emision JUNTO AL conjunto del que salio, o no"
                            + " sella ninguno de los dos: conjuntoId="
                            + conjuntoId
                            + ", derechoDeEmision="
                            + derechoDeEmision);
        }
        if (determinados > leidos) {
            throw new IllegalArgumentException(
                    "Una corrida no puede determinar a mas contribuyentes de los que miro: "
                            + determinados
                            + " de "
                            + leidos);
        }
    }

    /**
     * Un contribuyente que quedo fuera de la emision, y por que.
     *
     * @param codContribuyente su codigo del padron
     * @param nombre su nombre, aunque sea vacio
     * @param motivo por que quedo fuera. Sin el, el observado no se puede arreglar
     */
    public record Observado(String codContribuyente, String nombre, String motivo) {

        /**
         * {@code corrida_predial_observado.nombre varchar(240)} desde V29 (#408).
         *
         * <p>El nombre se copia <b>entero</b> de {@code contribuyente.nombre_razon_social}, que
         * admite 240 —una «SUCESION INDIVISA …» con sus herederos los usa—. Hasta #408 la columna
         * era {@code varchar(200)}: el rastro reventaba con 22001 y la lista de observados se
         * perdia. Recortarlo aqui se descarto: un nombre truncado es otro contribuyente en el
         * informe.
         */
        public static final int NOMBRE_MAXIMO = 240;

        public Observado {
            Objects.requireNonNull(codContribuyente, "El observado necesita su codigo");
            Objects.requireNonNull(nombre, "El observado necesita su nombre, aunque sea vacio");
            Objects.requireNonNull(motivo, "Un observado sin motivo no se puede arreglar");
            if (nombre.length() > NOMBRE_MAXIMO) {
                throw new IllegalArgumentException(
                        "El nombre del observado "
                                + codContribuyente
                                + " excede los "
                                + NOMBRE_MAXIMO
                                + " caracteres");
            }
        }
    }
}
