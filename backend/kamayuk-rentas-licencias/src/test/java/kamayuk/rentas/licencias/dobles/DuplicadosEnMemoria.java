package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicenciaRepository;

/** Un {@link DuplicadoDeLicenciaRepository} en memoria. */
public final class DuplicadosEnMemoria implements DuplicadoDeLicenciaRepository {

    private final List<DuplicadoDeLicencia> duplicados = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public DuplicadoDeLicencia registrar(DuplicadoDeLicencia duplicado) {
        boolean repetido =
                duplicados.stream()
                        .anyMatch(
                                d ->
                                        d.licenciaId() == duplicado.licenciaId()
                                                && d.numero() == duplicado.numero());
        if (repetido) {
            throw new DuplicadoDuplicado(
                    "La licencia ya tiene un duplicado numero " + duplicado.numero(),
                    new IllegalStateException("licencia_duplicado_uq"));
        }
        DuplicadoDeLicencia conId =
                new DuplicadoDeLicencia(
                        siguienteId++,
                        duplicado.licenciaId(),
                        duplicado.numero(),
                        duplicado.fecha(),
                        duplicado.motivo(),
                        duplicado.reciboId(),
                        duplicado.documentoId(),
                        duplicado.reimpresion(),
                        duplicado.registradoEn(),
                        "prueba",
                        duplicado.observacion());
        duplicados.add(conId);
        return conId;
    }

    @Override
    public int cuantosDe(long licenciaId) {
        return (int) duplicados.stream().filter(d -> d.licenciaId() == licenciaId).count();
    }

    @Override
    public List<DuplicadoDeLicencia> deLicencia(long licenciaId) {
        return duplicados.stream().filter(d -> d.licenciaId() == licenciaId).toList();
    }
}
