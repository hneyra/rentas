package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link LectorDeFichasEconomicas} sobre {@link FichaCatastralRepository} (#44).
 *
 * <p>Es la misma consulta que {@link LectorDeFichasCatastro} hace para la ficha unica, con el otro
 * tipo. Va en su propia clase porque su interfaz es otra; ver {@code LectorDeFichasEconomicas} para
 * por que son dos y no una.
 *
 * <p>El {@code @Transactional(readOnly = true)} es el que abre la transaccion donde se emite el
 * {@code SET LOCAL} que la politica RLS de {@code ficha_catastral} consulta. Sin el, la lectura no
 * devuelve vacio: falla.
 */
@Service
public class LectorDeFichasEconomicasCatastro implements LectorDeFichasEconomicas {

    private final FichaCatastralRepository repositorio;

    public LectorDeFichasEconomicasCatastro(FichaCatastralRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> fichaEconomicaVigenteEn(long predioId, LocalDate fecha) {
        return repositorio.vigenteA(predioId, TipoFicha.ECONOMICA, fecha).map(FichaCatastral::id);
    }
}
