package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.jspecify.annotations.Nullable;

/**
 * Un predio como lo ve el propio catastro: sus datos, su ubicacion resuelta a <b>codigos</b> y si
 * llego a ficharse.
 *
 * <p>No es {@link kamayuk.rentas.catastro.PredioDelPadron}, y la diferencia importa. Aquel es el
 * puerto que fiscalizacion recorre para detectar omisos, y por eso deja fuera a proposito los
 * predios <b>dados de baja</b> y los que no tienen <b>titular vigente</b>: una fila suya es «a esta
 * persona hay que reclamarle». Esto es lo contrario: el padron entero tal como esta, incluidos los
 * retirados y los que nadie ha fichado todavia, que es justo lo que hay que poder ver para
 * arreglarlo.
 *
 * <p><b>Via, sector y manzana salen por codigo</b> —y la via, ademas, con su nombre para poder
 * leerla—. No por identificador interno: la correccion del predio los recibe por codigo, asi que
 * publicar el {@code id} obligaria a la interfaz a traducir entre lo que lee y lo que manda.
 *
 * @param fichado si el predio tiene al menos una ficha catastral registrada, de cualquier tipo y
 *     cualquier version. No dice «vigente hoy»: eso exigiria una fecha (regla 9), y lo que esta
 *     pregunta responde es si alguien llego a levantar la ficha, no si sigue al dia
 */
public record PredioDelCatastro(
        long predioId,
        CodigoReferenciaCatastral codigo,
        TipoPredio tipo,
        String direccion,
        @Nullable String numeroMunicipal,
        @Nullable String codigoDeVia,
        @Nullable String nombreDeVia,
        @Nullable String codigoDeSector,
        @Nullable String codigoDeManzana,
        @Nullable String lote,
        @Nullable String ubigeo,
        EstadoPredio estado,
        boolean fichado) {

    public PredioDelCatastro {
        Objects.requireNonNull(codigo, "El predio necesita su codigo de referencia catastral");
        Objects.requireNonNull(tipo, "El predio necesita su tipo");
        Objects.requireNonNull(direccion, "El predio necesita su direccion");
        Objects.requireNonNull(estado, "El predio necesita su estado");
    }
}
