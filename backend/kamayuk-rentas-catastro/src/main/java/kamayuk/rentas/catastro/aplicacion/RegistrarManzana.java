package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da de alta una manzana dentro de un sector.
 *
 * <p>Copia el patron de {@link RegistrarVia}, con una diferencia: quien registra una manzana la
 * identifica por el <b>codigo</b> de su sector, no por el identificador interno —es lo que trae un
 * archivo de importacion, y lo que teclearia una persona—. Resolver ese codigo es parte de la misma
 * transaccion que el alta: si el sector no existe, la fila se rechaza sin escribir nada ni dejar
 * auditoria de un alta que no ocurrio.
 *
 * <p>Una manzana no se edita (su codigo es un tramo del codigo catastral de sus predios), asi que
 * este caso de uso solo da de alta.
 */
@Service
public class RegistrarManzana {

    private final CatastroRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarManzana(CatastroRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Transactional
    public Manzana registrarPorCodigoDeSector(
            String sectorCodigo, String codigo, Observacion observacion) {
        Sector sector =
                repositorio
                        .sectorPorCodigo(sectorCodigo)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No existe el sector con codigo '"
                                                        + sectorCodigo
                                                        + "'"));

        long sectorId =
                Objects.requireNonNull(
                        sector.id(), "El sector leido de la base tiene identificador");
        Manzana guardada = repositorio.guardar(Manzana.nueva(sectorId, codigo));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "manzana",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(sector, guardada)));

        return guardada;
    }

    private static String descripcion(Sector sector, Manzana manzana) {
        return "{\"sectorCodigo\":\""
                + sector.codigo()
                + "\",\"codigo\":\""
                + manzana.codigo()
                + "\"}";
    }
}
