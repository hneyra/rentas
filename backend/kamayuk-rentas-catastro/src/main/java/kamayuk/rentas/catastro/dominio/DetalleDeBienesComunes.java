package kamayuk.rentas.catastro.dominio;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;

/**
 * El detalle de una ficha de bienes comunes (RF-003): las areas comunes de una edificacion y como
 * se reparten entre sus unidades.
 *
 * <p>La ficha cuelga del predio que <b>es</b> la edificacion, y las participaciones apuntan a los
 * predios que son sus unidades. No hace falta una tabla de edificaciones: una edificacion en
 * propiedad exclusiva y comun ya tiene su propio codigo de referencia catastral, que es lo que la
 * ruta {@code /catastro/fichas/bienes-comunes/{codEdificacion}} recibe.
 *
 * <p>Aqui <b>no se suma nada ni se reparte nada</b>: repartir el valor de lo comun entre las
 * unidades es una regla tributaria, vive en rentas y esta bloqueada por D-02a.
 */
public record DetalleDeBienesComunes(
        List<BienComun> bienes, List<ParticipacionComun> participaciones)
        implements DetalleDeLaFicha {

    public DetalleDeBienesComunes {
        Objects.requireNonNull(bienes, "La lista de bienes comunes es vacia, no nula");
        Objects.requireNonNull(participaciones, "La lista de participaciones es vacia, no nula");
        bienes = List.copyOf(bienes);
        participaciones = List.copyOf(participaciones);
    }

    public static DetalleDeBienesComunes de(BienComun... bienes) {
        return new DetalleDeBienesComunes(List.of(bienes), List.of());
    }

    @Override
    public TipoFicha tipo() {
        return TipoFicha.BIENES_COMUNES;
    }

    public DetalleDeBienesComunes repartidoEntre(List<ParticipacionComun> otras) {
        return new DetalleDeBienesComunes(bienes, otras);
    }

    /** El area comun total de la edificacion. Es una suma de areas, no un importe. */
    public AreaM2 areaComunTotal() {
        return bienes.stream().map(BienComun::area).reduce(AreaM2.CERO, AreaM2::mas);
    }
}
