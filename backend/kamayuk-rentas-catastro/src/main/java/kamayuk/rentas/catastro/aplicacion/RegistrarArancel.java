package kamayuk.rentas.catastro.aplicacion;

import java.util.Objects;
import kamayuk.rentas.catastro.dominio.Arancel;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve una via por su codigo y carga su arancel contra un conjunto de parametros (#17).
 *
 * <p>Existe separado de {@link TablasDeValuacion#cargarArancel} por el mismo motivo que {@link
 * RegistrarVia} existe separado de {@code ViaRepository.save}: la resolucion del codigo de via
 * necesita ocurrir <b>dentro</b> de la misma transaccion que la carga para que la politica RLS vea
 * el contexto de tenant que {@code TenantTransactionManager} fija al abrir esa transaccion. Si
 * {@link ImportarArancel} llamara a {@link kamayuk.rentas.catastro.dominio.ViaRepository} directo y
 * despues, en otra llamada, a {@link TablasDeValuacion#cargarArancel}, la lectura de la via
 * ocurriria fuera de una transaccion administrada y RLS la dejaria vacia siempre —no por un error
 * de permisos, sino porque nadie fijo {@code app.municipalidad_id} para esa consulta suelta—.
 *
 * <p>Como {@link RegistrarVia}, este es el caso de uso que {@link ImportarArancel} llama una vez
 * por fila, fuera de cualquier transaccion ambiente: cada fila abre y cierra la suya.
 */
@Service
public class RegistrarArancel {

    private final ViaRepository vias;
    private final TablasDeValuacion tablas;

    public RegistrarArancel(ViaRepository vias, TablasDeValuacion tablas) {
        this.vias = vias;
        this.tablas = tablas;
    }

    @Transactional
    public Arancel registrar(
            String codigoVia,
            @Nullable String tramo,
            ValorNormativo valorM2,
            String documentoFuente,
            IdentificadorDeConjunto conjunto,
            Observacion observacion) {
        Via via =
                vias.findByCodigo(codigoVia)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No existe ninguna via con el codigo '"
                                                        + codigoVia
                                                        + "'"));
        long viaId = Objects.requireNonNull(via.id(), "Una via leida de la base trae su id");
        Arancel nuevo = Arancel.nuevo(viaId, tramo, valorM2, documentoFuente);
        return tablas.cargarArancel(nuevo, conjunto, observacion);
    }
}
