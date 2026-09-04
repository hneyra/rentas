package kamayuk.rentas.parametros;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Donde esta el derivado publicable de {@code normativa}, ahora que el corpus se fue (ADR-0025 §5).
 *
 * <h2>Que guarantia sostiene, y por que no se puede perder</h2>
 *
 * <p>Tres clases de prueba de este backend leen ese CSV y comprueban que <b>la llave con que el
 * derivado publica un valor es exactamente la que el consumidor pide</b>: {@code
 * PLAZO:PRESCRIPCION- DECLARACION_PRESENTADA}, {@code TRAMO_PREDIAL_LIMITE:2}, {@code
 * PLAZO:RECLAMACION_REC1}. Es el defecto que #192 midio y que ninguna otra verificacion ve: un
 * valor publicado bajo una clave que nadie lee se informa como publicado, el conjunto se sella con
 * el dentro, {@code verificar-publicacion.mjs} pasa en verde —la cifra esta en la norma y las
 * firmas son las del corpus— y la operacion sigue fallando con el sintoma de «no esta cargado».
 *
 * <p>Antes de P5B las dos mitades estaban en el mismo repositorio y el compilador y el sistema de
 * archivos las sujetaban. Ahora no: el CSV es de {@code normativa} y el consumidor es de {@code
 * rentas}. <b>Que la comprobacion sobreviva al corte es justamente lo que ADR-0030 §4 pide</b> —una
 * prueba de contrato en el sitio de lo que hacia el compilador—, asi que se sostiene por el mismo
 * mecanismo con que el backend consume {@code comun-verificaciones}: el repositorio hermano,
 * clonado al lado.
 *
 * <h2>Y si no esta, falla; no se salta</h2>
 *
 * <p>Una prueba que se omite a si misma deja el build en verde sin haber verificado nada, que es lo
 * contrario de lo que estas tres existen para hacer. Si {@code normativa} no esta clonado, el
 * mensaje dice el {@code git clone} que falta.
 */
public final class CorpusDeNormativa {

    /**
     * El repositorio hermano, igual que `infrastructure` para las barreras.
     *
     * <p>La ruta es relativa al <b>directorio del modulo</b>, que es donde Gradle arranca el
     * proceso de prueba: {@code rentas/backend/kamayuk-rentas-<contexto>}. De ahi a la carpeta que
     * contiene los seis repositorios hay tres saltos, no dos — y equivocarse en uno no da «archivo
     * no encontrado» en la asercion sino un {@code ExceptionInInitializerError} en el campo
     * estatico, que es un sintoma que no se parece a su causa.
     */
    private static final Path RAIZ = Path.of("../../../normativa").toAbsolutePath().normalize();

    private CorpusDeNormativa() {}

    /** {@code publicacion/parametros-2026.csv}: el derivado que `normativa` despliega. */
    public static Path derivadoPublicable() {
        return exigir(
                RAIZ.resolve("docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv"));
    }

    private static Path exigir(Path ruta) {
        if (!Files.isRegularFile(ruta)) {
            throw new IllegalStateException(
                    "No esta "
                            + ruta
                            + ". Desde P5B el corpus normativo vive en `normativa` (ADR-0025 §5), y"
                            + " estas pruebas comprueban que la llave con que el derivado publica un"
                            + " valor es la misma que este backend pide (#192). Clona el repositorio"
                            + " hermano: git clone https://github.com/hneyra/normativa ../../normativa");
        }
        return ruta;
    }
}
