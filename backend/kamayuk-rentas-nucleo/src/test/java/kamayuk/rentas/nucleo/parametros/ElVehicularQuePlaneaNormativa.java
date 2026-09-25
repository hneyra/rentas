package kamayuk.rentas.nucleo.parametros;

import java.nio.file.Path;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.LectorDeParametros;

/**
 * El conjunto que {@code normativa} sellara el dia que publique el vehicular: su derivado real mas
 * las dos filas que su corpus planea, con el nombre y la cifra que el corpus dice (#499).
 *
 * <h2>Por que existe</h2>
 *
 * <p>El vehicular pedia {@code ALICUOTA_VEHICULAR} y {@code VEHICULAR_MINIMO}, y {@code
 * vehicular-valores-referenciales-2026.md} §2 planea publicar {@code VEHICULAR_ALICUOTA} y {@code
 * VEHICULAR_MINIMO_UIT}: el mismo desajuste que #376 cerro en la alcabala y los espectaculos. El
 * derivado todavia no las trae, asi que sembrar con {@link DerivadoPublicado#conjuntoDelEjercicio}
 * a secas daria el mismo 422 con cualquier llave y no distinguiria nada; y sembrarlas a mano con la
 * llave del codigo es justamente lo que dejo verde el defecto de #376. Aqui se siembran con el
 * nombre que planea <b>el corpus</b>, y {@code ElVehicularQuePlaneaNormativaTest} comprueba contra
 * el archivo del corpus que el nombre y la cifra son los suyos.
 *
 * <h2>Las cifras son las que imprime la norma, sin convertir</h2>
 *
 * <p>{@code verificar-publicacion.mjs} exige que la cifra numerica de cada fila este en su texto
 * verbatim, y el corpus prohibe convertir unidades. El art. 33 del TUO LTM dice «1% de la base
 * imponible» y «no puede ser inferior al 1.5% de la UIT»: se publicaran {@code 1} y {@code 1.5},
 * igual que {@code PREDIAL_MINIMO} se publica como {@code 0.6} de «0.6% de la UIT». El {@code _UIT}
 * del nombre dice contra que se mide, no que la cifra venga en UIT.
 *
 * <p>El dia que el derivado las publique, lo publicado gana a lo planeado y la prueba de esta clase
 * se pone roja pidiendo retirarla.
 */
public final class ElVehicularQuePlaneaNormativa {

    /** El archivo del corpus que las planea, al lado del derivado. */
    public static final Path ARCHIVO =
            DerivadoPublicado.ARCHIVO
                    .getParent()
                    .getParent()
                    .resolve("vehicular-valores-referenciales-2026.md");

    /** El tipo de la alicuota, tal como lo escribe §2 del corpus. */
    public static final String ALICUOTA = "VEHICULAR_ALICUOTA";

    /** El tipo del minimo, tal como lo escribe §2 del corpus. */
    public static final String MINIMO = "VEHICULAR_MINIMO_UIT";

    /**
     * Cada tipo planeado, con la cifra que se publicara y el fragmento verbatim del art. 33 de
     * donde sale. Los dos se publican sin clave: el corpus solo pone el ejercicio en la clave
     * compuesta, y el ejercicio es la vigencia.
     */
    public static final Map<String, Fila> FILAS =
            Map.of(
                    ALICUOTA, new Fila("1", "1% de la base imponible"),
                    MINIMO, new Fila("1.5", "1.5% de la UIT"));

    private ElVehicularQuePlaneaNormativa() {}

    /** Todo lo que el derivado publica para el ejercicio, mas las dos filas planeadas. */
    public static LectorDeParametros conjuntoDelEjercicio(Ejercicio ejercicio) {
        Map<String, String> planeadas =
                Map.of(
                        ALICUOTA + "|", FILAS.get(ALICUOTA).cifra(),
                        MINIMO + "|", FILAS.get(MINIMO).cifra());
        return DerivadoPublicado.conjuntoDelEjercicioMas(ejercicio, planeadas);
    }

    /**
     * Una fila planeada.
     *
     * @param cifra el {@code valor_numerico} que se publicara
     * @param fragmento el texto del art. 33, tal como lo transcribe el corpus, que contiene la
     *     cifra
     */
    public record Fila(String cifra, String fragmento) {}
}
