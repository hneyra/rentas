package kamayuk.rentas.coactiva.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;

/**
 * Un {@link ActoCoactivoRepository} en memoria, para probar el transporte HTTP sin base de datos.
 *
 * <p>Imita el indice unico parcial {@code acto_rec1_uq} —una sola REC-1 por expediente—. Que la
 * <b>garantia</b> sea de la base y no de este {@code if} lo demuestra {@code
 * ActosCoactivosJdbcTest} con diez peticiones concurrentes de verdad; aqui solo se imita para que
 * el controlador se pueda probar sin PostgreSQL.
 *
 * <p>Solo agrega, igual que la tabla: no hay aqui ningun metodo que actualice.
 */
public final class ActosEnMemoria implements ActoCoactivoRepository {

    private final List<ActoCoactivo> guardados = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public ActoCoactivo registrar(ActoCoactivo acto) {
        if (acto.tipo() == TipoDeActoCoactivo.REC1 && rec1De(acto.expedienteId()).isPresent()) {
            throw new Rec1Duplicada(
                    "El expediente " + acto.expedienteId() + " ya tiene su REC-1",
                    new IllegalStateException("indice unico imitado: acto_rec1_uq"));
        }
        ActoCoactivo conId =
                new ActoCoactivo(
                        siguienteId++,
                        acto.expedienteId(),
                        acto.tipo(),
                        acto.numero(),
                        acto.fecha(),
                        acto.descripcion(),
                        acto.medida(),
                        acto.rec1NotificacionId(),
                        acto.rec1ExigibleDesde(),
                        acto.documentoId(),
                        acto.registradoEn(),
                        "prueba",
                        acto.observacion());
        guardados.add(conId);
        return conId;
    }

    @Override
    public List<ActoCoactivo> deExpediente(long expedienteId) {
        return guardados.stream().filter(a -> a.expedienteId() == expedienteId).toList();
    }

    @Override
    public Optional<ActoCoactivo> rec1De(long expedienteId) {
        return ultimoDe(expedienteId, TipoDeActoCoactivo.REC1);
    }

    @Override
    public Optional<ActoCoactivo> ultimoDe(long expedienteId, TipoDeActoCoactivo tipo) {
        return deExpediente(expedienteId).stream()
                .filter(a -> a.tipo() == tipo)
                .reduce((primero, segundo) -> segundo);
    }

    @Override
    public Optional<ActoCoactivo> porNumero(String numero) {
        return guardados.stream().filter(a -> a.numero().equalsIgnoreCase(numero)).findFirst();
    }
}
