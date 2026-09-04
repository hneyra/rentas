package kamayuk.rentas.catastro.infraestructura.web;

import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.dominio.LoteDelPlano;
import kamayuk.rentas.catastro.dominio.PlanoDelCatastro;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * El plano de un marco, como sale por HTTP (ADR-0022).
 *
 * <p><b>Lista blanca, y aqui la lista es lo que el issue mide</b>: {@code lotes} y {@code
 * sinGeometria}, y por lote su identidad, su ubicacion y su poligono. Ni un titular, ni un importe,
 * ni un area —los tres motivos estan en {@link LoteDelPlano}—.
 *
 * <p><b>No hay ningun {@code truncado}, y su ausencia es la decision</b> (ADR-0022 §2): si en el
 * marco caben mas lotes de los que se sirven la respuesta es un {@code 422} con la cuenta, no una
 * pagina con una marca. Una marca la puede ignorar quien dibuja; un plano al que le faltan lotes se
 * lee como un plano donde no hay lotes. Por lo mismo no hay sobre paginado.
 *
 * @param sinGeometria cuantos predios alcanzados por los mismos filtros no tienen poligono. Sale
 *     <b>siempre</b>, cero incluido: sin esa cifra el visor afirma algo que no sabe
 */
public record PlanoCatastralResource(List<LoteDelPlanoResource> lotes, long sinGeometria) {

    public static PlanoCatastralResource de(PlanoDelCatastro plano) {
        return new PlanoCatastralResource(
                plano.lotes().stream().map(LoteDelPlanoResource::de).toList(),
                plano.sinGeometria());
    }

    /**
     * Un lote con su poligono.
     *
     * @param geometria el GeoJSON tal como {@code ST_AsGeoJSON} lo produjo, <b>como objeto</b> y no
     *     como cadena. Se lee y se vuelve a escribir en vez de viajar en crudo porque un GeoJSON
     *     entrecomillado obligaria a cada cliente a analizarlo otra vez, y porque asi lo que la
     *     forma derivada de la API declara —un objeto— es lo que el JSON lleva. Sus claves son
     *     {@code type} y {@code coordinates}, en ingles: son las de RFC 7946, y traducirlas
     *     produciria un GeoJSON que ninguna biblioteca de mapas sabe leer
     */
    public record LoteDelPlanoResource(
            long predioId,
            String codRefCatastral,
            String direccion,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            String estado,
            Map<String, Object> geometria) {

        /**
         * El lector del GeoJSON.
         *
         * <p>Sin ningun modulo del dominio a proposito: lo que se lee aqui no es una respuesta del
         * sistema sino texto que produjo PostGIS, y las coordenadas se dejan como Jackson las lee
         * para volver a escribirlas iguales.
         */
        private static final JsonMapper GEOJSON = JsonMapper.builder().build();

        static LoteDelPlanoResource de(LoteDelPlano lote) {
            return new LoteDelPlanoResource(
                    lote.predioId(),
                    lote.codigo().valor(),
                    lote.direccion(),
                    lote.codigoDeSector(),
                    lote.codigoDeManzana(),
                    lote.lote(),
                    lote.estado().name(),
                    comoObjeto(lote.geometria()));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> comoObjeto(String geoJson) {
            try {
                return GEOJSON.readValue(geoJson, Map.class);
            } catch (JacksonException ilegible) {
                // No puede pasar —lo escribio ST_AsGeoJSON sobre una columna tipada—, y si
                // pasara, publicar el texto crudo dejaria un campo que dice ser un objeto y
                // no lo es. Mejor que se vea.
                throw new IllegalStateException(
                        "PostGIS devolvio un GeoJSON que no se puede leer", ilegible);
            }
        }
    }
}
