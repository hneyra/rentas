package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeLaDeterminacionPredial;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import org.jspecify.annotations.Nullable;

/**
 * La determinación predial <b>guardada</b> de un contribuyente, tal como sale por {@code GET
 * /api/v1/rentas/predial/determinaciones} (#207).
 *
 * <h2>Por qué es otro recurso y no {@link DeterminacionPredialResource}</h2>
 *
 * <p>Porque no puede afirmar lo mismo, y publicarlo con la misma forma sería publicar dos campos
 * nulos con el contrato de la escritura de al lado — el modo de fallo que #194 midió. Es el mismo
 * reparto que el área predial ya tiene entre {@link CorridaPredialResource} —la corrida que se
 * acaba de ejecutar— y {@link CorridaGuardadaResource} —la que se lee después—.
 *
 * <p>Lo que <b>no</b> lleva: {@code simulacion}, porque una simulación no deja fila que leer, así
 * que aquí siempre sería {@code false}.
 *
 * <p>Lo que sí lleva desde #234 son {@code modalidad} y {@code cuotas[]}, y <b>no salen de la misma
 * clase de sitio que el resto</b>: la modalidad es una columna de la fila —{@code V20}—, y las
 * cuotas se <b>derivan</b> de ella, del monto guardado y de los vencimientos de ese conjunto
 * sellado. Guardarlas sería una segunda verdad sobre el mismo hecho. Cuando la fila es anterior a
 * V20 no dice su modalidad, y entonces los dos salen en blanco —{@code null} y {@code []}— en vez
 * del trimestral supuesto: es el trato que {@code V10} le dio a {@code pago_recibido}.
 *
 * <p>Lo que sí lleva y no está guardado —{@link #uit}, {@link #tramos}, {@link #minimoImponible} y
 * {@link #derechoDeEmision}— sale del <b>conjunto sellado que esa determinación fijó</b>, no del
 * vigente de hoy. Es lo que ARQ-09 §3 promete, y por eso no se guarda dos veces.
 *
 * <p>Los importes viajan como texto, igual que en {@link DeterminacionPredialResource}: son la
 * cifra que se dibuja, no un {@code Dinero} con el que operar. Y ninguna se recompone en la
 * interfaz (RNF-083): la base de cada predio ya viene ponderada, y sumar autovalúos para
 * adelantarla daría una cifra parecida y equivocada.
 *
 * @param id el identificador de la determinación guardada
 * @param ejercicio el ejercicio determinado
 * @param codContribuyente el código del contribuyente en el padrón
 * @param sujeto de quién es, ya redactado para leerse
 * @param conjuntoId el conjunto de parámetros sellado con que se calculó
 * @param conjunto cómo se nombra ese conjunto: «2026 v1»
 * @param estado en qué situación está la determinación
 * @param origen de dónde salió
 * @param predios lo que puso cada predio en la base
 * @param valuoTotal la suma de los autovalúos, sin ponderar
 * @param valuoExonerado la parte exonerada, sin ponderar
 * @param valuoAfecto lo que queda afecto, sin ponderar
 * @param baseImponible la base del contribuyente, ya ponderada por el % de propiedad de cada predio
 * @param uit la UIT del conjunto sellado
 * @param tramos qué aportó cada tramo del artículo 13
 * @param minimoImponible el mínimo del conjunto sellado (RT-014)
 * @param impuestoInsoluto el impuesto anual determinado, que es lo que la fila guarda
 * @param derechoDeEmision el derecho de emisión mecanizada del conjunto sellado
 * @param totalAPagar el impuesto más el derecho de emisión
 * @param reglasAplicadas los identificadores de las reglas que produjeron el monto, en orden
 */
public record DeterminacionGuardadaResource(
        long id,
        String ejercicio,
        String codContribuyente,
        String sujeto,
        long conjuntoId,
        String conjunto,
        String estado,
        String origen,
        List<PredioGuardado> predios,
        String valuoTotal,
        String valuoExonerado,
        String valuoAfecto,
        String baseImponible,
        String uit,
        List<DeterminacionPredialResource.TramoAplicado> tramos,
        String minimoImponible,
        String impuestoInsoluto,
        String derechoDeEmision,
        String totalAPagar,
        @Nullable String modalidad,
        List<DeterminacionPredialResource.CuotaDeterminada> cuotas,
        List<String> reglasAplicadas) {

    public DeterminacionGuardadaResource {
        Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
        predios = List.copyOf(predios);
        tramos = List.copyOf(tramos);
        cuotas = List.copyOf(cuotas);
        reglasAplicadas = List.copyOf(reglasAplicadas);
    }

    public static DeterminacionGuardadaResource de(
            ConsultaDeLaDeterminacionPredial.Guardada leida) {
        List<PredioGuardado> predios = new ArrayList<>();
        for (DetalleDeterminacionPredio predio : leida.predios()) {
            predios.add(
                    new PredioGuardado(
                            predio.predioId(),
                            predio.autovaluo().toString(),
                            predio.valuoExonerado().toString(),
                            predio.autovaluo().menos(predio.valuoExonerado()).toString(),
                            predio.porcentajePropiedad().valor().toPlainString(),
                            predio.baseImponiblePredio().toString(),
                            predio.origen().name(),
                            predio.valuacionConjuntoId(),
                            predio.valuacionHuella()));
        }
        List<DeterminacionPredialResource.TramoAplicado> tramos = new ArrayList<>();
        for (AporteDeTramo aporte : leida.tramos()) {
            tramos.add(
                    new DeterminacionPredialResource.TramoAplicado(
                            aporte.orden(),
                            aporte.tieneTope()
                                    ? Objects.requireNonNull(aporte.limiteSuperior()).toString()
                                    : null,
                            aporte.alicuota().valor().toPlainString(),
                            aporte.porcionGravada().toString(),
                            aporte.aporte().toString()));
        }
        List<DeterminacionPredialResource.CuotaDeterminada> cuotas = new ArrayList<>();
        for (CuotaDelPredial cuota : leida.cuotas()) {
            cuotas.add(
                    new DeterminacionPredialResource.CuotaDeterminada(
                            cuota.numero(),
                            cuota.vencimiento().toString(),
                            cuota.importe().toString()));
        }
        Long id = leida.cabecera().id();
        return new DeterminacionGuardadaResource(
                id == null ? 0L : id,
                leida.cabecera().ejercicio().toString(),
                leida.codContribuyente(),
                leida.sujeto(),
                leida.cabecera().conjuntoId(),
                leida.nombreDelConjunto(),
                leida.cabecera().estado().name(),
                leida.cabecera().origen().name(),
                predios,
                leida.valuoTotal().toString(),
                leida.valuoExonerado().toString(),
                leida.valuoAfecto().toString(),
                leida.cabecera().baseImponible().toString(),
                leida.uit().toString(),
                tramos,
                leida.minimoImponible().toString(),
                leida.impuestoInsoluto().toString(),
                leida.derechoDeEmision().toString(),
                leida.totalAPagar().toString(),
                leida.modalidad() == null ? null : leida.modalidad().name(),
                cuotas,
                leida.cabecera().reglasAplicadas());
    }

    /**
     * Un predio dentro de la base, tal como quedó guardado.
     *
     * <p>No lleva el código catastral ni la dirección ni el uso, al revés que {@link
     * DeterminacionPredialResource.PredioDeLaBase}: esos tres son de {@code catastro} y se
     * resuelven <b>a una fecha</b>. Publicarlos aquí sería publicar los de hoy dentro de una
     * determinación de hace dos años, y ni esta lectura ni la fila guardada pueden afirmar que sean
     * los de entonces. Por lo mismo faltan {@code porcentajeRegistradoDelPredio} y {@code
     * titularidadCompleta}, que son un hecho del padrón a la fecha de cálculo y no una columna de
     * la determinación.
     *
     * @param baseImponible lo que este predio puso, ya ponderado por el % de propiedad
     * @param origenDelAutovaluo si el autovalúo lo fijó una declaración jurada o salió de la
     *     valuación que {@code catastro} selló (ADR-0027)
     * @param valuacionConjuntoId el conjunto que fijó la corrida de valuación; nulo si el autovalúo
     *     es declarado
     * @param valuacionHuella la huella con que {@code catastro} selló esa valuación; nulo si el
     *     autovalúo es declarado
     */
    public record PredioGuardado(
            long predioId,
            String autovaluo,
            String valuoExonerado,
            String valuoAfecto,
            String porcentajePropiedad,
            String baseImponible,
            String origenDelAutovaluo,
            @Nullable Long valuacionConjuntoId,
            @Nullable String valuacionHuella) {}
}
