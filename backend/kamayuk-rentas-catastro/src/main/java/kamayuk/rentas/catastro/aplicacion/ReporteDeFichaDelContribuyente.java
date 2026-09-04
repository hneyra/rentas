package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La ficha del contribuyente que se imprime en ventanilla (RF-010): quien es, donde vive y que
 * unidades tiene afectas.
 *
 * <p>Junta los dos lados —el padron y el catastro— y no calcula nada. <b>No lleva ningun
 * importe</b>: el autovaluo de cada predio es una regla de calculo, vive en rentas y esta bloqueada
 * por D-02a. Lo que sale es superficie, uso y porcentaje de titularidad.
 *
 * <p>Se arma <b>a una fecha</b>. Reimprimir en 2029 la ficha con que se atendio en 2027 tiene que
 * dar lo mismo que dio entonces; con «los datos de hoy» el documento no serviria para explicar nada
 * de lo que ya se emitio (regla 9).
 *
 * <p>Devuelve datos, no un documento. Convertirlos en PDF, hoja de calculo o texto enriquecido es
 * la capa de generacion de documentos (#55, RF-132): un caso de uso que supiera dibujar paginas no
 * se podria probar sin abrirlas.
 */
@Service
public class ReporteDeFichaDelContribuyente {

    private final DirectorioDeContribuyentes padron;
    private final CatastroRepository catastro;
    private final FichaCatastralRepository fichas;
    private final Clock reloj;

    public ReporteDeFichaDelContribuyente(
            DirectorioDeContribuyentes padron,
            CatastroRepository catastro,
            FichaCatastralRepository fichas,
            Clock reloj) {
        this.padron = padron;
        this.catastro = catastro;
        this.fichas = fichas;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public Optional<Reporte> de(String codigo, @Nullable LocalDate fecha) {
        LocalDate cuando = fecha == null ? LocalDate.now(reloj) : fecha;

        return padron.porCodigo(codigo)
                .map(
                        contribuyente ->
                                new Reporte(
                                        contribuyente,
                                        padron.domicilioFiscalDe(contribuyente.id(), cuando)
                                                .orElse(null),
                                        unidadesDe(contribuyente.id(), cuando),
                                        cuando));
    }

    private List<UnidadAfecta> unidadesDe(long contribuyenteId, LocalDate fecha) {
        List<UnidadAfecta> unidades = new ArrayList<>();
        for (Titularidad titularidad : catastro.prediosDe(contribuyenteId, fecha)) {
            Optional<Predio> predio = catastro.predio(titularidad.predioId());
            if (predio.isEmpty()) {
                continue;
            }
            Optional<FichaCatastral> ficha =
                    fichas.vigenteA(titularidad.predioId(), TipoFicha.UNICA, fecha);
            unidades.add(
                    new UnidadAfecta(
                            predio.get().codigo().valor(),
                            predio.get().direccion(),
                            titularidad.condicion().name(),
                            titularidad.porcentaje(),
                            ficha.map(FichaCatastral::areaTerreno).orElse(null),
                            ficha.map(FichaCatastral::uso).orElse(null),
                            ficha.map(FichaCatastral::version).orElse(null)));
        }
        return List.copyOf(unidades);
    }

    /**
     * El contenido del reporte.
     *
     * @param aLaFecha la fecha con que se armo; va en el documento, porque toda cifra dice de
     *     cuando es (regla 9)
     */
    public record Reporte(
            ResumenDeContribuyente contribuyente,
            @Nullable String domicilioFiscal,
            List<UnidadAfecta> unidades,
            LocalDate aLaFecha) {}

    /**
     * Una unidad afecta del contribuyente.
     *
     * <p>{@code area}, {@code uso} y {@code version} son nulos cuando el predio esta en el padron
     * catastral pero todavia no tiene ficha. Eso pasa, y sale asi en el reporte a proposito: es un
     * predio pendiente de fichar, y rellenarlo con ceros lo haria parecer fichado y vacio.
     */
    public record UnidadAfecta(
            String codigo,
            String direccion,
            String condicion,
            Porcentaje porcentaje,
            @Nullable AreaM2 area,
            @Nullable String uso,
            @Nullable Integer version) {}
}
