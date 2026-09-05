package kamayuk.rentas.nucleo.infraestructura;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellaDelLote;
import kamayuk.rentas.catastro.HuellasDeLaProyeccion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las huellas de {@code predio_ref}, calculadas en Java con la misma funcion que el origen.
 *
 * <p><b>Vive en este modulo y no en el del adaptador cliente</b>, y eso es deliberado: {@code
 * kamayuk-rentas-catastro} no tiene ni una consulta —su javadoc lo dice y el escaner de frontera lo
 * comprueba—, y {@code predio_ref} es una tabla de ESTE sistema (la crea `V4` de `rentas`), leida
 * ya desde aqui por {@code ConciliacionRepositoryJdbc}. El puerto se declara alli, con el resto del
 * vocabulario de la anti-entropia; lo que se implementa aqui es la consulta.
 *
 * <h2>Por que en Java y no en SQL</h2>
 *
 * <p>PostgreSQL sabe hacer {@code sha256} y {@code string_agg(... ORDER BY ...)}, y saldria una
 * consulta en vez de un recorrido. Se calcula en Java a proposito: la huella tiene que ser <b>la
 * misma</b> que calcula {@code catastro}, y alli se calcula con {@link HuellaDelLote}. Dos
 * implementaciones —una en SQL, otra en Java— son dos sitios donde el separador, el orden o la
 * codificacion pueden divergir, y divergir ahi no falla ruidosamente: o todos los sectores salen
 * discrepantes o ninguno, y las dos cosas se leen como un problema de datos siendo de codigo.
 *
 * <p>La funcion la fijan los vectores de oro de {@code docs/50-api/anti-entropia/}, que las dos
 * implementaciones reproducen cada una en su CI.
 *
 * <p><b>Ninguna consulta filtra por {@code municipalidad_id}</b> (regla 2): lo hace la politica RLS
 * con el contexto que fijo la transaccion del caso de uso.
 */
@Repository
public class HuellasDeLaProyeccionJdbc extends RepositorioJdbc implements HuellasDeLaProyeccion {

    /**
     * Las cinco columnas que la proyeccion copia del origen, en el orden del recorrido.
     *
     * <p>{@code ORDER BY predio_id}: la huella del sector se compone por identificador ascendente,
     * y esa decision esta fijada en los vectores con un caso de tres lotes y su inverso.
     */
    private static final String LOTES =
            """
            SELECT predio_id,
                   codigo_ref_catastral,
                   direccion,
                   sector_codigo,
                   estado
              FROM predio_ref
             ORDER BY predio_id
            """;

    public HuellasDeLaProyeccionJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public List<AntiEntropia.HuellaDeSector> porSector() {
        List<Fila> filas =
                jdbc().sql(LOTES)
                        .query(
                                (rs, numero) ->
                                        new Fila(
                                                rs.getLong("predio_id"),
                                                rs.getString("codigo_ref_catastral"),
                                                rs.getString("direccion"),
                                                rs.getString("sector_codigo"),
                                                rs.getString("estado")))
                        .list();

        // Se ordena por (sector, predioId) y se agrupa recorriendo, no con un `HashMap`: un
        // mapa perderia el orden dentro de cada sector, que es justo lo que decide la huella.
        List<Fila> porSector = new ArrayList<>(filas);
        porSector.sort(
                Comparator.comparing(
                                (Fila fila) ->
                                        fila.sectorCodigo() == null ? "" : fila.sectorCodigo())
                        .thenComparingLong(Fila::predioId));

        List<AntiEntropia.HuellaDeSector> huellas = new ArrayList<>();
        List<String> deEsteSector = new ArrayList<>();
        String sectorEnCurso = null;
        boolean empezado = false;

        for (Fila fila : porSector) {
            if (empezado && !Objects.equals(sectorEnCurso, fila.sectorCodigo())) {
                huellas.add(cerrar(sectorEnCurso, deEsteSector));
                deEsteSector = new ArrayList<>();
            }
            sectorEnCurso = fila.sectorCodigo();
            empezado = true;
            deEsteSector.add(fila.huella());
        }
        if (empezado) {
            huellas.add(cerrar(sectorEnCurso, deEsteSector));
        }
        return List.copyOf(huellas);
    }

    private static AntiEntropia.HuellaDeSector cerrar(
            @Nullable String sector, List<String> huellasDeSusLotes) {
        return new AntiEntropia.HuellaDeSector(
                sector,
                huellasDeSusLotes.size(),
                HuellaDelLote.deUnSector(List.copyOf(huellasDeSusLotes)));
    }

    private record Fila(
            long predioId,
            String codRefCatastral,
            String direccion,
            @Nullable String sectorCodigo,
            String estado) {

        String huella() {
            return HuellaDelLote.deUnLote(
                    predioId, codRefCatastral, direccion, sectorCodigo, estado);
        }
    }
}
