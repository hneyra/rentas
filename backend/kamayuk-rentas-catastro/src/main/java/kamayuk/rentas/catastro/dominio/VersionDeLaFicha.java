package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del historico de una ficha: <b>que cambio, cuando, quien y por que</b>.
 *
 * <p>Es un modelo de lectura, no la ficha. La ficha tiene construcciones, detalle y area; esto
 * tiene autor y motivo. El versionado de #18 guarda las dos cosas, pero solo la primera se leia:
 * sin el {@code usuario_registro} y la {@code fecha_registro} el historico es una lista de areas
 * distintas, y quien atiende una reclamacion no puede decir quien la cambio ni con que documento.
 *
 * <p><b>La observacion es la mitad util.</b> Un diff dice que el area paso de 120 a 180; solo la
 * observacion dice que fue una fiscalizacion de campo y no un error de tecleo, y es lo que se lee
 * en voz alta cuando el contribuyente pregunta por que le subio el recibo (regla 10, RNF-052).
 *
 * @param vigenciaHasta nulo en la version vigente
 * @param registradaEn cuando se escribio la fila, que no es cuando empezo a regir
 */
public record VersionDeLaFicha(
        long id,
        int version,
        AreaM2 areaTerreno,
        String uso,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        OrigenDeLaFicha origen,
        String documentoOrigen,
        Observacion observacion,
        String usuario,
        OffsetDateTime registradaEn) {

    public VersionDeLaFicha {
        Objects.requireNonNull(areaTerreno, "La version registrada tiene su area");
        Objects.requireNonNull(uso, "La version registrada tiene su uso");
        Objects.requireNonNull(vigenciaDesde, "La version registrada tiene desde cuando rigio");
        Objects.requireNonNull(origen, "La version registrada dice de donde salio");
        Objects.requireNonNull(documentoOrigen, "La version registrada tiene su documento");
        Objects.requireNonNull(
                observacion, "Una version sin observacion no explica nada (regla 10, RNF-052)");
        Objects.requireNonNull(usuario, "El historico sin autor no sirve para responder a nadie");
        Objects.requireNonNull(registradaEn, "El historico necesita cuando se escribio la fila");
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }
}
