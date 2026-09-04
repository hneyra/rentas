package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del listado de predios del catastro.
 *
 * <p>Publica la ubicacion por <b>codigo</b> —los mismos que la correccion del predio recibe— y no
 * por identificador interno: la interfaz lee esta fila y con ella rellena el formulario que vuelve
 * al servidor, asi que cualquier otra cosa la obligaria a traducir en medio.
 *
 * <p>De la via salen su codigo y su nombre. El nombre no es redundante: el codigo es lo que viaja y
 * el nombre es lo unico que se puede leer en ventanilla.
 *
 * <p>Ni un importe (regla 5) y ni un titular: quien es el propietario de un predio se resuelve al
 * clic, de un predio cada vez, por {@code /catastro/predios/{predioId}/titulares} —eso es ADR-0015
 * §2.4, y publicarlo aqui convertiria «quien puede listar predios» en «quien puede cosechar la
 * correlacion predio→persona de toda la municipalidad»—.
 */
public record PredioDelCatastroResource(
        long predioId,
        String codRefCatastral,
        String tipo,
        String direccion,
        @Nullable String numeroMunicipal,
        @Nullable String codigoDeVia,
        @Nullable String via,
        @Nullable String codigoDeSector,
        @Nullable String codigoDeManzana,
        @Nullable String lote,
        @Nullable String ubigeo,
        String estado,
        boolean fichado) {

    public static PredioDelCatastroResource de(PredioDelCatastro predio) {
        return new PredioDelCatastroResource(
                predio.predioId(),
                predio.codigo().valor(),
                predio.tipo().name(),
                predio.direccion(),
                predio.numeroMunicipal(),
                predio.codigoDeVia(),
                predio.nombreDeVia(),
                predio.codigoDeSector(),
                predio.codigoDeManzana(),
                predio.lote(),
                predio.ubigeo(),
                predio.estado().name(),
                predio.fichado());
    }
}
