package kamayuk.rentas.catastro;

import java.util.Objects;
import kamayuk.rentas.dominio.ValorNormativo;
import org.jspecify.annotations.Nullable;

/**
 * Una celda del cuadro de valores unitarios de edificacion, publicada para otros contextos acotados
 * (ARQ-01 §4, #17, #48).
 *
 * <p>La pide {@code licencias}: la valorizacion de obra del FUE multiplica el area declarada de
 * cada partida por el valor de la letra que le corresponde, y esa cifra vive en {@code
 * valor_unitario_edificacion} y <b>solo ahi</b> (AC 2 de #48, regla 5).
 *
 * <p><b>La partida viaja como texto y no como la enumeracion de {@code catastro}</b>: publicar
 * {@code Partida} obligaria a {@code licencias} a depender del modelo interno de {@code catastro},
 * que es justo lo que Spring Modulith vigila. El vocabulario que las dos mitades comparten no es un
 * tipo de Java: es el {@code CHECK} de la columna, que V1 escribe una vez y V43 repite en {@code
 * edificacion_estructura} palabra por palabra.
 *
 * @param partida MUROS, TECHOS o PUERTAS: las tres partidas de apreciacion exterior del cuadro
 * @param categoria la letra, de A a I
 * @param anioConstruccionDesde extremo inferior del rango de anios de construccion al que aplica
 * @param anioConstruccionHasta extremo superior; nulo cuando la tabla no le pone tope
 * @param valorM2 la cifra normativa por metro cuadrado; nunca un {@code Dinero} determinado
 */
public record ValorUnitarioPublicado(
        String partida,
        char categoria,
        int anioConstruccionDesde,
        @Nullable Integer anioConstruccionHasta,
        ValorNormativo valorM2) {

    public ValorUnitarioPublicado {
        Objects.requireNonNull(partida, "La celda del cuadro dice de que partida es");
        Objects.requireNonNull(valorM2, "La celda del cuadro dice cuanto vale el metro cuadrado");
        partida = partida.strip().toUpperCase(java.util.Locale.ROOT);
    }

    /** Si esta celda es la que rige a una edificacion construida en ese anio. */
    public boolean rigeEn(int anioDeConstruccion) {
        Integer hasta = anioConstruccionHasta;
        return anioDeConstruccion >= anioConstruccionDesde
                && (hasta == null || anioDeConstruccion <= hasta);
    }
}
