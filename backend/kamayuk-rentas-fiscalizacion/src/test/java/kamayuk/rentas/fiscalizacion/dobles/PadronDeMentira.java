package kamayuk.rentas.fiscalizacion.dobles;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * El catastro de mentira: fichas y caracteristicas.
 *
 * <p><b>Solo lee.</b> No tiene un solo metodo que escriba, y eso es lo que hace comprobable el AC 4
 * de #49 —«nada de esto escribe en catastro ni en rentas»— desde el propio tipo: si algun dia
 * {@code fiscalizacion} intentara escribir, no habria por donde.
 *
 * <p>Desde #545 ya no implementa el padron: el cruce que dibuja {@code fisc_omisos} es una consulta
 * de {@code DeteccionRepository} y se prueba contra PostgreSQL, porque una condicion filtrada en
 * memoria no puede demostrar que el filtro acota el conjunto. Los titulares tampoco estan aqui:
 * {@code TitularesDelPredio.de(long, LocalDate)} y {@code LectorDeCaracteristicas.de(long,
 * LocalDate)} tienen la misma firma con distinto tipo de retorno, asi que una sola clase no puede
 * implementar los dos puertos — su doble es {@link TitularesDeMentira}.
 */
public final class PadronDeMentira implements LectorDeFichas, LectorDeCaracteristicas {

    private final Map<Long, AreaM2> areasPorFicha = new HashMap<>();
    private final Map<Long, CaracteristicasDelPredio> caracteristicas = new HashMap<>();
    private final Map<Long, Long> fichaVigentePorPredio = new HashMap<>();

    /** Registra una version de ficha con su area. */
    public PadronDeMentira conFicha(long fichaId, AreaM2 area) {
        areasPorFicha.put(fichaId, area);
        return this;
    }

    /** Lo que la ficha vigente de un predio dice a cualquier fecha. */
    public PadronDeMentira conCaracteristicas(
            long predioId, @Nullable String uso, @Nullable AreaM2 area, long fichaVigenteId) {
        caracteristicas.put(predioId, new CaracteristicasDelPredio(uso, "S-01", area));
        fichaVigentePorPredio.put(predioId, fichaVigenteId);
        return this;
    }

    @Override
    public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
        return Optional.ofNullable(fichaVigentePorPredio.get(predioId));
    }

    @Override
    public Optional<AreaM2> areaDeLaVersion(long fichaId) {
        return Optional.ofNullable(areasPorFicha.get(fichaId));
    }

    @Override
    public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
        return Optional.ofNullable(caracteristicas.get(predioId));
    }
}
