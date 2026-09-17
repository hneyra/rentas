package kamayuk.rentas.nucleo.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CronogramaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La última determinación predial <b>guardada</b> de un contribuyente, leída (#207).
 *
 * <h2>Por qué existía el hueco</h2>
 *
 * <p>{@code territorio} —la hoja de la Determinación— era la única de las cuarenta que declaraba
 * siete operaciones y <b>ni una lectura</b>: las siete escriben, y la que se le atribuía —{@code
 * POST /rentas/predial/calculo-individual}— dispara el cálculo, no lo consulta. Abrir una pantalla
 * no puede determinar de oficio a nadie ni asentar nada en su cuenta corriente, así que la hoja no
 * tenía con qué dibujarse. Y el {@code POST} con {@code simulacion=false} inserta una fila <b>cada
 * vez que se le llama</b>: usarlo «para ver» duplicaría determinaciones.
 *
 * <h2>Qué afirma esta lectura, y de dónde sale cada cosa</h2>
 *
 * <p>Dos fuentes, y ninguna más. De las <b>filas guardadas</b> salen la cabecera ({@code
 * determinacion}: base imponible, monto determinado, reglas aplicadas, conjunto, estado y origen) y
 * el detalle por predio ({@code determinacion_predio_detalle}: autovalúo, valúo exonerado,
 * porcentaje de propiedad y lo que ese predio puso en la base). Del <b>conjunto sellado que esa
 * determinación fijó</b> —por su {@code conjunto_id}, con {@link
 * CuadroPredialParametrizado#delConjunto} y no con {@code vigenteEn}— salen la UIT, los tramos del
 * artículo 13, el mínimo imponible y el derecho de emisión.
 *
 * <p>Recomputar esos cuatro no es inventarlos: es lo que ARQ-09 §3 y la regla 6 prometen —la misma
 * base contra el mismo conjunto sellado da el mismo céntimo dentro de diez años—, y por eso no se
 * guardan dos veces. Resolverlos con {@code vigenteEn} sí sería otra cosa: si se sellara una
 * segunda versión del ejercicio, esta lectura publicaría unos tramos que la determinación nunca
 * usó.
 *
 * <h2>El cronograma: derivado del conjunto sellado, nunca supuesto (#234)</h2>
 *
 * <p>Desde {@code V20}, {@code determinacion} guarda <b>la modalidad</b>, y con ella el cronograma
 * vuelve a ser derivable: los vencimientos son las llaves {@code PREDIAL_VENCIMIENTO} de ese mismo
 * conjunto sellado y el importe es el reparto del monto que la fila ya guarda ({@link
 * CronogramaDelPredial}). Las cuotas <b>no</b> se guardan, y es deliberado: serían una segunda
 * verdad sobre el mismo hecho, que es lo que #214 retiró del acta de fiscalización.
 *
 * <p><b>Una fila anterior a V20 publica el cronograma en blanco</b> —{@code modalidad} nula y
 * {@code cuotas} vacía—, nunca el trimestral supuesto. De aquellas determinaciones la modalidad no
 * consta en ningún sitio: suponerla publicaría un cronograma que puede no ser el que el
 * contribuyente recibió, y ése es justo el modo de fallo que la regla 5 prohíbe.
 *
 * <h2>Lo que esta lectura NO publica, y por qué</h2>
 *
 * <p><b>El «Monto deducido» y la «Situación» de cada cuota</b>, que la hoja también dibuja: el
 * primero no lo publica nadie —lo más cercano es {@code valuoExonerado}, que no es un monto
 * deducido en soles— y el segundo es un hecho de <b>cuenta corriente</b>, no del cálculo. Los dos
 * quedan en «no publicado» con su motivo, que es lo que #207 dejó previsto.
 *
 * <h2>Dos vacíos que no se pueden confundir</h2>
 *
 * <p>Un código que no está en el padrón no es lo mismo que un contribuyente sin determinación de
 * ese ejercicio: lo primero es que la pregunta no tiene sujeto, lo segundo es que la respuesta es
 * «todavía no». Aquí el primero lanza {@link DeterminarPredial.ContribuyenteInexistente} —404
 * nombrándolo— y el segundo devuelve {@link Optional#empty()}, que el controlador convierte en
 * <b>204</b>, igual que {@code GET /rentas/predial/corridas/ultima}. Es la lección de #546.
 */
@Service
public class ConsultaDeLaDeterminacionPredial {

    private final DirectorioDeContribuyentes directorio;
    private final DeterminacionRepository determinaciones;
    private final CuadroPredialParametrizado cuadro;

    public ConsultaDeLaDeterminacionPredial(
            DirectorioDeContribuyentes directorio,
            DeterminacionRepository determinaciones,
            CuadroPredialParametrizado cuadro) {
        this.directorio = directorio;
        this.determinaciones = determinaciones;
        this.cuadro = cuadro;
    }

    /**
     * La última determinación predial guardada de ese contribuyente para ese ejercicio.
     *
     * @throws DeterminarPredial.ContribuyenteInexistente si el código no está en el padrón
     * @throws kamayuk.rentas.parametros.LectorDeParametros.ConjuntoNoSellado si el conjunto que la
     *     determinación referencia ya no está sellado — una determinación emitida sin sellar no se
     *     puede reproducir, y publicarla con los parámetros de hoy sería publicar otra cifra
     */
    @Transactional(readOnly = true)
    public Optional<Guardada> ultimaDe(String codContribuyente, Ejercicio ejercicio) {
        ResumenDeContribuyente contribuyente =
                directorio
                        .porCodigo(codContribuyente.strip())
                        .orElseThrow(
                                () ->
                                        new DeterminarPredial.ContribuyenteInexistente(
                                                codContribuyente));

        return determinaciones
                .ultimaPredialDe(ejercicio, contribuyente.id())
                .map(cabecera -> componer(cabecera, contribuyente, ejercicio));
    }

    // ------------------------------------------------------------------

    private Guardada componer(
            Determinacion cabecera, ResumenDeContribuyente contribuyente, Ejercicio ejercicio) {

        List<DetalleDeterminacionPredio> detalle =
                determinaciones.detalleDe(identificadorDe(cabecera));
        CuadroPredialParametrizado.Vigente sellado =
                cuadro.delConjunto(ejercicio, cabecera.conjuntoId());

        Dinero valuoTotal = Dinero.CERO;
        Dinero valuoExonerado = Dinero.CERO;
        for (DetalleDeterminacionPredio predio : detalle) {
            valuoTotal = valuoTotal.mas(predio.autovaluo());
            valuoExonerado = valuoExonerado.mas(predio.valuoExonerado());
        }

        List<AporteDeTramo> tramos =
                TramosProgresivosAcumulativos.desglosar(cabecera.baseImponible(), sellado.tramos());
        Dinero derechoDeEmision = sellado.derechoDeEmision();

        // El cronograma solo se resuelve si la fila DICE con que modalidad se emitio. Sin ella no
        // se llama a `vencimientos`: llamarla con la trimestral por omision publicaria cuatro
        // fechas que esta lectura no puede afirmar (#234).
        ModalidadDelPredial modalidad = cabecera.modalidad();
        List<CuotaDelPredial> cuotas =
                modalidad == null
                        ? List.of()
                        : CronogramaDelPredial.repartir(
                                cabecera.montoDeterminado(),
                                sellado.vencimientos(modalidad),
                                sellado.redondeo());

        return new Guardada(
                cabecera,
                detalle,
                contribuyente.codigo(),
                contribuyente.nombre(),
                sellado.nombreDelConjunto(),
                valuoTotal,
                valuoExonerado,
                valuoTotal.menos(valuoExonerado),
                sellado.uit(),
                tramos,
                sellado.minimoImponible(),
                derechoDeEmision,
                cabecera.montoDeterminado().mas(derechoDeEmision),
                modalidad,
                cuotas);
    }

    private static long identificadorDe(Determinacion cabecera) {
        Long id = cabecera.id();
        if (id == null) {
            throw new IllegalStateException(
                    "La determinacion leida de la base no trae identificador");
        }
        return id;
    }

    /**
     * Una determinación predial guardada, con lo que su conjunto sellado determina.
     *
     * <p>No es {@code DeterminacionPredialCalculada}: aquella lleva el {@code simulacion}, que aquí
     * no tiene sentido porque una simulación no deja fila que leer, y publica la memoria del
     * cálculo —el código catastral, la dirección y el uso de cada predio— que se resuelve <b>a una
     * fecha</b> y que esta lectura no puede afirmar años después. Tener dos tipos y no uno es lo
     * que impide que esta lectura publique un campo nulo con el mismo contrato que la escritura,
     * que es el modo de fallo que #194 midió.
     *
     * @param cabecera la fila de {@code determinacion}
     * @param predios lo que puso cada predio, de {@code determinacion_predio_detalle}
     * @param codContribuyente el código del contribuyente en el padrón
     * @param sujeto de quién es, ya redactado para leerse
     * @param nombreDelConjunto cómo se nombra el conjunto sellado: «2026 v1»
     * @param valuoTotal la suma de los autovalúos, sin ponderar
     * @param valuoExonerado la parte exonerada, sin ponderar
     * @param valuoAfecto lo que queda afecto, sin ponderar
     * @param uit la UIT de ese conjunto, con la que se convierten los límites de los tramos
     * @param tramos qué aportó cada tramo del artículo 13, desglosado otra vez sobre la misma base
     * @param minimoImponible el mínimo de ese conjunto (RT-014)
     * @param derechoDeEmision el derecho de emisión mecanizada de ese conjunto
     * @param totalAPagar el impuesto determinado más el derecho de emisión
     * @param modalidad bajo qué cronograma se emitió (V20, #234); <b>nulo</b> si la fila es
     *     anterior a la migración, donde significa «no consta» y nunca «al contado»
     * @param cuotas el cronograma derivado del conjunto sellado; <b>vacío</b> cuando la fila no
     *     dice su modalidad, porque entonces no hay cronograma que afirmar
     */
    public record Guardada(
            Determinacion cabecera,
            List<DetalleDeterminacionPredio> predios,
            String codContribuyente,
            String sujeto,
            String nombreDelConjunto,
            Dinero valuoTotal,
            Dinero valuoExonerado,
            Dinero valuoAfecto,
            Dinero uit,
            List<AporteDeTramo> tramos,
            Dinero minimoImponible,
            Dinero derechoDeEmision,
            Dinero totalAPagar,
            @Nullable ModalidadDelPredial modalidad,
            List<CuotaDelPredial> cuotas) {

        public Guardada {
            predios = List.copyOf(predios);
            tramos = List.copyOf(tramos);
            cuotas = List.copyOf(cuotas);
            if (modalidad == null && !cuotas.isEmpty()) {
                throw new IllegalArgumentException(
                        "Un cronograma sin modalidad no se puede afirmar: una fila anterior a V20"
                                + " publica las cuotas en blanco, nunca las trimestrales supuestas"
                                + " (#234)");
            }
        }

        /** El impuesto anual determinado, que es lo que la cabecera guarda. */
        public Dinero impuestoInsoluto() {
            return cabecera.montoDeterminado();
        }
    }
}
