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
 * Lo que `rentas` pide y lee del buzon de {@code identidad} (ADR-0039 etapa 4, ADR-0028 §3): las
 * dos operaciones de {@code EventosController}, tal como {@code ClienteHttpDelBuzonDeIdentidad} las
 * pide y lee de verdad.
 *
 * <p>Se publica en {@code docs/50-api/contratos-que-consume/identidad.json} y lo comprueba el
 * PROVEEDOR en su CI (ADR-0030 §4): un campo que {@code identidad} deje de publicar pone rojo su
 * build, no el nuestro. <b>Hoy esa prueba del proveedor —{@code ContratoConRentasTest}— esta
 * {@code @Disabled} en {@code identidad}</b>, y su {@code ContratosDeLosConsumidoresTest} exige que
 * este archivo NO exista en los cuatro clones: publicarlo desde aqui es exactamente lo que pone esa
 * guarda en rojo alli y lo que la etapa 3 dejo escrito como remedio («hay que quitarle el
 * {@code @Disabled}»). Es de {@code identidad} y no se toca desde este repositorio.
 *
 * <p>La forma del evento es la que {@code BuzonServidoDePuntaAPuntaTest.laFormaDelEventoServido}
 * fija campo a campo del lado del emisor. {@code cuerpo} es TEXTO —la fila entera, como JSON dentro
 * de una cadena— y {@code creadoEn} un instante ISO; los dos se leen aqui como cadena.
 */
@DisplayName("Contrato que rentas consume de identidad")
public class ContratoQueConsumeDeIdentidad extends ContratoQueSePublicaTestBase {

    public static final Map<String, Object> EVENTO =
            ordenados(
                    Map.entry("eventoId", "texto"),
                    Map.entry("secuencia", "entero"),
                    Map.entry("tipo", "texto"),
                    Map.entry("sujetoId", "entero"),
                    Map.entry("cuerpo", "texto"),
                    Map.entry("huella", "texto"),
                    Map.entry("creadoEn", "texto"));

    @Override
    public ContratoDelConsumidor contrato() {
        Map<String, ContratoDelConsumidor.OperacionEsperada> operaciones = new LinkedHashMap<>();

        // Lo pendiente para ESTE consumidor: quien pregunta sale del `azp` del token de servicio,
        // nunca de un parametro. Lo unico que viaja es cuanto se quiere leer.
        operaciones.put(
                "GET /eventos/pendientes",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("limite"),
                        ordenados(
                                Map.entry("eventos", List.of(EVENTO)),
                                Map.entry("quedan", "entero"))));

        // El acuse, por consumidor: una lista de identificadores bajo `eventos`, y de vuelta las
        // tres cifras. `escritos` < `recibidos` no es un error: un acuse repetido se ignora.
        operaciones.put(
                "POST /eventos/acuses",
                new ContratoDelConsumidor.OperacionEsperada(
                        Set.of(),
                        ordenados(
                                Map.entry("recibidos", "entero"),
                                Map.entry("escritos", "entero"),
                                Map.entry("quedan", "entero")),
                        ordenados(Map.entry("eventos", List.of("texto")))));

        return new ContratoDelConsumidor("rentas", "identidad", "/identidad/api/v1", operaciones);
    }

    @SafeVarargs
    private static Map<String, Object> ordenados(Map.Entry<String, Object>... campos) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (Map.Entry<String, Object> campo : campos) {
            mapa.put(campo.getKey(), campo.getValue());
        }
        // `unmodifiableMap` sobre un `LinkedHashMap` y no `Map.copyOf`: el orden de iteracion de
        // `Map.copyOf` no esta especificado y el archivo cambiaria de una corrida a otra.
        return Collections.unmodifiableMap(mapa);
    }
}
