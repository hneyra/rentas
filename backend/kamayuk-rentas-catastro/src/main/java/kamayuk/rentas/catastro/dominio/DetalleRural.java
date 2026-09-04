package kamayuk.rentas.catastro.dominio;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.dominio.Medida;

/**
 * El detalle de una ficha rural (RF-004): los grupos de tierra y los colindantes.
 *
 * <p><b>No exige construcciones.</b> Un predio rustico sin ninguna edificacion es lo normal, y una
 * ficha que obligara a declarar al menos una obligaria al tecnico a inventarse un dato o a no
 * fichar el predio. Las construcciones, si las hay, van donde van en los cuatro tipos.
 *
 * <p>Los colindantes no se repiten por orientacion: la base lo sostiene con una restriccion unica y
 * este tipo tambien, para que el error salga donde se comete y no tres capas mas abajo.
 */
public record DetalleRural(List<TierraRural> tierras, List<Colindante> colindantes)
        implements DetalleDeLaFicha {

    public DetalleRural {
        Objects.requireNonNull(tierras, "La lista de grupos de tierra es vacia, no nula");
        Objects.requireNonNull(colindantes, "La lista de colindantes es vacia, no nula");
        tierras = List.copyOf(tierras);
        colindantes = List.copyOf(colindantes);

        Set<Orientacion> vistas = EnumSet.noneOf(Orientacion.class);
        for (Colindante colindante : colindantes) {
            if (!vistas.add(colindante.orientacion())) {
                throw new IllegalArgumentException(
                        "El predio ya declara un colindante por el "
                                + colindante.orientacion()
                                + "; dos por la misma orientacion no se pueden discutir por"
                                + " separado en una rectificacion de linderos");
            }
        }
    }

    public static DetalleRural de(TierraRural... tierras) {
        return new DetalleRural(List.of(tierras), List.of());
    }

    @Override
    public TipoFicha tipo() {
        return TipoFicha.RURAL;
    }

    public DetalleRural con(List<Colindante> otrosColindantes) {
        return new DetalleRural(tierras, otrosColindantes);
    }

    /**
     * La superficie total del predio, en hectareas.
     *
     * <p>Sumar hectareas es aritmetica, no una regla tributaria: aqui no se aplica ningun arancel.
     * {@link Medida#mas} rechaza sumar unidades distintas, asi que un grupo guardado en metros —que
     * el constructor de {@link TierraRural} ya impide— tampoco pasaria por aqui.
     */
    public Medida hectareasTotales() {
        return tierras.stream()
                .map(TierraRural::hectareas)
                .reduce(TierraRural.enHectareas("0"), Medida::mas);
    }
}
