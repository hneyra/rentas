package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.PrediosSinConciliar;
import org.springframework.stereotype.Service;

/**
 * Lo que {@code rentas} le contesta al panel de trabajo parado (#549, ADR-0015).
 *
 * <p><b>No tiene consulta propia.</b> Delega en {@link ConsultaDeConciliacion#resumen}, que es el
 * recuento que #564 construyo y que {@code GET /catastro/fichas/conciliacion/resumen} ya publica:
 * un solo agregado con su ejercicio y su fecha de corte. Escribir aqui un {@code count} propio
 * seria la segunda definicion de «sin conciliar» que el AC 2.4 de #549 existe para impedir, y ese
 * predicado ya se contradijo una vez consigo mismo (#564).
 *
 * <p><b>Sin {@code @Transactional} propia</b>, y a proposito: la abre {@code
 * ConsultaDeConciliacion.resumen}, que es donde vive el SQL. Este servicio no hace mas que traducir
 * el resumen a la unica cifra que el panel necesita, asi que una transaccion aqui solo anadiria un
 * anfitrion sin ninguna consulta propia — el reparto que #54 y #72 dejaron escrito.
 */
@Service
public class PrediosSinConciliarRentas implements PrediosSinConciliar {

    private final ConsultaDeConciliacion conciliacion;

    public PrediosSinConciliarRentas(ConsultaDeConciliacion conciliacion) {
        this.conciliacion = conciliacion;
    }

    @Override
    public long cuantosA(Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(ejercicio, "No hay «sin conciliar»: hay sinConciliarA(ejercicio)");
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");
        return conciliacion.resumen(ejercicio, aLaFecha).noConciliados();
    }
}
