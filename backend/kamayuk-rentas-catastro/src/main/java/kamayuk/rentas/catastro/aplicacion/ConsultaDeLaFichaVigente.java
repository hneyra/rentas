package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La ficha de un predio, buscada por su codigo: las cuatro rutas {@code GET
 * /catastro/fichas/‹tipo›/‹codigo›}.
 *
 * <h2>Por que es una clase y no tres llamadas del controlador</h2>
 *
 * <p>Leer una ficha son <b>tres</b> consultas —el predio por su codigo, su ficha vigente a la fecha
 * y, si se pide, su historial—, y {@code FichaController} las hacia por separado: la primera desde
 * el propio controlador, contra {@code CatastroRepository}, y las otras dos contra casos de uso que
 * si eran {@code @Transactional}. Media peticion dentro de una transaccion y media fuera.
 *
 * <p>Eso costaba un <b>500</b> en las cuatro rutas (#486): fuera de transaccion no se emite el
 * {@code SET LOCAL app.municipalidad_id}, y la politica RLS lo lee con {@code
 * current_setting(...)::bigint}. La cadena vacia no devuelve vacio —revienta con «invalid input
 * syntax for type bigint: ""»—.
 *
 * <p><b>Y aunque no fallara, tres transacciones son tres respuestas.</b> Entre la lectura del
 * predio y la de su ficha cabe una version nueva: el historial podria traer una fila que la ficha
 * vigente ya no refleja. Aqui las tres van en la misma, que es lo unico que hace la respuesta
 * coherente consigo misma.
 *
 * <p>La fecha entra como argumento y no se resuelve aqui (regla 6): quien pregunta dice a que
 * fecha, y el controlador pone la de hoy solo cuando la peticion no la trae.
 */
@Service
public class ConsultaDeLaFichaVigente {

    private final CatastroRepository catastro;
    private final FichaCatastralRepository fichas;

    public ConsultaDeLaFichaVigente(CatastroRepository catastro, FichaCatastralRepository fichas) {
        this.catastro = catastro;
        this.fichas = fichas;
    }

    /**
     * Lo que hay bajo ese codigo, a esa fecha.
     *
     * <p>Los dos «no hay» se distinguen a proposito, porque no se arreglan igual: {@link
     * Optional#empty()} es <b>no hay predio con ese codigo</b> —un codigo mal tecleado, o un predio
     * que nunca se dio de alta—, y un {@link Encontrada#ficha()} nulo es <b>el predio existe y no
     * tiene ficha de ese tipo vigente a esa fecha</b>, que es lo que le pasa a un predio urbano al
     * que se le pregunta por su ficha rural. Devolver lo mismo en los dos casos mandaria a quien
     * atiende a buscar el codigo cuando el codigo estaba bien.
     */
    @Transactional(readOnly = true)
    public Optional<Encontrada> porCodigo(
            CodigoReferenciaCatastral referencia,
            TipoFicha tipo,
            LocalDate cuando,
            boolean conHistorial) {

        return catastro.predioPorCodigo(referencia)
                .map(
                        predio -> {
                            long predioId =
                                    java.util.Objects.requireNonNull(
                                            predio.id(), "El predio leido tiene id");
                            FichaCatastral ficha =
                                    fichas.vigenteA(predioId, tipo, cuando).orElse(null);
                            return new Encontrada(
                                    predioId,
                                    ficha,
                                    conHistorial ? fichas.versionesDe(predioId, tipo) : null);
                        });
    }

    /**
     * El predio, su ficha vigente si la tiene, y su historial si se pidio.
     *
     * <p>El historial es nulo —no una lista vacia— cuando no se pidio: es la diferencia entre «no
     * lo pediste» y «no hay ninguna version», y la respuesta del contrato ya la conserva.
     */
    public record Encontrada(
            long predioId,
            @Nullable FichaCatastral ficha,
            @Nullable List<VersionDeLaFicha> historial) {}
}
