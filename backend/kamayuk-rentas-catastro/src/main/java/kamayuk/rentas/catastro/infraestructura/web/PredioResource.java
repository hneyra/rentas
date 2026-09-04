package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Predio;
import org.jspecify.annotations.Nullable;

/**
 * El predio tal como sale por HTTP, con sus datos propios y sin los de su ficha.
 *
 * <p><b>Ni un identificador interno de via, sector o manzana.</b> Esos tres entran por codigo
 * —{@code codigoDeVia}, {@code codigoDeSector}, {@code codigoDeManzana}— y no salen: publicar sus
 * {@code id} obligaria a la interfaz a traducirlos para volver a mandarlos, y el identificador
 * interno no le dice nada a nadie en ventanilla. Quien necesite el nombre de la via o del sector lo
 * pide en el listado, que si los resuelve.
 *
 * <p>Ni un importe: el valor del predio sale del cuadro de valores unitarios, de la depreciacion y
 * del arancel, que son D-02a y no estan firmados.
 *
 * @param predioId el identificador con el que se dirigen las rutas de {@code /catastro/predios}
 */
public record PredioResource(
        long predioId,
        String codRefCatastral,
        String tipo,
        String direccion,
        @Nullable String numeroMunicipal,
        @Nullable String lote,
        @Nullable String ubigeo,
        String estado) {

    public static PredioResource de(Predio predio) {
        return new PredioResource(
                predio.id() == null ? 0L : predio.id(),
                predio.codigo().valor(),
                predio.tipo().name(),
                predio.direccion(),
                predio.numeroMunicipal(),
                predio.lote(),
                predio.ubigeo(),
                predio.estado().name());
    }
}
