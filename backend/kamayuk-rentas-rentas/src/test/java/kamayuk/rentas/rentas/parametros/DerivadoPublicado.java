package kamayuk.rentas.rentas.parametros;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;

/**
 * El derivado publicable del corpus, leido como lo lee el proceso que publica (#188, #192, #395).
 *
 * <p>Existe para que las dos pruebas que lo consumen —la del cuadro del predial y la del corpus de
 * casos de NEG-05— lo lean <b>de la misma manera</b>. Con una copia en cada una, el dia que el
 * formato del archivo cambiara una de las dos seguiria verde leyendo mal, y el sintoma seria una
 * cifra distinta sin ningun error.
 *
 * <p>Se lee por <b>posicion</b>, igual que {@code FilaPublicable} y que {@code
 * ImportarParametrosDelConjunto}: {@code tipo, clave, vigencia_desde, vigencia_hasta,
 * valor_numerico}. Las demas columnas —la transcripcion verbatim, el documento fuente y las dos
 * firmas— las comprueba {@code docs/10-negocio/verificar-publicacion.mjs} contra el corpus en cada
 * PR, y aqui sobran.
 */
public final class DerivadoPublicado {

    /** El derivado que este repositorio versiona, tal como se despliega. */
    public static final Path ARCHIVO =
            Path.of("../../docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv")
                    .toAbsolutePath()
                    .normalize();

    private DerivadoPublicado() {}

    /**
     * Las filas numericas que rigen ese ejercicio, indexadas por {@code tipo|clave}.
     *
     * <p>La clave vacia es la forma del tipo con un solo valor —la UIT—, y se conserva como cadena
     * vacia a proposito: es la misma distincion que {@code IS NOT DISTINCT FROM} sostiene en la
     * base, y confundirla con «no esta» es el defecto que #247 §2 destapo.
     */
    public static Map<String, String> numerosVigentesEn(int ejercicio) {
        Map<String, String> publicados = new LinkedHashMap<>();
        String primerDia = ejercicio + "-01-01";
        String ultimoDia = ejercicio + "-12-31";
        try (Reader archivo = Files.newBufferedReader(ARCHIVO, StandardCharsets.UTF_8)) {
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

    /** Un lector de un conjunto compuesto con <b>todo</b> lo que el derivado publica. */
    public static LectorDeParametros conjuntoDelEjercicio(Ejercicio ejercicio) {
        return new DelDerivado(ejercicio, numerosVigentesEn(ejercicio.valor()));
    }

    /**
     * Un lector de un conjunto compuesto con <b>solo esas llaves</b>, con los valores que el
     * derivado publica para ellas.
     *
     * <p>Es lo que permite que un caso del corpus declare que parametros necesita y que declararlos
     * de menos falle en vez de pasar en verde con lo que otro caso dejo cargado.
     *
     * @throws IllegalArgumentException si alguna llave no esta publicada: comparar contra un valor
     *     que el corpus no respalda no probaria nada
     */
    public static LectorDeParametros conjuntoCon(Ejercicio ejercicio, Set<String> llaves) {
        Map<String, String> publicados = numerosVigentesEn(ejercicio.valor());
        Map<String, String> elegidos = new LinkedHashMap<>();
        for (String llave : llaves) {
            // Solo el PRIMER dos puntos separa el tipo de la clave: la clave puede llevar los
            // suyos, como `PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA` lleva un guion.
            int corte = llave.indexOf(':');
            String normalizada =
                    corte < 0
                            ? llave + "|"
                            : llave.substring(0, corte) + "|" + llave.substring(corte + 1);
            String valor = publicados.get(normalizada);
            if (valor == null) {
                throw new IllegalArgumentException(
                        "El derivado publicable no trae el parametro "
                                + llave
                                + ", asi que no hay con que comparar al centimo: lo que falta es"
                                + " transcribirlo y firmarlo (ADR-0007), no rellenarlo aqui");
            }
            elegidos.put(normalizada, valor);
        }
        return new DelDerivado(ejercicio, elegidos);
    }

    /** Un conjunto sellado compuesto con lo que el derivado publica, y nada mas. */
    private record DelDerivado(Ejercicio ejercicio, Map<String, String> publicados)
            implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio delEjercicio) {
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(delEjercicio, 1);
            for (Map.Entry<String, String> fila : publicados.entrySet()) {
                String[] partes = fila.getKey().split("\\|", -1);
                constructor.numero(
                        partes[0],
                        partes[1].isEmpty() ? null : partes[1],
                        ValorNormativo.de(fila.getValue()));
            }
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(ejercicio);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio delEjercicio) {
            return IdentificadorDeConjunto.de(1L);
        }
    }
}
