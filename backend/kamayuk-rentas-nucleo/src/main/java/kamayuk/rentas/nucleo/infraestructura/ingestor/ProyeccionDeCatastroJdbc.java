package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Escribe la proyeccion local de {@code catastro} (C-8, `V4`, `V5`, `V9`, `V12`).
 *
 * <h2>Se conecta con {@code rol_ingestor_catastro}, no con {@code sgtm_app}</h2>
 *
 * <p>El {@code JdbcClient} que recibe sale del pool que {@link ConfiguracionDelIngestor} construye,
 * y esa es la mitad de ADR-0027 §3 que no es una promesa: `V4` y `V5` no le dan a la aplicacion mas
 * que {@code SELECT}. Si esta clase recibiera el pool de la aplicacion, no fallaria al arrancar —
 * fallaria en el primer {@code INSERT}, con {@code 42501}, y el sintoma no se parece a la causa.
 *
 * <h2>Ninguna consulta filtra por municipalidad, y ninguna la recibe</h2>
 *
 * <p>Regla 2. El valor entra por {@code current_setting('app.municipalidad_id')} —del {@code SET
 * LOCAL} que abre la transaccion— tanto en el {@code WHERE} de la politica RLS como en la columna
 * de cada {@code INSERT}. Sin contexto, el {@code INSERT} <b>falla</b> en lugar de plantar una fila
 * con la municipalidad equivocada.
 *
 * <h2>Las tres decisiones que este archivo toma, y por que</h2>
 *
 * <ol>
 *   <li><b>El buzon se escribe PRIMERO y con {@code ON CONFLICT DO NOTHING}.</b> Es la
 *       deduplicacion, y es del motor: diez entregas simultaneas del mismo hecho leerian las diez
 *       «no esta» con un {@code SELECT} previo y las diez escribirian. Aqui escribe una.
 *   <li><b>Un hecho VIEJO no pisa a uno nuevo</b>, y el {@code WHERE} del {@code ON CONFLICT DO
 *       UPDATE} es lo que lo impide. Como el {@code RETURNING} viene vacio cuando ese {@code WHERE}
 *       no se cumple, el descarte <b>se puede decir</b> en vez de ocurrir en silencio.
 *   <li><b>Una valuacion que ya esta NO se sobreescribe.</b> `V5` no le da {@code UPDATE} al
 *       ingestor sobre {@code valuacion_predio} a proposito: es un hecho sellado. Un segundo hecho
 *       para el mismo (ejercicio, predio) sale como {@link ProyeccionDeCatastro.NoSePuedeAplicar}
 *       nombrandolo, que es un hueco declarado de C-8 y no un fallo de esta clase.
 * </ol>
 */
public class ProyeccionDeCatastroJdbc implements ProyeccionDeCatastro {

    /**
     * La municipalidad en curso, como expresion SQL. La misma constante que {@code RepositorioJdbc}
     * usa, repetida aqui porque esta clase no extiende esa base: no la construye Spring por
     * componente sino {@link ConfiguracionDelIngestor}, con otro pool.
     */
    private static final String MUNICIPALIDAD_ACTUAL =
            "current_setting('app.municipalidad_id')::bigint";

    private final JdbcClient jdbc;
    private final CuerpoDelHecho cuerpos;

    public ProyeccionDeCatastroJdbc(JdbcClient jdbc, CuerpoDelHecho cuerpos) {
        this.jdbc = jdbc;
        this.cuerpos = cuerpos;
    }

    @Override
    public Aplicacion aplicar(HechoRecibido hecho, Instant cuando) {
        if (!anotarEnElBuzon(hecho, cuando)) {
            return Aplicacion.YA_APLICADO;
        }
        // El `default` esta por Checkstyle y no sobra: el enumerado es una COPIA del de
        // `catastro` (ver `TipoDeHechoDeCatastro`), y el dia que alguien le anada un cuarto valor
        // sin decidir que hace la proyeccion con el, esto tiene que fallar en vez de no escribir
        // nada y devolver «aplicado».
        return switch (hecho.tipo()) {
            case PREDIO_PROYECTADO -> aplicarPredio(hecho, cuando);
            case VALUACION_PUBLICADA -> aplicarValuacion(hecho, cuando);
            case CORRIDA_CERRADA -> aplicarCorrida(hecho, cuando);
            default ->
                    throw new NoSePuedeAplicar(
                            "El hecho es de tipo «"
                                    + hecho.tipo()
                                    + "» y esta proyeccion no sabe que escribir con el");
        };
    }

    @Override
    public void matar(HechoRecibido hecho, String motivo, Instant cuando) {
        jdbc.sql(
                        """
                        INSERT INTO catastro_evento_muerto (municipalidad_id, evento_id, secuencia,
                                                            tipo, predio_id, ejercicio, cuerpo,
                                                            huella, motivo, recibido_en)
                        VALUES (%s, :evento, :secuencia, :tipo, :predio, :ejercicio,
                                :cuerpo, :huella, :motivo, :cuando)
                        ON CONFLICT (municipalidad_id, evento_id) DO NOTHING
                        """
                                .formatted(MUNICIPALIDAD_ACTUAL))
                .param("evento", hecho.eventoId())
                .param("secuencia", hecho.secuencia())
                .param("tipo", hecho.tipo().name())
                .param("predio", hecho.predioId())
                .param("ejercicio", hecho.ejercicio())
                .param("cuerpo", hecho.cuerpo())
                .param("huella", hecho.huella())
                .param("motivo", recortar(motivo))
                .param("cuando", Timestamp.from(cuando))
                .update();
    }

    @Override
    public long muertosSinExplicar() {
        Long cuantos =
                jdbc.sql(
                                "SELECT count(*) FROM catastro_evento_muerto"
                                        + " WHERE explicacion IS NULL")
                        .query(Long.class)
                        .single();
        return cuantos == null ? 0 : cuantos;
    }

    // ------------------------------------------------------------------

    /**
     * Deja constancia de que este hecho se aplico, y dice si era nuevo.
     *
     * <p><b>Va primero por dos motivos.</b> Uno: es la deduplicacion, y aplicar antes de deduplicar
     * seria aplicar dos veces. Dos: las cuatro claves foraneas que `V9` colgo de esta tabla exigen
     * que la fila del evento exista <b>antes</b> que la fila que lo nombra.
     *
     * @return si el hecho no estaba
     * @throws NoSePuedeAplicar si estaba con OTRA huella — el emisor reescribiendo un hecho sellado
     */
    private boolean anotarEnElBuzon(HechoRecibido hecho, Instant cuando) {
        Optional<java.util.UUID> escrito =
                jdbc.sql(
                                """
                                INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id,
                                                                      secuencia, tipo, predio_id,
                                                                      aplicado_en, huella)
                                VALUES (%s, :evento, :secuencia, :tipo, :predio, :cuando, :huella)
                                ON CONFLICT (municipalidad_id, evento_id) DO NOTHING
                                RETURNING evento_id
                                """
                                        .formatted(MUNICIPALIDAD_ACTUAL))
                        .param("evento", hecho.eventoId())
                        .param("secuencia", hecho.secuencia())
                        .param("tipo", hecho.tipo().name())
                        .param("predio", hecho.predioId())
                        .param("cuando", Timestamp.from(cuando))
                        .param("huella", hecho.huella())
                        .query(java.util.UUID.class)
                        .optional();
        if (escrito.isPresent()) {
            return true;
        }
        String huellaQueYaEstaba =
                jdbc.sql("SELECT huella FROM catastro_evento_aplicado WHERE evento_id = :evento")
                        .param("evento", hecho.eventoId())
                        .query(String.class)
                        .single()
                        .strip();
        if (!huellaQueYaEstaba.equals(hecho.huella())) {
            // LA MITAD DE `V9` QUE NO SE VE HASTA QUE PASA. Sin la huella guardada, la
            // deduplicacion por `evento_id` daria esto por bueno y lo descartaria en silencio:
            // el emisor creeria haber corregido un hecho sellado y aqui seguiria el viejo.
            throw new NoSePuedeAplicar(
                    "El hecho "
                            + hecho.eventoId()
                            + " ya se aplico con la huella "
                            + huellaQueYaEstaba
                            + " y ahora llega con "
                            + hecho.huella()
                            + ". El emisor esta reescribiendo un hecho sellado (ADR-0027 §1), y"
                            + " esto no se arregla reintentando: hay que mirar por que `catastro`"
                            + " volvio a publicar la misma identidad con otro contenido");
        }
        return false;
    }

    private Aplicacion aplicarPredio(HechoRecibido hecho, Instant cuando) {
        CuerpoDelHecho.Predio predio = cuerpos.predio(hecho.cuerpo());
        Optional<Long> escrito =
                jdbc.sql(
                                """
                                INSERT INTO predio_ref (municipalidad_id, predio_id,
                                                        codigo_ref_catastral, direccion,
                                                        sector_codigo, estado, secuencia,
                                                        proyectado_en, evento_id, huella)
                                VALUES (%s, :predio, :codigo, :direccion, :sector, :estado,
                                        :secuencia, :cuando, :evento, :huella)
                                ON CONFLICT (municipalidad_id, predio_id) DO UPDATE
                                   SET codigo_ref_catastral = EXCLUDED.codigo_ref_catastral,
                                       direccion = EXCLUDED.direccion,
                                       sector_codigo = EXCLUDED.sector_codigo,
                                       estado = EXCLUDED.estado,
                                       secuencia = EXCLUDED.secuencia,
                                       proyectado_en = EXCLUDED.proyectado_en,
                                       evento_id = EXCLUDED.evento_id,
                                       huella = EXCLUDED.huella
                                 WHERE predio_ref.secuencia < EXCLUDED.secuencia
                                RETURNING predio_id
                                """
                                        .formatted(MUNICIPALIDAD_ACTUAL))
                        .param("predio", predio.predioId())
                        .param("codigo", predio.codigoRefCatastral())
                        .param("direccion", predio.direccion())
                        .param("sector", predio.sectorCodigo())
                        .param("estado", predio.estado())
                        .param("secuencia", hecho.secuencia())
                        .param("cuando", Timestamp.from(cuando))
                        .param("evento", hecho.eventoId())
                        .param("huella", hecho.huella())
                        .query(Long.class)
                        .optional();
        if (escrito.isEmpty()) {
            // La fila que hay salio de un hecho MAS NUEVO. Se descarta el evento entero —sus
            // fichas incluidas—: aplicar las fichas de un hecho viejo sobre un predio nuevo
            // dejaria la proyeccion describiendo un padron que nunca existio.
            return Aplicacion.DESCARTADO_POR_VIEJO;
        }
        for (CuerpoDelHecho.Ficha ficha : predio.fichas()) {
            jdbc.sql(
                            """
                            INSERT INTO ficha_ref (municipalidad_id, ficha_id, predio_id, tipo,
                                                   version, vigencia_desde, vigencia_hasta,
                                                   area_terreno, uso, secuencia, proyectado_en,
                                                   evento_id, huella)
                            VALUES (%s, :ficha, :predio, :tipo, :version, :desde, :hasta,
                                    CAST(:area AS area_m2), :uso, :secuencia, :cuando, :evento,
                                    :huella)
                            ON CONFLICT (municipalidad_id, ficha_id) DO UPDATE
                               SET predio_id = EXCLUDED.predio_id,
                                   tipo = EXCLUDED.tipo,
                                   version = EXCLUDED.version,
                                   vigencia_desde = EXCLUDED.vigencia_desde,
                                   vigencia_hasta = EXCLUDED.vigencia_hasta,
                                   area_terreno = EXCLUDED.area_terreno,
                                   uso = EXCLUDED.uso,
                                   secuencia = EXCLUDED.secuencia,
                                   proyectado_en = EXCLUDED.proyectado_en,
                                   evento_id = EXCLUDED.evento_id,
                                   huella = EXCLUDED.huella
                             WHERE ficha_ref.secuencia < EXCLUDED.secuencia
                            """
                                    .formatted(MUNICIPALIDAD_ACTUAL))
                    .param("ficha", ficha.fichaId())
                    .param("predio", predio.predioId())
                    .param("tipo", ficha.tipo())
                    .param("version", ficha.version())
                    .param("desde", ficha.vigenciaDesde())
                    .param("hasta", ficha.vigenciaHasta())
                    .param("area", ficha.areaTerreno())
                    .param("uso", ficha.uso())
                    .param("secuencia", hecho.secuencia())
                    .param("cuando", Timestamp.from(cuando))
                    .param("evento", hecho.eventoId())
                    .param("huella", hecho.huella())
                    .update();
        }
        return Aplicacion.APLICADO;
    }

    private Aplicacion aplicarValuacion(HechoRecibido hecho, Instant cuando) {
        CuerpoDelHecho.Valuacion valuacion = cuerpos.valuacion(hecho.cuerpo());
        Optional<Long> escrito =
                jdbc.sql(
                                """
                                INSERT INTO valuacion_predio (municipalidad_id, ejercicio, predio_id,
                                                              fecha_de_corte, valor_terreno,
                                                              valor_construccion, valor_obras,
                                                              valor_del_predio, motivo,
                                                              llave_que_falta, ficha_catastral_id,
                                                              conjunto_id, reglas_version,
                                                              reglas_aplicadas, huella, evento_id,
                                                              recibida_en, secuencia)
                                VALUES (%s, :ejercicio, :predio, :corte, :terreno, :construccion,
                                        :obras, :total, :motivo, :llave, :ficha, :conjunto,
                                        :reglasVersion, :reglasAplicadas, :huella, :evento,
                                        :cuando, :secuencia)
                                ON CONFLICT (municipalidad_id, ejercicio, predio_id) DO NOTHING
                                RETURNING predio_id
                                """
                                        .formatted(MUNICIPALIDAD_ACTUAL))
                        .param("ejercicio", valuacion.ejercicio())
                        .param("predio", valuacion.predioId())
                        .param("corte", valuacion.fechaDeCorte())
                        .param("terreno", importe(valuacion.valorTerreno()))
                        .param("construccion", importe(valuacion.valorConstruccion()))
                        .param("obras", importe(valuacion.valorObras()))
                        .param("total", importe(valuacion.valorDelPredio()))
                        .param("motivo", valuacion.motivo())
                        .param("llave", valuacion.llaveQueFalta())
                        .param("ficha", valuacion.fichaCatastralId())
                        .param("conjunto", valuacion.conjuntoId())
                        .param("reglasVersion", valuacion.reglasVersion())
                        .param("reglasAplicadas", valuacion.reglasAplicadas())
                        .param("huella", hecho.huella())
                        .param("evento", hecho.eventoId())
                        .param("cuando", Timestamp.from(cuando))
                        .param("secuencia", hecho.secuencia())
                        .query(Long.class)
                        .optional();
        if (escrito.isEmpty()) {
            // EL HUECO DECLARADO DE C-8, dicho en voz alta en vez de fallar con un choque de
            // clave. `valuacion_predio` tiene la clave (municipalidad, ejercicio, predio) y `V5`
            // no le da UPDATE al ingestor: un hecho sellado no se sustituye. Asi que una SEGUNDA
            // corrida del mismo ejercicio no puede aterrizar sus valuaciones, y lo que hay que
            // hacer no es reintentar sino decidir como se corrige una valuacion ya publicada.
            throw new NoSePuedeAplicar(
                    "El ejercicio "
                            + valuacion.ejercicio()
                            + " ya tiene una valuacion sellada del predio "
                            + valuacion.predioId()
                            + ", y este hecho ("
                            + hecho.eventoId()
                            + ") trae otra. `valuacion_predio` no admite UPDATE (ADR-0027 §1,"
                            + " `V5`): una valuacion no se sustituye. Reintentar no cambia nada —"
                            + " hay que decidir como se corrige una valuacion ya publicada");
        }
        return Aplicacion.APLICADO;
    }

    private Aplicacion aplicarCorrida(HechoRecibido hecho, Instant cuando) {
        CuerpoDelHecho.Corrida corrida = cuerpos.corrida(hecho.cuerpo());
        Optional<Integer> escrito =
                jdbc.sql(
                                """
                                INSERT INTO valuacion_corrida (municipalidad_id, ejercicio,
                                                               corrida_id, conjunto_id,
                                                               fecha_de_corte, reglas_version,
                                                               conteo, huella, cerrada_en,
                                                               recibida_en, evento_id, secuencia)
                                VALUES (%s, :ejercicio, :corrida, :conjunto, :corte,
                                        :reglasVersion, :conteo, :huella, :cerrada, :cuando,
                                        :evento, :secuencia)
                                ON CONFLICT (municipalidad_id, ejercicio) DO UPDATE
                                   SET corrida_id = EXCLUDED.corrida_id,
                                       conjunto_id = EXCLUDED.conjunto_id,
                                       fecha_de_corte = EXCLUDED.fecha_de_corte,
                                       reglas_version = EXCLUDED.reglas_version,
                                       conteo = EXCLUDED.conteo,
                                       huella = EXCLUDED.huella,
                                       cerrada_en = EXCLUDED.cerrada_en,
                                       recibida_en = EXCLUDED.recibida_en,
                                       evento_id = EXCLUDED.evento_id,
                                       secuencia = EXCLUDED.secuencia
                                 WHERE valuacion_corrida.secuencia < EXCLUDED.secuencia
                                RETURNING ejercicio
                                """
                                        .formatted(MUNICIPALIDAD_ACTUAL))
                        .param("ejercicio", corrida.ejercicio())
                        .param("corrida", corrida.corridaId())
                        .param("conjunto", corrida.conjuntoId())
                        .param("corte", corrida.fechaDeCorte())
                        .param("reglasVersion", corrida.reglasVersion())
                        .param("conteo", corrida.conteo())
                        .param("huella", corrida.huella())
                        .param("cerrada", Timestamp.from(corrida.cerradaEn()))
                        .param("cuando", Timestamp.from(cuando))
                        .param("evento", hecho.eventoId())
                        .param("secuencia", hecho.secuencia())
                        .query(Integer.class)
                        .optional();
        // Un cierre VIEJO que llega tarde no pisa al que ya esta: sin esto, el candado de
        // ADR-0027 §2 compararia lo recibido contra el conteo de una corrida anterior.
        return escrito.isEmpty() ? Aplicacion.DESCARTADO_POR_VIEJO : Aplicacion.APLICADO;
    }

    /**
     * El importe, de cadena a {@code BigDecimal}, sin pasar por ningun {@code double}.
     *
     * <p>Regla 1 y RNF-055. El emisor lo manda como cadena por lo mismo, y este es el unico sitio
     * donde los bytes se convierten.
     */
    private static @Nullable BigDecimal importe(@Nullable String texto) {
        return texto == null || texto.isBlank() ? null : new BigDecimal(texto);
    }

    /** El largo de {@code catastro_evento_muerto.motivo}. */
    private static String recortar(String motivo) {
        return motivo.length() <= 400 ? motivo : motivo.substring(0, 400);
    }
}
