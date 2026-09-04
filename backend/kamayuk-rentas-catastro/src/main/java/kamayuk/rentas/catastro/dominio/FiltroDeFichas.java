package kamayuk.rentas.catastro.dominio;

import java.util.Optional;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import org.jspecify.annotations.Nullable;

/**
 * Los filtros de la consulta transversal de fichas (RF-006) y del resumen predial (RF-046).
 *
 * <p>Son exactamente los que declaran las pantallas en el contrato: codigo de referencia catastral,
 * titular, manzana, lote, tipo de ficha y uso. No hay aqui un «buscar cualquier cosa»: cada filtro
 * apunta a una columna, y una consulta que acepta texto libre acaba haciendo {@code LIKE '%…%'}
 * sobre todo el padron.
 *
 * <p><b>{@code uso} lo trajo {@code consulta_resumen_predial} (#25).</b> Encaja sin violentar nada
 * —{@code ficha_catastral.uso} es una columna, y el desplegable de la pantalla ofrece una lista
 * cerrada—, asi que se anade en vez de inventarle una consulta propia a esa pantalla. Lo que
 * <b>no</b> se anade es el filtro «Palabra» que la misma pantalla declara: eso es exactamente el
 * texto libre que el parrafo anterior descarta, y {@code ResumenPredialController} lo rechaza con
 * 422 en lugar de traducirlo a un {@code LIKE} sobre el padron entero.
 *
 * <p>{@code contribuyente} es distinto de los otros tres: no es una columna de catastro. Se
 * resuelve preguntandole al padron, y por aproximacion —el nombre llega mal escrito desde
 * ventanilla mas a menudo que bien—.
 *
 * <p><b>Lo que no esta y por que.</b> El contrato declara tambien {@code conciliadaConRentas}, que
 * compara el catastro con la declaracion jurada del padron de rentas. Responderlo leyendo su tabla
 * desde aqui seria el acoplamiento que ARQ-01 §4 evita, con el agravante de ser invisible para
 * Spring Modulith, asi que <b>sigue sin estar en este filtro</b>: desde #344 lo sirve {@code
 * rentas}, que compone las dos mitades por sus APIs publicas (ADR-0015 §2), y {@code
 * ConsultaController} redirige alli la peticion que lo trae.
 */
public record FiltroDeFichas(
        @Nullable String codRefCatastral,
        @Nullable String contribuyente,
        @Nullable String manzana,
        @Nullable String lote,
        @Nullable TipoFicha tipo,
        @Nullable String uso,
        AcotacionPorPredio acotacion) {

    /** Los seis filtros de siempre, sin acotar por predio. */
    public FiltroDeFichas(
            @Nullable String codRefCatastral,
            @Nullable String contribuyente,
            @Nullable String manzana,
            @Nullable String lote,
            @Nullable TipoFicha tipo,
            @Nullable String uso) {
        this(
                codRefCatastral,
                contribuyente,
                manzana,
                lote,
                tipo,
                uso,
                AcotacionPorPredio.ninguna());
    }

    public FiltroDeFichas {
        codRefCatastral = limpio(codRefCatastral);
        contribuyente = limpio(contribuyente);
        manzana = limpio(manzana);
        lote = limpio(lote);
        uso = limpio(uso);
    }

    /** Los cinco filtros de {@code consulta_fichas}, que no declara «Uso». */
    public FiltroDeFichas(
            @Nullable String codRefCatastral,
            @Nullable String contribuyente,
            @Nullable String manzana,
            @Nullable String lote,
            @Nullable TipoFicha tipo) {
        this(codRefCatastral, contribuyente, manzana, lote, tipo, null);
    }

    public static FiltroDeFichas ninguno() {
        return new FiltroDeFichas(null, null, null, null, null, null);
    }

    /** El mismo filtro, acotado a un conjunto de predios (#631). */
    public FiltroDeFichas acotadoA(AcotacionPorPredio otra) {
        return new FiltroDeFichas(codRefCatastral, contribuyente, manzana, lote, tipo, uso, otra);
    }

    public Optional<String> porContribuyente() {
        return Optional.ofNullable(contribuyente);
    }

    /**
     * Si no filtra por nada de lo que el usuario teclea. Una consulta asi devuelve el padron
     * paginado, que es legitimo.
     *
     * <p>La acotacion por predio <b>no cuenta</b>: no la teclea nadie, la pone el contexto que
     * compone la consulta (#631).
     */
    public boolean estaVacio() {
        return codRefCatastral == null
                && contribuyente == null
                && manzana == null
                && lote == null
                && tipo == null
                && uso == null;
    }

    private static @Nullable String limpio(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
