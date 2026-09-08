package kamayuk.rentas.verificaciones;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.comun.verificaciones.contrato.ContratoQueSePublicaTestBase;
import org.junit.jupiter.api.DisplayName;

/**
 * Lo que {@code rentas} le pide a {@code caja}, publicado para que su CI lo compruebe (#27).
 *
 * <h2>De las dos direcciones de esta frontera, solo una estaba comprometida</h2>
 *
 * <p>{@code ContratoConCajaTest} comprueba la contraria —que este backend siga <b>aceptando</b> lo
 * que {@code caja} le manda en {@code POST /pagos}, que es lo que C-1 cerro— leyendo {@code
 * caja/docs/50-api/contratos-que-consume/rentas.json}. La de aqui, {@code rentas} <b>pidiendo</b> a
 * {@code caja}, no tenia contrato ni prueba: {@code docs/50-api/contratos-que-consume/} traia
 * {@code catastro.json} y {@code normativa.json} y ningun {@code caja.json}. Un parametro podia
 * cambiar de nombre en cualquiera de los dos lados y los dos CI seguian verdes.
 *
 * <p><b>Y no es hipotetico</b>: {@code AvanceDeCajaHttp} llevaba pidiendo {@code ?dia=&aLaFecha=}
 * contra un endpoint que admite {@code desde} y {@code hasta}, con {@code GET
 * /rentas/api/v1/indicadores/recaudacion} en 500 y las dos aplicaciones sanas.
 *
 * <h2>Seis operaciones para cinco puertos</h2>
 *
 * <p>{@code CobrosDeTasas} pide por dos rutas porque {@code caja} publica dos —acreditar un cobro
 * en un recibo y sumar lo recaudado por un concepto en un rango—, con dos respuestas distintas.
 * Declarar una sola dejaria a la otra sin nadie que comprobara su forma en el CI del proveedor, que
 * es lo unico que este archivo existe para conseguir.
 *
 * <p><b>Y desde #40 entra la sexta: el estado de un recibo por su IDENTIFICADOR.</b> Hasta entonces
 * esa ausencia era deliberada —el puerto lo servia un muñon que lanzaba {@code SinRutaEnCaja}, y
 * declarar aqui una ruta que este backend no llama pondria rojo el CI del proveedor por nada—. Ya
 * no: {@code AnulacionesDeReciboHttp} la pide de verdad, asi que declararla es exactamente lo que
 * este archivo existe para hacer. La ruta la publica {@code caja} desde el propio P5D ({@code
 * e54c443}), o sea el mismo commit que creo el muñon.
 *
 * <h2>Lo que se declara es lo que se LEE, no lo que el proveedor publica</h2>
 *
 * <p>{@code GET /recaudacion/avance} publica ademas {@code filas}, {@code neto} y {@code turno}, y
 * aqui no se declaran: un campo declarado es un campo que el proveedor no puede retirar sin poner
 * rojo su build, y comprometerle campos que nadie lee le ata las manos por nada.
 *
 * <h2>La ida y vuelta, que es lo que hace que esto no sea otra copia a mano</h2>
 *
 * <p>Que el archivo publicado sea el que produce {@link #contrato()} no dice nada sobre si {@link
 * #contrato()} describe lo que el adaptador pide y lee: los dos salen de este repositorio. Lo que
 * lo sostiene son sus dos hermanas, {@code PeticionesACajaTest} —que compara la cadena de consulta
 * que sale de verdad contra los parametros declarados aqui— y {@code LecturaDeCajaTest} —que
 * fabrica la respuesta <b>a partir de estas formas</b> y la pasa por el adaptador de produccion—.
 */
@DisplayName("Contrato que rentas consume de caja")
public class ContratoQueConsumeDeCaja extends ContratoQueSePublicaTestBase {

    /**
     * Un importe con su fecha, tal como {@code caja} publica todo importe (regla 9, RNF-075).
     *
     * <p>Es {@code ImporteActualizado}, un OBJETO de dos campos. Que este declarado aqui como
     * objeto y no como hoja es exactamente lo que #41 midio: {@code path("cobrado").asString("0")}
     * sobre el no falla —devuelve el «0»— y el panel publicaria dos ceros sin un solo error.
     */
    public static final Map<String, Object> IMPORTE_ACTUALIZADO =
            ordenados(
                    // `Dinero` viaja como CADENA (RNF-055, regla 1): `caja` lo serializa con
                    // `writeString` y este lado lo lee con `Dinero.de(String)`. Leerlo como
                    // numero lo haria pasar por un `double` y la precision monetaria se perderia
                    // en el transporte, que es el sitio donde nadie mira.
                    Map.entry("importe", "texto"),
                    // Y la fecha como «fecha»: el componente es un `LocalDate` de verdad, no una
                    // cadena ISO como los `desde`/`hasta` del sobre. Declararla «texto» pondria
                    // rojo el CI del proveedor por un campo que publica bien.
                    Map.entry("actualizadoA", "fecha"));

    /** El avance del dia, tal como lo lee {@code AvanceDeCajaHttp}. */
    public static final Map<String, Object> AVANCE_DEL_RANGO =
            ordenados(
                    // El rango con que `caja` contesta. El adaptador lo COMPARA con el que pidio:
                    // una suma de otros dias publicada como la de hoy no se distingue de la buena.
                    Map.entry("desde", "texto"),
                    Map.entry("hasta", "texto"),
                    Map.entry("aLaFecha", "texto"),
                    Map.entry("cobrado", IMPORTE_ACTUALIZADO),
                    Map.entry("anulado", IMPORTE_ACTUALIZADO));

    /**
     * El estado de un recibo por su identificador, tal como lo lee {@code AnulacionesDeReciboHttp}.
     *
     * <p>Dos campos y ninguna cifra, que es lo que {@code caja} publica: quien pregunta esto no
     * quiere el recibo, quiere saber si sigue en pie. {@code anulado} se declara <b>booleano</b> y
     * se lee exigiendolo: {@code asBoolean(false)} sobre un campo ausente no da error, da {@code
     * false} — y ese {@code false} significa «ese recibo sigue vigente», que es la respuesta que
     * impide anular el convenio.
     */
    public static final Map<String, Object> ESTADO_DEL_RECIBO =
            ordenados(Map.entry("reciboId", "entero"), Map.entry("anulado", "booleano"));

    /** Un recibo de tramite, tal como lo lee {@code RecibosDeTramiteHttp}. */
    public static final Map<String, Object> RECIBO_DE_TRAMITE =
            ordenados(
                    Map.entry("reciboId", "entero"),
                    Map.entry("numero", "texto"),
                    Map.entry("fechaDePago", "texto"),
                    Map.entry("contribuyenteId", "entero"),
                    Map.entry("esDeTasas", "booleano"),
                    Map.entry("anulado", "booleano"),
                    Map.entry("conceptos", java.util.List.of("texto")),
                    // `Dinero` viaja como cadena (RNF-055): `caja` lo escribe con
                    // `toPlainString()` y este lado lo lee con `Dinero.de(String)`.
                    Map.entry("total", "texto"),
                    Map.entry("actualizadoA", "texto"));

    /** Un concepto del TUPA cobrado en un recibo, tal como lo lee {@code CobrosDeTasasHttp}. */
    public static final Map<String, Object> TASA_COBRADA =
            ordenados(
                    Map.entry("numeroDeRecibo", "texto"),
                    Map.entry("codigoDeTasa", "texto"),
                    Map.entry("cantidad", "entero"),
                    Map.entry("importe", "texto"),
                    Map.entry("fecha", "texto"));

    /** Lo recaudado por un concepto en un rango, con sus dos fechas. */
    public static final Map<String, Object> RECAUDACION_DE_TASA =
            ordenados(
                    Map.entry("codigoDeTasa", "texto"),
                    Map.entry("cobrado", "texto"),
                    Map.entry("anulado", "texto"),
                    Map.entry("desde", "texto"),
                    Map.entry("hasta", "texto"));

    /** Lo que vuelve al emitir una orden de cobro. `ordenId` y `nueva` no son texto. */
    public static final Map<String, Object> ORDEN_EMITIDA =
            ordenados(
                    Map.entry("ordenId", "entero"),
                    Map.entry("estado", "texto"),
                    Map.entry("nueva", "booleano"));

    /**
     * El cuerpo que {@code OrdenesDeCobroHttp} compone a mano.
     *
     * <p>Se declara entero y no solo lo obligatorio: lo que se manda a un {@code record} que no
     * declara un campo lo <b>descarta Jackson en silencio</b> —{@code FAIL_ON_UNKNOWN_PROPERTIES}
     * esta apagado en los cuatro— y el emisor recibe 201. El dato se pierde y las dos partes creen
     * que llego, que es el camino del dinero fallando sin ruido.
     */
    public static final Map<String, Object> PETICION_DE_ORDEN =
            ordenados(
                    Map.entry("sistemaOrigen", "texto"),
                    Map.entry("referenciaExterna", "texto"),
                    Map.entry("concepto", "texto"),
                    Map.entry("detalle", "texto"),
                    Map.entry("importe", "texto"),
                    Map.entry("fechaExigibilidad", "texto"),
                    Map.entry("actualizadoA", "texto"),
                    Map.entry("pagadorDocumento", "texto"),
                    Map.entry("pagadorNombre", "texto"),
                    Map.entry("pagadorIdExterno", "entero"),
                    Map.entry("observacion", "texto"));

    @Override
    public ContratoDelConsumidor contrato() {
        Map<String, ContratoDelConsumidor.OperacionEsperada> operaciones = new LinkedHashMap<>();

        // El avance de la ventanilla de UN dia. `desde` y `hasta` con la misma fecha: es el
        // rango de un dia, que es lo que `caja` sabe sumar (#27 AC-1). NO se manda `aLaFecha`
        // —`caja` no lo lee, lo pone su reloj y viaja con la cifra— ni `dia`, que no existe:
        // mandarlos era el defecto que este contrato existe para impedir, y ademas `caja`
        // rechaza los parametros que no conoce con un 422.
        operaciones.put(
                "GET /recaudacion/avance",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("desde", "hasta"), AVANCE_DEL_RANGO));

        // Un recibo por su numero impreso: lo que acredita que el derecho de tramite se pago
        // antes de emitir una licencia (RF-110). Sin parametros, el numero va en la ruta.
        operaciones.put(
                "GET /recibos/{numero}",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), RECIBO_DE_TRAMITE));

        // Si un recibo esta anulado, por su IDENTIFICADOR interno y no por su numero impreso:
        // `convenio_movimiento.recibo_id` guarda el id, no el numero del papel. Sin parametros,
        // el identificador va en la ruta.
        operaciones.put(
                "GET /recibos/por-id/{reciboId}",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), ESTADO_DEL_RECIBO));

        // Si un recibo cobro un concepto del TUPA, y por cuanto. Los dos van en la ruta.
        operaciones.put(
                "GET /tasas/{codigo}/cobros/{numero}",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), TASA_COBRADA));

        // Lo recaudado por un concepto en un rango: el resumen anual de licencias.
        operaciones.put(
                "GET /tasas/{codigo}/recaudacion",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("desde", "hasta"), RECAUDACION_DE_TASA));

        // La UNICA escritura de este consumidor, y la unica de las cinco cuya respuesta ademas
        // se lee: `estado` decide si la orden queda viva.
        operaciones.put(
                "POST /ordenes-de-cobro",
                new ContratoDelConsumidor.OperacionEsperada(
                        Set.of(), ORDEN_EMITIDA, PETICION_DE_ORDEN));

        return new ContratoDelConsumidor("rentas", "caja", "/caja/api/v1", operaciones);
    }

    @SafeVarargs
    private static Map<String, Object> ordenados(Map.Entry<String, Object>... campos) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (Map.Entry<String, Object> campo : campos) {
            mapa.put(campo.getKey(), campo.getValue());
        }
        // `unmodifiableMap` sobre un `LinkedHashMap`, no `Map.copyOf`: este mapa se serializa al
        // archivo comprometido, y el orden de iteracion de `Map.copyOf` no esta especificado — el
        // archivo cambiaria de una corrida a otra sin que nadie tocara nada.
        return Collections.unmodifiableMap(mapa);
    }
}
