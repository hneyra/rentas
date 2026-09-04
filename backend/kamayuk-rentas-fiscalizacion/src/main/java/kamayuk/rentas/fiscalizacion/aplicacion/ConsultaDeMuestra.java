package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelProgramaRepository;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La muestra sorteada de un programa, para la grilla de {@code fisc_programa} y para que {@code
 * fisc_predial} resuelva su fila (#481).
 *
 * <p>El {@code @Transactional} no es decorativo: sin él no hay {@code SET LOCAL} y la política RLS
 * no deja leer ni una fila. Es el defecto que {@code ConsultaDeVias} cerró y que #53 volvió a
 * encontrar.
 */
@Service
public class ConsultaDeMuestra {

    private final ProgramaFiscalizacionRepository programas;
    private final MuestraDelProgramaRepository muestras;
    private final ActaFiscalizacionRepository actas;

    public ConsultaDeMuestra(
            ProgramaFiscalizacionRepository programas,
            MuestraDelProgramaRepository muestras,
            ActaFiscalizacionRepository actas) {
        this.programas = programas;
        this.muestras = muestras;
        this.actas = actas;
    }

    /**
     * Una página de la muestra, con el dato derivado que la fila no guarda.
     *
     * <p><b>{@link Optional#empty()} cuando el programa no existe</b> (#546), y no una página
     * vacía: son dos respuestas distintas y hasta este issue eran la misma. {@code GET
     * /programas/99999/muestra} contestaba {@code 200 {"contenido":[],"totalElementos":0}}, o sea
     * exactamente lo que contesta un programa recién registrado al que nadie ha sorteado todavía la
     * muestra — así que quien pide por un identificador equivocado no puede saber que se equivocó,
     * y la pantalla dibuja «este programa no tiene predios seleccionados» de un programa que no
     * está. Es el defecto que #537 cerró para las manzanas de un sector, y se cierra por el mismo
     * sitio: el {@code Optional} lo devuelve el caso de uso, dentro de la misma transacción que la
     * lectura, porque preguntárselo al repositorio desde el controlador corre sin {@code SET LOCAL}
     * y la política RLS revienta (#486).
     *
     * @param predioId acota a un predio; es como {@code fisc_predial} pide la suya
     */
    @Transactional(readOnly = true)
    public Optional<Resultado> buscar(
            long programaId, @Nullable Long predioId, Paginacion paginacion) {
        if (programas.findById(programaId).isEmpty()) {
            return Optional.empty();
        }
        Pagina<MuestraDelPrograma> pagina = muestras.delPrograma(programaId, predioId, paginacion);

        Set<Long> predios = new HashSet<>();
        for (MuestraDelPrograma fila : pagina.contenido()) {
            predios.add(fila.predioId());
        }

        return Optional.of(
                new Resultado(pagina, actas.prediosConActaEnElPrograma(programaId, predios)));
    }

    /**
     * La página y <b>cuáles de sus predios ya se visitaron</b>, que es de donde sale la columna
     * «Estado» de la grilla.
     *
     * <p>No es una columna de la fila a propósito: guardarla dejaría dos verdades sobre lo mismo, y
     * la que se lee en pantalla sería la que nadie recalculó ({@code V60} §2).
     */
    public record Resultado(Pagina<MuestraDelPrograma> pagina, Set<Long> prediosConActa) {

        public boolean visitado(MuestraDelPrograma fila) {
            return prediosConActa.contains(fila.predioId());
        }
    }
}
