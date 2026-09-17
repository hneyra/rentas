package kamayuk.rentas.fiscalizacion.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros con los que se busca en la relación de resoluciones de determinación (#192, RF-057).
 *
 * <p>Ninguno recibe la municipalidad (regla 2): la pone la política RLS.
 *
 * <h2>Es uno, y los otros dos que se pensaron no existen</h2>
 *
 * <p>La pantalla {@code resolucion_determinacion_fisc} no dibuja <b>ningún</b> filtro: sus dos
 * desplegables —«Ejercicios alcanzados» y «Artículo del Código Tributario»— son decisiones de la
 * <b>emisión</b>, o sea del {@code POST} que liquida, y no criterios de búsqueda. Así que aquí no
 * hay ninguno que derivar del prototipo, y declarar de más sería publicar promesas que ninguna
 * pantalla hace — lo que #431, #432 y #544 tuvieron que retirar.
 *
 * <p>El que hay está porque el caso de uso ya lo servía —{@code ConsultaDeResoluciones
 * .deContribuyente}, escrito y sin ningún controlador que lo expusiera— y porque es el que hace
 * utilizable el resto del módulo: la pantalla del estado de cuenta de fiscalización ya pregunta por
 * contribuyente, y llegar desde ahí a la resolución que se le dictó es el camino que faltaba.
 *
 * <p>Los otros dos que se midieron y <b>no</b> se declaran:
 *
 * <ul>
 *   <li><b>Estado</b>: una resolución de determinación no tiene ninguno. {@code
 *       resolucion_determinacion} no admite {@code UPDATE} desde V49 —una resolución equivocada se
 *       deja sin efecto con otro acto— y no hay tabla de movimientos de la que derivarlo, al
 *       contrario que la liquidación ({@link EstadoDeLiquidacion}). Filtrar por un estado que nadie
 *       registra devolvería siempre lo mismo o siempre nada.
 *   <li><b>Ejercicio</b>: la resolución tiene dos y no uno, y no significan lo mismo — el de su
 *       <b>numeración</b>, que sale de la fecha del acto, y el <b>periodo fiscalizado</b>, que son
 *       los dos extremos de la liquidación. Elegir uno en silencio haría que buscar «2026»
 *       devolviera un conjunto distinto del que quien pregunta tiene en la cabeza; el periodo viaja
 *       en cada fila para que se vea, y cuál de los dos se filtra es una decisión que ninguna
 *       pantalla ha pedido todavía.
 * </ul>
 *
 * @param contribuyenteId el fiscalizado, ya resuelto por quien llama; sin él, todas las de la
 *     municipalidad
 */
public record CriterioDeResoluciones(@Nullable Long contribuyenteId) {

    /** Sin ningún filtro: todas las de la municipalidad. */
    public static CriterioDeResoluciones todas() {
        return new CriterioDeResoluciones(null);
    }

    /** Las que se le dictaron a un contribuyente. */
    public static CriterioDeResoluciones deContribuyente(long contribuyenteId) {
        return new CriterioDeResoluciones(contribuyenteId);
    }
}
