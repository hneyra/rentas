package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.jspecify.annotations.Nullable;

/**
 * Un lote del plano catastral: quien es, donde esta y su poligono (ADR-0022 §1).
 *
 * <h2>Lo que NO lleva, que es la mitad de este tipo</h2>
 *
 * <p><b>Ni el titular.</b> Una lectura espacial que devolviera el dueno de cada lote seria el
 * extractor masivo del padron que {@code GET /catastro/predios} evita a proposito: quien es el
 * propietario se resuelve al clic, de un predio cada vez, por {@code
 * /catastro/predios/{predioId}/titulares}, que exige el permiso del padron y deja su fila de ACCESO
 * (ADR-0015 §2.4, #366). Aqui no hay ni el nombre ni el codigo.
 *
 * <p><b>Ni ningun area.</b> Ni la del poligono ni la de la ficha. La del poligono no es la
 * imponible —la imponible es {@code ficha_catastral.area_terreno}, la que midio el tecnico
 * (ADR-0021)— y publicarlas juntas invita a compararlas en una pantalla donde no se decide nada:
 * que no cuadren es un hallazgo que se informa, con su acto y su observacion, y no lo informa un
 * mapa.
 *
 * <p><b>Ni ningun importe.</b> Un color es un rango y una cifra normativa no lo es; el arancel se
 * lee donde se lee, con su ejercicio y su documento fuente al lado (ADR-0022 §5).
 *
 * <h2>Lo que si lleva</h2>
 *
 * <p>La identidad —{@code predioId} y el codigo de referencia catastral— y la ubicacion
 * administrativa. El sector y la manzana salen <b>por codigo</b>, igual que en {@link
 * PredioDelCatastro}, y sirven para lo que ADR-0022 §5 decidio que esas dos capas hacen: colorear y
 * rotular los lotes por su manzana y su sector. No se dibuja el perimetro de ninguna de las dos,
 * porque no lo tienen: derivarlo de la union de los lotes digitalizados seria publicar un lindero
 * que nadie levanto.
 *
 * <h2>Y por que lleva el estado</h2>
 *
 * <p>Porque un predio <b>retirado</b> del padron sigue siendo un terreno, y el plano tiene las dos
 * maneras de mentir sobre el: dibujarlo como uno mas dice que sigue en el padron, y <b>no dibujarlo
 * deja un hueco</b>, que es exactamente lo que ADR-0022 §2 no quiere —un hueco en un plano se lee
 * como «ahi no hay lote», no como «ese lote esta de baja»—. Asi que sale, y sale dicho.
 *
 * @param geometria el poligono en GeoJSON, tal como PostGIS lo serializa desde la columna: ni
 *     reproyectado ni simplificado. Viaja como texto porque en esta capa es un dato opaco —el
 *     dominio no interpreta GeoJSON—; quien lo convierte en objeto JSON es la capa web
 */
public record LoteDelPlano(
        long predioId,
        CodigoReferenciaCatastral codigo,
        String direccion,
        @Nullable String codigoDeSector,
        @Nullable String codigoDeManzana,
        @Nullable String lote,
        EstadoPredio estado,
        String geometria) {

    public LoteDelPlano {
        Objects.requireNonNull(codigo, "El lote necesita su codigo de referencia catastral");
        Objects.requireNonNull(direccion, "El lote necesita su direccion");
        Objects.requireNonNull(estado, "El lote necesita su estado");
        Objects.requireNonNull(geometria, "Un lote del plano sin poligono no es un lote del plano");
    }
}
