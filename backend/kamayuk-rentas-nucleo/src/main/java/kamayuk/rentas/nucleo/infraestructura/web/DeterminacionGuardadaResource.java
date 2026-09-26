package kamayuk.rentas.nucleo.infraestructura.web;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
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
 * clase de sitio que el resto</b>: la modalidad es una columna de la fila —{@code V21}—, y las
 * cuotas se <b>derivan</b> de ella, del monto guardado y de los vencimientos de ese conjunto
 * sellado. Guardarlas sería una segunda verdad sobre el mismo hecho. Cuando la fila es anterior a
 * V21 no dice su modalidad, y entonces los dos salen en blanco —{@code null} y {@code []}— en vez
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
 * <p><b>Los que salen del conjunto sellado se publican con dos decimales, y por un solo sitio</b>
 * (#354): {@link #importeDeCierre(Dinero)}. La copia local de {@code normativa} entrega cada número
 * como {@code numeric(18,6)} —la UIT llega {@code "5500.000000"}—, {@code Dinero.por} no redondea
 * por diseño —el mínimo salía con catorce decimales, los límites de tramo con doce— y {@code
 * toString()} lo publicaba tal cual. La interfaz exige dos como mucho y revienta con más, con
 * razón. El aporte de cada tramo <b>no</b> pasa por ahí: es un intermedio sin redondear (ADR-0018,
 * #245).
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
                            predio.valuacionHuella(),
                            predio.autovaluoDeclarado() == null
                                    ? null
                                    : predio.autovaluoDeclarado().toString()));
        }
        List<DeterminacionPredialResource.TramoAplicado> tramos = new ArrayList<>();
        for (AporteDeTramo aporte : leida.tramos()) {
            tramos.add(
                    new DeterminacionPredialResource.TramoAplicado(
                            aporte.orden(),
                            aporte.tieneTope()
                                    ? importeDeCierre(
                                            Objects.requireNonNull(aporte.limiteSuperior()))
                                    : null,
                            aporte.alicuota().valor().toPlainString(),
                            importeDeCierre(aporte.porcionGravada()),
                            // Entero, y a proposito: ver el javadoc de la clase.
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
                importeDeCierre(leida.uit()),
                tramos,
                importeDeCierre(leida.minimoImponible()),
                leida.impuestoInsoluto().toString(),
                importeDeCierre(leida.derechoDeEmision()),
                importeDeCierre(leida.totalAPagar()),
                leida.modalidad() == null ? null : leida.modalidad().name(),
                cuotas,
                leida.cabecera().reglasAplicadas());
    }

    /**
     * La escala con que el contrato publica un importe de cierre: dos decimales, que es la forma
     * que la interfaz admite ({@code formatearImporte}, {@code IMPORTE_SERVIDO}). No es un
     * parámetro tributario ni una política de redondeo: es la forma del texto en el borde del
     * contrato.
     */
    private static final int ESCALA_DEL_CONTRATO = 2;

    /**
     * <b>Un importe de cierre con la escala del contrato</b> (#354): la única fuente de verdad de
     * cómo se escribe {@code uit}, {@code minimoImponible}, {@code limiteSuperior}, {@code
     * porcionGravada}, {@code derechoDeEmision} y {@code totalAPagar}.
     *
     * <p><b>No redondea</b>: {@link RoundingMode#UNNECESSARY} lanza si hubiera un decimal
     * significativo más allá del segundo. {@code 5500.000000} pasa a {@code 5500.00} y {@code
     * 33.00000000000000} a {@code 33.00}, porque el 0,6 % de una UIT múltiplo de 50 es exacto. Si
     * un día un conjunto sellado produjera un céntimo partido, esta lectura fallaría en vez de
     * publicar una cifra redondeada aquí que nadie decidió redondear (D-03, ADR-0018): recortarla
     * en el borde sería la aritmética sobre dinero que la interfaz se niega a hacer, hecha un paso
     * antes.
     */
    private static String importeDeCierre(Dinero importe) {
        return importe.valor()
                .setScale(ESCALA_DEL_CONTRATO, RoundingMode.UNNECESSARY)
                .toPlainString();
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
     * @param autovaluoDeclarado lo que declaró el contribuyente cuando el autovalúo es el sellado
     *     (#362, V33); nulo cuando no hay dos cifras que comparar —el autovalúo es el declarado,
     *     nadie declaró, o la fila es anterior a V33 y no lo guardó—
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
            @Nullable String valuacionHuella,
            @Nullable String autovaluoDeclarado) {}
}
