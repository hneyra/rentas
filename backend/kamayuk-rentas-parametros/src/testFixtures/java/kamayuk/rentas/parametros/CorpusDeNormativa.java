package kamayuk.rentas.parametros;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;

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
 * <p>Esas tres comparaban llaves <b>escritas a mano</b>, y ninguna era de la alcabala ni de los
 * espectaculos, que pidieron {@code ALICUOTA_ALCABALA} y {@code ALICUOTA_ESPECTACULO} contra las
 * {@code ALCABALA_ALICUOTA} y {@code ESPECTACULO_ALICUOTA} publicadas sin que nada lo viera (#376).
 * Desde #376 el nucleo reune sus llaves en {@code LlavesDelConjunto} y {@code
 * LlavesDelConjuntoContraElDerivadoTest} las recorre <b>todas</b> contra este mismo archivo —y al
 * reves: todo tipo que el archivo publica lo pide una llave o esta declarado—, y {@code
 * LlavesDeLaLiquidacionContraElDerivadoTest} hace lo mismo con las de la liquidacion de {@code
 * fiscalizacion}. Las dos leen el archivo con {@link #numerosVigentesEn(int)}, y no cada una a su
 * manera.
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

    /**
     * Las filas numericas del derivado que rigen ese ejercicio, indexadas por {@code tipo|clave}.
     *
     * <p>Se lee por <b>posicion</b>, igual que {@code FilaPublicable} y que {@code
     * ImportarParametrosDelConjunto}: {@code tipo, clave, vigencia_desde, vigencia_hasta,
     * valor_numerico}. La clave vacia es la forma del tipo con un solo valor —la UIT— y se conserva
     * como cadena vacia a proposito: es la misma distincion que {@code IS NOT DISTINCT FROM}
     * sostiene en la base, y confundirla con «no esta» es el defecto que #247 §2 destapo.
     *
     * <p>Vive aqui, y no en cada prueba que la necesita, para que todas lean el archivo <b>de la
     * misma manera</b> (#376): con una copia en cada modulo, el dia que el formato cambiara una
     * seguiria verde leyendo mal.
     */
    public static Map<String, String> numerosVigentesEn(int ejercicio) {
        Map<String, String> publicados = new LinkedHashMap<>();
        String primerDia = ejercicio + "-01-01";
        String ultimoDia = ejercicio + "-12-31";
        try (Reader archivo =
                Files.newBufferedReader(derivadoPublicable(), StandardCharsets.UTF_8)) {
            for (FilaCsv fila : LectorDeFilasCsv.leer(archivo)) {
                List<String> campos = fila.campos();
                String desde = campos.get(2);
                String hasta = campos.get(3);
                boolean rige =
                        desde.compareTo(ultimoDia) <= 0
                                && (hasta.isEmpty() || hasta.compareTo(primerDia) >= 0);
                if (!rige || campos.get(4).isEmpty()) {
                    continue;
                }
                publicados.put(campos.get(0) + "|" + campos.get(1), campos.get(4));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el derivado publicable", e);
        }
        return publicados;
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
