package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El punto de entrada para que otro contexto acotado asiente un cargo (ARQ-01 §4 regla 2): quien
 * determina la deuda —rentas, sanciones, licencias— se la pide a {@code cuentacorriente} en vez de
 * escribir en {@code cuenta_corriente_asiento} por su cuenta; este contexto no conoce reglas
 * tributarias, así que no valida que el importe sea correcto, solo lo asienta.
 *
 * <p>Es la API pública de este módulo: vive en el paquete raíz, no en {@code .aplicacion} ni en
 * {@code .dominio}, mismo patrón que {@link ConsultaDeDeudaPublica}.
 *
 * <p>Cubre el caso corriente —un cargo nuevo, insoluto, en fase ordinaria— que es el único que un
 * contexto que recién determina una deuda necesita: reversar, abonar o mover de fase son actos
 * posteriores de otros contextos (tesorería, coactiva) que ya tienen su propio caso de uso.
 */
public interface GeneradorDeCargos {

    /**
     * Asienta un cargo insoluto nuevo, en fase ordinaria.
     *
     * @param ejercicio el ejercicio del cargo; clave de partición del libro
     * @param contribuyenteId a quién se le cobra
     * @param tributo el tributo al que se imputa, tal como lo nombra quien pide el cargo
     * @param periodo la cuota o el mes, si el tributo se divide; {@code null} si no aplica
     * @param predioId la unidad, si la obligación es predial o de arbitrios
     * @param vehiculoId la unidad, si la obligación es vehicular
     * @param referenciaExterna como entra una papeleta o una licencia, sin clave foránea (ARQ-01 §4
     *     regla 2); {@code null} cuando quien pide el cargo ya tiene su propia cabecera de
     *     determinación con la que explicarlo
     * @param monto siempre positivo
     * @param fechaValor fecha a la que se imputa el cargo
     * @param documentoOrigen el documento que lo origina
     * @param observacion por qué se asienta (regla 10)
     */
    void generarCargo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Asienta un <b>gasto</b> del procedimiento coactivo: concepto {@code GASTO}, fase {@code
     * COACTIVA} (#42, RF-104).
     *
     * <p>Es el segundo metodo de este puerto, y existe porque {@link #generarCargo} dice
     * exactamente lo que cubre: «un cargo nuevo, insoluto, en fase ordinaria». Las costas del
     * procedimiento no son ninguna de las dos cosas:
     *
     * <ul>
     *   <li><b>Concepto {@code GASTO} y no {@code INSOLUTO}</b>, porque no son tributo. El desglose
     *       de la deuda las cuenta aparte —{@code CalculoDeDeuda} netea por concepto— y eso es lo
     *       que permite que la REC imprima «insoluto / reajuste / interes / gasto» sin mezclarlas
     *       con el impuesto. Y como no son insoluto, <b>no devengan interes moratorio</b> ({@code
     *       CalculoDeDeuda} solo acumula sobre el insoluto), que es lo que corresponde: una costa
     *       no es una deuda tributaria en mora.
     *   <li><b>Fase {@code COACTIVA} y no {@code ORDINARIA}</b>, porque nacen dentro de un
     *       procedimiento de ejecucion. Asentarlas en fase ordinaria las dejaria fuera del
     *       expediente que las genero, cobrables por la ventanilla comun mientras el ejecutor las
     *       reclama.
     * </ul>
     *
     * <p><b>Ni la fase ni el concepto viajan en la firma</b>, a proposito: {@code Fase} y {@code
     * Concepto} viven en {@code .dominio} y no cruzan el limite del modulo (ARQ-01 §4). Los fija
     * este contexto, que es el unico que sabe lo que significan. Es el mismo criterio con el que
     * {@code DeudaAcogida} transporta su fase de origen como texto opaco.
     *
     * <p>Sin periodo y sin unidad: una costa no es de una cuota ni de un predio, es del
     * procedimiento. La obligacion que crea en el libro es {@code (contribuyente, tributo,
     * ejercicio, 0, sin unidad)}.
     *
     * @param ejercicio el ejercicio al que se imputa; clave de particion del libro
     * @param contribuyenteId el obligado del expediente
     * @param tributo el tributo de costas al que se imputa, tal como lo nombra quien liquida
     * @param referenciaExterna el numero del expediente coactivo que la devenga; queda en el libro
     *     para poder explicar el cargo, no para agrupar por el
     * @param monto siempre positivo; sale del arancel sellado, nunca de un literal (regla 5)
     * @param fechaValor la fecha de la liquidacion
     * @param documentoOrigen el numero de la liquidacion que lo origina
     * @param observacion por que se asienta (regla 10)
     */
    void generarGastoDelProcedimiento(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion);
}
