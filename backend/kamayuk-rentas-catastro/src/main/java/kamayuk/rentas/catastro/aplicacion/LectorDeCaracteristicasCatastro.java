package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementacion de {@link LectorDeCaracteristicas}. */
@Service
public class LectorDeCaracteristicasCatastro implements LectorDeCaracteristicas {

    private final CatastroRepository catastro;
    private final FichaCatastralRepository fichas;

    public LectorDeCaracteristicasCatastro(
            CatastroRepository catastro, FichaCatastralRepository fichas) {
        this.catastro = catastro;
        this.fichas = fichas;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
        Optional<Predio> predio = catastro.predio(predioId);
        if (predio.isEmpty()) {
            return Optional.empty();
        }

        // Una sola lectura de la ficha para el uso y el area: son de la misma version, y pedirla
        // dos veces abriria la puerta a que el uso saliera de una y el area de otra.
        Optional<kamayuk.rentas.catastro.dominio.FichaCatastral> vigente =
                fichas.vigenteA(predioId, TipoFicha.UNICA, fecha);
        String uso = vigente.map(ficha -> ficha.uso()).orElse(null);
        kamayuk.rentas.dominio.AreaM2 area = vigente.map(ficha -> ficha.areaTerreno()).orElse(null);

        Long sectorId = predio.get().sectorId();
        String sectorCodigo =
                sectorId == null
                        ? null
                        : catastro.sectorPorId(sectorId).map(Sector::codigo).orElse(null);

        return Optional.of(new CaracteristicasDelPredio(uso, sectorCodigo, area));
    }
}
