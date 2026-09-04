package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code rec_impresion} manda (#41, RF-101).
 *
 * <p>La pantalla lista los expedientes pendientes de pago, deja marcar varios y ofrece «Generar»,
 * «Imprimir» y «REC 2». Esta peticion cubre las tres: {@code rec} dice cual de las dos
 * resoluciones, y {@code reimprimir} distingue generar por primera vez de volver a sacar lo ya
 * emitido.
 *
 * <p><b>Cada expediente es su propia transaccion.</b> Es lo que permite que el informe diga, por
 * expediente, si salio o por que no: si los veinte compartieran una, el primero que no tuviera
 * deuda se llevaria por delante los diecinueve buenos. Es el mismo criterio que la carga de vias
 * (#290) y por el mismo motivo.
 *
 * @param expedientes los numeros impresos de los expedientes marcados
 * @param rec cual se emite: {@code REC1} o {@code REC2}; si falta, {@code REC1}
 * @param medida la forma de la medida cautelar; obligatoria cuando {@code rec} es {@code REC2}
 * @param fecha el dia del acto, en ISO; si falta, hoy
 * @param proyectarInteresAl a que dia se proyecta la deuda que se imprime (regla 9); si falta, la
 *     fecha del acto
 * @param glosa la glosa que va en la resolucion; si falta, el titulo del acto
 * @param formato PDF, XLS o RTF; si falta, PDF
 * @param reimprimir si es {@code true}, no se dicta nada: se vuelve a sacar la resolucion ya
 *     emitida, marcada como duplicado y comprobando que sale identica (RF-132)
 * @param observacion por que se emite o se reimprime (regla 10, RNF-052)
 */
public record PeticionDeRec(
        @Nullable List<String> expedientes,
        @Nullable String rec,
        @Nullable String medida,
        @Nullable String fecha,
        @Nullable String proyectarInteresAl,
        @Nullable String glosa,
        @Nullable String formato,
        @Nullable Boolean reimprimir,
        @Nullable String observacion) {}
