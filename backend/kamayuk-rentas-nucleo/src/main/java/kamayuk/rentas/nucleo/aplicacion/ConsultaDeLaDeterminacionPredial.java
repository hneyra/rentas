package kamayuk.rentas.nucleo.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
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
 * <h2>Lo que esta lectura NO publica, y por qué</h2>
 *
 * <p><b>La modalidad y el cronograma de cuotas.</b> {@code determinacion} no guarda la modalidad
 * —sólo la guarda {@code corrida_predial}, que es de la emisión masiva—, y sin ella {@code
 * Vigente#vencimientos} no se puede llamar: las cuatro fechas dependen de si el contribuyente pagó
 * al contado o en cuatro trimestres. Suponer {@code TRIMESTRAL} —que es lo que hace el {@code POST}
 * cuando el cuerpo no la dice— publicaría un cronograma que puede no ser el que el contribuyente
 * recibió, y ése es justo el modo de fallo que la regla 5 prohíbe. Está nombrado y no resuelto:
 * {@code rentas}#234.
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
                cabecera.montoDeterminado().mas(derechoDeEmision));
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
     * <p>No es {@code DeterminacionPredialCalculada}: aquella lleva el cronograma y la modalidad
     * —que son entradas del {@code POST} y no filas guardadas— y el {@code simulacion}, que aquí no
     * tiene sentido porque una simulación no deja fila que leer. Tener dos tipos y no uno es lo que
     * impide que esta lectura publique un campo nulo con el mismo contrato que la escritura, que es
     * el modo de fallo que #194 midió.
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
            Dinero totalAPagar) {

        public Guardada {
            predios = List.copyOf(predios);
            tramos = List.copyOf(tramos);
        }

        /** El impuesto anual determinado, que es lo que la cabecera guarda. */
        public Dinero impuestoInsoluto() {
            return cabecera.montoDeterminado();
        }
    }
}
