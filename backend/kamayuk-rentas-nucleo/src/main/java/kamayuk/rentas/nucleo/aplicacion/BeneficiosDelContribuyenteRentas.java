package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.BeneficiosDelContribuyente;
import kamayuk.rentas.nucleo.dominio.Beneficio;
import kamayuk.rentas.nucleo.dominio.BeneficioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link BeneficiosDelContribuyente} sobre {@link BeneficioRepository} (#42, RF-107).
 *
 * <p>Solo proyecta: no calcula ningun descuento, y por eso no depende de ningun parametro sellado.
 * Lo que sale es lo que la fila del beneficio dice de si misma.
 *
 * <p>{@code @Transactional(readOnly = true)} en el metodo publico y no en quien llama: sin
 * transaccion no hay {@code SET LOCAL}, y sin el la politica RLS <b>falla</b> en vez de devolver
 * filas. Que lo traiga el puerto es lo que permite que un contexto ajeno lo consuma sin saber nada
 * de eso —el mismo defecto que la marcha blanca de la seguridad destapo en {@code GET
 * /catastro/vias}—.
 */
@Service
public class BeneficiosDelContribuyenteRentas implements BeneficiosDelContribuyente {

    private final BeneficioRepository beneficios;

    public BeneficiosDelContribuyenteRentas(BeneficioRepository beneficios) {
        this.beneficios = beneficios;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate aLaFecha) {
        List<Beneficio> suyos = beneficios.vigentesDelContribuyente(contribuyenteId, aLaFecha);
        List<BeneficioRegistrado> proyectados = new ArrayList<>(suyos.size());
        for (Beneficio beneficio : suyos) {
            proyectados.add(
                    new BeneficioRegistrado(
                            beneficio.tipo(),
                            beneficio.clase().name(),
                            beneficio.tributo(),
                            beneficio.porcentaje(),
                            beneficio.monto(),
                            beneficio.baseLegal(),
                            beneficio.vigenciaDesde(),
                            beneficio.vigenciaHasta()));
        }
        return List.copyOf(proyectados);
    }
}
