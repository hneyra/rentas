package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.compartido.MarcoGeografico;
import org.jspecify.annotations.Nullable;

/**
 * Con que se acota el plano catastral: un marco, y los dos filtros con que se busca en ventanilla
 * (ADR-0022 §2).
 *
 * <p><b>El marco es obligatorio</b> y por eso no es {@code @Nullable}: sin el, la consulta seria el
 * padron entero, que es lo que esta lectura existe para no hacer. Los otros dos son los mismos que
 * la pantalla dibuja —«Sector» y «Manzana»—, por codigo, como en {@link FiltroDePredios}, y viven
 * en {@link AcotacionDelPlano} porque el <b>marco de lo levantado</b> (#612) los usa igual y sin
 * marco: son la unica forma de que las dos lecturas respondan sobre el mismo conjunto de predios.
 *
 * <p><b>No lleva ni estado ni fichado</b>, que si tiene {@link FiltroDePredios}. No es un olvido:
 * el contrato declara cuatro parametros para esta operacion y esos dos no estan. El plano dibuja lo
 * que hay levantado, no la cola de saneamiento —esa se lee en la grilla, que es donde se puede
 * leer, porque un predio sin ficha y sin poligono no tiene como dibujarse—.
 */
public record FiltroDelPlano(MarcoGeografico marco, AcotacionDelPlano acotacion) {

    public FiltroDelPlano {
        Objects.requireNonNull(marco, "El plano se pide siempre acotado por un marco");
        Objects.requireNonNull(acotacion, "El plano necesita su acotacion, aunque no acote nada");
    }

    public FiltroDelPlano(
            MarcoGeografico marco,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana) {
        this(marco, new AcotacionDelPlano(codigoDeSector, codigoDeManzana));
    }

    public @Nullable String codigoDeSector() {
        return acotacion.codigoDeSector();
    }

    public @Nullable String codigoDeManzana() {
        return acotacion.codigoDeManzana();
    }
}
