package kamayuk.rentas.verificaciones;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.comun.verificaciones.contrato.ContratoQueSePublicaTestBase;
import org.junit.jupiter.api.DisplayName;

/**
 * Lo que {@code rentas} le pide a {@code normativa}, publicado para que su CI lo comprueba.
 *
 * <p>Son las dos de {@code ClienteHttpDeNormativa}: resolver el conjunto sellado de un ejercicio y
 * descargar su snapshot. La segunda es la unica frontera de las cuatro que ademas verifica una
 * <b>huella</b> —el {@code ETag} contra el sha256 del cuerpo—, y eso pone una exigencia que el
 * contrato no puede expresar y hay que decir: el cuerpo tiene que ser <b>byte a byte</b> el que el
 * proveedor sello, asi que reordenar sus claves rompe la descarga sin cambiar un solo campo. La
 * comparacion de formas de aqui no lo ve; lo que lo ve es la huella, en produccion, y por eso esa
 * respuesta se sirve como {@code ResponseEntity<String>} escrita a mano.
 *
 * <p><b>Este contrato es identico al que publica {@code catastro}</b>, y no por copia: los dos
 * {@code ClienteHttpDeNormativa} son el mismo archivo con otro paquete —22 lineas de diferencia,
 * todas de {@code import}—. Se declaran por separado porque el proveedor tiene que saber quien
 * pide: el dia que uno de los dos deje de descargar un ambito, su archivo lo dira y el del otro no.
 */
@DisplayName("Contrato que rentas consume de normativa")
public class ContratoQueConsumeDeNormativa extends ContratoQueSePublicaTestBase {

    /** El snapshot sellado, tal como lo lee {@code ClienteHttpDeNormativa.descargar}. */
    public static final Map<String, Object> SNAPSHOT =
            ordenados(
                    Map.entry("conjuntoId", "entero"),
                    Map.entry("ejercicio", "entero"),
                    Map.entry("version", "entero"),
                    Map.entry("ambito", "texto"),
                    Map.entry(
                            "parametros",
                            List.of(
                                    ordenados(
                                            Map.entry("tipo", "texto"),
                                            Map.entry("clave", "texto"),
                                            Map.entry("valorNumerico", "texto"),
                                            Map.entry("valorTexto", "texto"),
                                            Map.entry("vigenciaDesde", "texto"),
                                            Map.entry("vigenciaHasta", "texto"),
                                            Map.entry("documentoFuente", "texto")))),
                    Map.entry(
                            "valoresUnitarios",
                            List.of(
                                    ordenados(
                                            Map.entry("partida", "texto"),
                                            Map.entry("categoria", "texto"),
                                            Map.entry("anioConstruccionDesde", "entero"),
                                            Map.entry("anioConstruccionHasta", "entero"),
                                            Map.entry("valorM2", "texto"),
                                            Map.entry("documentoFuente", "texto")))),
                    Map.entry(
                            "depreciaciones",
                            List.of(
                                    ordenados(
                                            Map.entry("uso", "texto"),
                                            Map.entry("material", "texto"),
                                            Map.entry("estadoConservacion", "texto"),
                                            Map.entry("antiguedadHasta", "entero"),
                                            Map.entry("porcentaje", "texto"),
                                            Map.entry("documentoFuente", "texto")))),
                    Map.entry(
                            "valoresReferenciales",
                            List.of(
                                    ordenados(
                                            Map.entry("ejercicio", "entero"),
                                            Map.entry("categoria", "texto"),
                                            Map.entry("marca", "texto"),
                                            Map.entry("modelo", "texto"),
                                            Map.entry("anioFabricacion", "entero"),
                                            Map.entry("valor", "texto"),
                                            Map.entry("documentoFuente", "texto")))));

    @Override
    protected ContratoDelConsumidor contrato() {
        Map<String, ContratoDelConsumidor.OperacionEsperada> operaciones = new LinkedHashMap<>();

        operaciones.put(
                "GET /conjuntos",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("ejercicio"), ordenados(Map.entry("conjuntoId", "entero"))));

        operaciones.put(
                "GET /conjuntos/{id}/snapshot",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of("ambito"), SNAPSHOT));

        return new ContratoDelConsumidor("rentas", "normativa", "/normativa/api/v1", operaciones);
    }

    @SafeVarargs
    static Map<String, Object> ordenados(Map.Entry<String, Object>... campos) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (Map.Entry<String, Object> campo : campos) {
            mapa.put(campo.getKey(), campo.getValue());
        }
        // `unmodifiableMap` sobre un `LinkedHashMap`, no `Map.copyOf`: este mapa se
        // serializa al archivo comprometido, y el orden de iteracion de `Map.copyOf` no
        // esta especificado — el archivo cambiaria de una corrida a otra sin que nadie
        // tocara nada, y entonces la comparacion byte a byte deja de significar algo.
        return Collections.unmodifiableMap(mapa);
    }
}
