package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import org.jspecify.annotations.Nullable;

/**
 * La determinacion predial de un contribuyente tal como sale por {@code predial_individual} ({@code
 * POST /api/v1/rentas/predial/calculo-individual}, #395).
 *
 * <p>Trae las cinco piezas que la memoria de calculo necesita —los predios, la base ya ponderada,
 * los tramos aplicados, las cuotas con su derecho de emision y la fecha—, y las trae <b>ya
 * calculadas</b>. RNF-083: ninguna se recompone en la interfaz. Sumar los autovaluos de {@link
 * #predios} para adelantar la base daria una cifra parecida —sin el % de propiedad— y el error no
 * se veria; multiplicar la base por una alicuota para adivinar lo que puso un tramo, tampoco.
 *
 * <p><b>Los importes viajan como texto</b>, igual que en {@link DeterminacionVehicularResource}:
 * son la cifra que se dibuja, no un {@code Dinero} con el que operar. La fecha a la que estan
 * calculados es {@link #fechaCalculo}, una sola para toda la determinacion, porque toda ella se
 * hizo en el mismo instante y con el mismo conjunto (regla 9, RNF-075).
 *
 * <p><b>{@link #conjunto} y {@link #conjuntoId} no son decorado</b>: una cifra sin su version no se
 * puede recalcular (ARQ-09 §3). Con ellos, volver a determinar este mismo ejercicio dentro de diez
 * anios da el mismo centimo; sin ellos, da «los parametros de entonces», que es otra cosa.
 *
 * @param id el identificador de la determinacion guardada; {@code 0} si esto fue una simulacion
 * @param simulacion si no se guardo ninguna fila
 * @param ejercicio el ejercicio determinado
 * @param codContribuyente el codigo del contribuyente en el padron
 * @param sujeto de quien es, ya redactado para leerse
 * @param conjuntoId el conjunto de parametros sellado con que se calculo
 * @param conjunto como se nombra ese conjunto: «2026 v1»
 * @param fechaCalculo el dia al que corresponde todo lo de aqui
 * @param predios los que integran la base, con lo que puso cada uno
 * @param valuoTotal la suma de los autovaluos, sin ponderar
 * @param valuoExonerado la parte exonerada, sin ponderar
 * @param valuoAfecto lo que queda afecto, sin ponderar
 * @param baseImponible la base del contribuyente, ya ponderada por el % de propiedad de cada predio
 * @param uit la UIT del ejercicio con que se convirtieron los limites de los tramos
 * @param tramos que aporto cada tramo del articulo 13
 * @param minimoImponible el minimo del ejercicio (RT-014)
 * @param impuestoInsoluto el impuesto anual determinado
 * @param derechoDeEmision el derecho de emision mecanizada
 * @param totalAPagar el impuesto mas el derecho de emision
 * @param modalidad el cronograma que se aplico
 * @param cuotas las cuotas con sus vencimientos
 * @param reglasAplicadas los identificadores de las reglas que produjeron el monto, en orden
 */
public record DeterminacionPredialResource(
        long id,
        boolean simulacion,
        String ejercicio,
        String codContribuyente,
        String sujeto,
        long conjuntoId,
        String conjunto,
        String fechaCalculo,
        List<PredioDeLaBase> predios,
        String valuoTotal,
        String valuoExonerado,
        String valuoAfecto,
        String baseImponible,
        String uit,
        List<TramoAplicado> tramos,
        String minimoImponible,
        String impuestoInsoluto,
        String derechoDeEmision,
        String totalAPagar,
        String modalidad,
        List<CuotaDeterminada> cuotas,
        List<String> reglasAplicadas) {

    public DeterminacionPredialResource {
        Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
        predios = List.copyOf(predios);
        tramos = List.copyOf(tramos);
        cuotas = List.copyOf(cuotas);
        reglasAplicadas = List.copyOf(reglasAplicadas);
    }

    public static DeterminacionPredialResource de(DeterminacionPredialCalculada calculada) {
        List<PredioDeLaBase> predios = new ArrayList<>();
        for (PredioEnLaBase predio : calculada.predios()) {
            predios.add(
                    new PredioDeLaBase(
                            predio.predioId(),
                            predio.codigoReferenciaCatastral(),
                            predio.direccion(),
                            predio.uso(),
                            predio.porcentajePropiedad().valor().toPlainString(),
                            predio.autovaluo().toString(),
                            predio.valuoExonerado().toString(),
                            predio.valuoAfecto().toString(),
                            predio.baseImponiblePredio().toString(),
                            predio.porcentajeRegistradoDelPredio().valor().toPlainString(),
                            predio.titularidadCompleta()));
        }
        List<TramoAplicado> tramos = new ArrayList<>();
        for (AporteDeTramo aporte : calculada.tramos()) {
            tramos.add(
                    new TramoAplicado(
                            aporte.orden(),
                            aporte.tieneTope()
                                    ? Objects.requireNonNull(aporte.limiteSuperior()).toString()
                                    : null,
                            aporte.alicuota().valor().toPlainString(),
                            aporte.porcionGravada().toString(),
                            aporte.aporte().toString()));
        }
        List<CuotaDeterminada> cuotas = new ArrayList<>();
        for (CuotaDelPredial cuota : calculada.cuotas()) {
            cuotas.add(
                    new CuotaDeterminada(
                            cuota.numero(),
                            cuota.vencimiento().toString(),
                            cuota.importe().toString()));
        }
        Long id = calculada.cabecera().id();
        return new DeterminacionPredialResource(
                id == null ? 0L : id,
                calculada.esSimulacion(),
                calculada.cabecera().ejercicio().toString(),
                calculada.codContribuyente(),
                calculada.sujeto(),
                calculada.cabecera().conjuntoId(),
                calculada.nombreDelConjunto(),
                calculada.fechaCalculo().toString(),
                predios,
                calculada.valuoTotal().toString(),
                calculada.valuoExonerado().toString(),
                calculada.valuoAfecto().toString(),
                calculada.cabecera().baseImponible().toString(),
                calculada.uit().toString(),
                tramos,
                calculada.minimoImponible().toString(),
                calculada.impuestoInsoluto().toString(),
                calculada.derechoDeEmision().toString(),
                calculada.totalAPagar().toString(),
                calculada.modalidad(),
                cuotas,
                calculada.cabecera().reglasAplicadas());
    }

    /**
     * Un predio dentro de la base.
     *
     * @param baseImponible lo que este predio puso, ya ponderado por el % de propiedad. Es la cifra
     *     que RNF-083 prohibe recomponer: no es el valuo afecto, es el valuo afecto por la cuota
     * @param porcentajeRegistradoDelPredio lo que suman <b>todas</b> las cuotas del predio a la
     *     fecha de calculo (#690). No es {@code porcentajePropiedad}: aquel es la parte de este
     *     contribuyente, este es cuanto del predio tiene dueño registrado
     * @param titularidadCompleta si esa suma llega a 100. Viaja <b>derivado y no derivable</b> a
     *     proposito: la comparacion es de una cifra decimal contra 100 y hacerla en la pantalla es
     *     invitar a que 99,9999 se lea como completo. Cuando es {@code false}, la base de este
     *     predio esta ponderada por una titularidad que no cubre el predio entero — la
     *     determinacion es correcta para lo registrado, y lo que no puede pasar es que salga sin
     *     que nada la acompañe
     */
    public record PredioDeLaBase(
            long predioId,
            String codigoPredial,
            String ubicacion,
            @Nullable String uso,
            String porcentajePropiedad,
            String autovaluo,
            String valuoExonerado,
            String valuoAfecto,
            String baseImponible,
            String porcentajeRegistradoDelPredio,
            boolean titularidadCompleta) {}

    /**
     * Un tramo del articulo 13 y lo que aporto.
     *
     * @param limiteSuperior hasta cuanto llega, en soles; nulo en el ultimo, que no tiene tope
     * @param aporte lo que puso, <b>sin redondear</b>: el redondeo es del impuesto, una sola vez
     *     (ADR-0018), asi que la suma de los aportes puede diferir de {@link #impuestoInsoluto} en
     *     un centimo. La cifra que se cobra es la del impuesto
     */
    public record TramoAplicado(
            int orden,
            @Nullable String limiteSuperior,
            String alicuota,
            String porcionGravada,
            String aporte) {}

    /** Una cuota del cronograma. */
    public record CuotaDeterminada(int numero, String vencimiento, String importe) {}
}
