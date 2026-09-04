package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta y actualizacion de la ficha del vehiculo, con su observacion y su auditoria.
 *
 * <p>La {@link Observacion} esta en la firma, no en el cuerpo de la peticion: es un argumento sin
 * el cual el metodo no se puede llamar (regla 10, RNF-052).
 */
@Service
public class RegistrarVehiculo {

    private final VehiculoRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarVehiculo(VehiculoRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Transactional
    public Vehiculo registrar(Vehiculo vehiculo, Observacion observacion) {
        boolean esAlta = vehiculo.esNuevo();
        Vehiculo guardado = repositorio.save(vehiculo);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "vehiculo",
                                String.valueOf(guardado.id()),
                                esAlta ? Operacion.ALTA : Operacion.MODIFICACION,
                                observacion)
                        .con(null, FichaEnJson.de(guardado)));

        return guardado;
    }
}
