package kamayuk.rentas.valores;

import java.util.Optional;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;

/**
 * Si hay un valor <b>vivo</b> que formaliza una obligacion del libro (#372).
 *
 * <p>Es la API publica con que otro contexto pregunta «¿esta deuda ya esta en un titulo de cobro
 * que sigue corriendo?». Vive en el paquete raiz, junto a {@link ValoresDelContribuyente}, por el
 * mismo motivo que las demas: Spring Modulith trata como interno todo lo que esta en un subpaquete.
 * <b>Esto es lo que se ve de los valores. Sus tablas, no.</b>
 *
 * <h2>Por que existe: la pregunta es de {@code valores}, y se hacia en otro sitio</h2>
 *
 * <p>Hasta #372 {@code sanciones.AnularPapeleta} respondia a esta pregunta con una copia propia: la
 * fila {@code GENERADO} de {@code papeleta_masivo_item}, que solo deja la corrida de valores por
 * papeletas. Pero un valor sobre la deuda de una papeleta nace tambien por la emision individual
 * ({@code POST /api/v1/valores}, que admite OP, RD o RM sobre cualquier tributo) y por la masiva de
 * valores, y ninguno de los dos deja esa fila: una RM emitida desde la ventanilla de valores no
 * impedia anular la papeleta, y la baja se llevaba la deuda que esa RM estaba cobrando. Quien sabe
 * todos los caminos por los que nace un valor es este modulo, y la respuesta sale de lo que todos
 * ellos escriben: {@code valor_detalle}.
 *
 * <h2>Que es «vivo»</h2>
 *
 * <p>Que no este {@code PAGADO}, {@code ANULADO} ni {@code PRESCRITO}: la misma frontera que la
 * consulta de valores llama «terminal» y con la que {@code SituacionDelValor} deja de describir una
 * cobranza en curso. {@code COACTIVA} <b>si</b> esta vivo —es justo el que mas pesa—. Y la
 * distincion no es adorno: un valor que existio y se anulo ya no sostiene nada, y confundir «hay un
 * valor vivo» con «hubo un valor» bloquearia para siempre un acto legitimo.
 *
 * <h2>Por obligacion, no por papeleta</h2>
 *
 * <p>La clave es la del libro —obligado, tributo, ejercicio y unidad—, que es lo que un valor
 * formaliza. Mientras dos papeletas del mismo obligado puedan compartir obligacion (#465), un valor
 * vivo de una de ellas contesta tambien por la otra. Es el lado conservador: la alternativa seria
 * dar por libre una deuda que un titulo esta cobrando.
 */
public interface ValoresSobreUnaObligacion {

    /**
     * El numero impreso del valor vivo que formaliza esa obligacion de ese contribuyente, si lo
     * hay.
     *
     * <p>De cualquier tipo —OP, RD o RM— y emitido por cualquier camino: individual, masivo o por
     * la corrida de papeletas. Si hubiera mas de uno, el primero que se emitio; basta uno para
     * contestar que la deuda esta formalizada, y el numero es lo que quien pregunta nombra al
     * rechazar.
     *
     * @param contribuyenteId a quien se le emitio
     * @param obligacion la obligacion del libro, con la misma clave con que {@code cuentacorriente}
     *     la asienta
     * @return el numero, o vacio si ningun valor vivo la formaliza
     */
    Optional<String> vivoSobre(long contribuyenteId, SeleccionDeObligacion obligacion);
}
