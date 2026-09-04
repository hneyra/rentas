package kamayuk.rentas.verificaciones;

import java.util.List;
import kamayuk.comun.verificaciones.contrato.VectoresDeHuellaTestBase;
import kamayuk.rentas.catastro.HuellaDelLote;
import org.junit.jupiter.api.DisplayName;

/**
 * La huella que calcula ESTE repositorio es la del archivo de vectores.
 *
 * <p>`rentas` es quien publica el archivo —`docs/50-api/anti-entropia/huella-del-lote.json`— y
 * `catastro` lo reproduce con su propia implementacion. Uno solo lo genera a proposito: si los dos
 * pudieran regenerarlo, quien cambiara el algoritmo regeneraria el archivo y el rojo se convertiria
 * en un diff que alguien acepta.
 */
@DisplayName("Vectores de la huella de la anti-entropia")
class VectoresDeHuellaTest extends VectoresDeHuellaTestBase {

    @Override
    protected String repositorioQuePublica() {
        return "rentas";
    }

    @Override
    protected String huellaDeUnLote(
            long predioId,
            String codRefCatastral,
            String direccion,
            String sectorCodigo,
            String estado) {
        return HuellaDelLote.deUnLote(predioId, codRefCatastral, direccion, sectorCodigo, estado);
    }

    @Override
    protected String huellaDeUnSector(List<String> huellasDeSusLotes) {
        return HuellaDelLote.deUnSector(huellasDeSusLotes);
    }
}
