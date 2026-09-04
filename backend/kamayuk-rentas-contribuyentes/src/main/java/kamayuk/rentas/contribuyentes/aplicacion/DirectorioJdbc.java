package kamayuk.rentas.contribuyentes.aplicacion;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.FichaRepository;
import kamayuk.rentas.contribuyentes.dominio.TipoDomicilio;
import kamayuk.rentas.dominio.CodigoContribuyente;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La cara que este contexto le da a los demas.
 *
 * <p>Se apoya en lo que ya existe —la busqueda por aproximacion de RF-011 y el domicilio vigente a
 * la fecha de RF-012— en vez de escribir consultas propias. Que un contexto vecino y la pantalla
 * del padron encuentren <b>al mismo contribuyente escribiendo lo mismo</b> no es cosmetico: si cada
 * uno tuviera su consulta, un dia divergirian y nadie sabria cual de las dos es la buena.
 *
 * <p>El domicilio no viaja en el resumen: sale aparte, y con la fecha a la que se pide. No «el
 * ultimo»: quien mude en setiembre no cambia la direccion a la que se notifico en marzo (regla 9).
 */
@Service
public class DirectorioJdbc implements DirectorioDeContribuyentes {

    private final ContribuyenteRepository padron;
    private final FichaRepository fichas;

    public DirectorioJdbc(ContribuyenteRepository padron, FichaRepository fichas) {
        this.padron = padron;
        this.fichas = fichas;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        if (maximo < 1) {
            throw new IllegalArgumentException(
                    "Buscar «como mucho cero» no devuelve nada y esconde el error: " + maximo);
        }
        String limpio = texto.strip();

        // Primero por codigo, que es exacto: quien escribe «C-000123» sabe a quien busca, y
        // pasarlo por la aproximacion lo enterraria entre nombres parecidos.
        Optional<ResumenDeContribuyente> exacto = porCodigo(limpio);
        if (exacto.isPresent()) {
            return List.of(exacto.get());
        }
        return padron
                .buscar(
                        CriterioDeBusqueda.porNombre(limpio),
                        Paginacion.de(0, maximo, "nombreRazonSocial"))
                .contenido()
                .stream()
                .map(this::resumir)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
        try {
            return padron.findByCodigo(CodigoContribuyente.de(codigo)).map(this::resumir);
        } catch (IllegalArgumentException noEsUnCodigo) {
            // Lo escrito no tiene forma de codigo. No es un error: es que el usuario estaba
            // escribiendo un nombre.
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ResumenDeContribuyente> porId = new LinkedHashMap<>();
        for (Contribuyente contribuyente : padron.findAllById(ids)) {
            ResumenDeContribuyente resumen = resumir(contribuyente);
            porId.put(resumen.id(), resumen);
        }
        return Map.copyOf(porId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
        return fichas.domicilioVigenteA(contribuyenteId, TipoDomicilio.FISCAL, fecha)
                .map(Domicilio::direccion);
    }

    private ResumenDeContribuyente resumir(Contribuyente contribuyente) {
        long id =
                java.util.Objects.requireNonNull(
                        contribuyente.id(), "Un contribuyente leido tiene identificador");
        return new ResumenDeContribuyente(
                id,
                contribuyente.codigo().valor(),
                contribuyente.nombreRazonSocial(),
                contribuyente.documento().toString());
    }
}
