package kamayuk.rentas.catastro.aplicacion;

import java.util.List;
import kamayuk.rentas.catastro.dominio.AcotacionDelPlano;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FiltroDelPlano;
import kamayuk.rentas.catastro.dominio.LoteDelPlano;
import kamayuk.rentas.catastro.dominio.MarcoDeLoLevantado;
import kamayuk.rentas.catastro.dominio.PlanoDelCatastro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los lotes de un marco, para dibujar el plano catastral (ADR-0022, #536).
 *
 * <h2>Se niega antes que recortarse</h2>
 *
 * <p>Es lo unico que separa a esta lectura de las otras del sistema, y no es una preferencia de
 * estilo. Una tabla recortada <b>se ve</b> recortada: tiene paginacion y un total encima. Un plano
 * al que le faltan lotes no se ve recortado: se ve como un plano donde ahi no hay lotes, y quien
 * mira un hueco concluye que ese terreno no esta en el padron. Por eso, cuando en el marco caben
 * mas lotes de los que se sirven, la respuesta es {@link MarcoConDemasiadosLotes} diciendo cuantos
 * hay y cual es el tope —una respuesta que se puede obedecer acercandose— y nunca una pagina con
 * los primeros.
 *
 * <p>Por lo mismo no pagina: no hay un orden que convierta «la pagina 2» en una porcion del
 * territorio.
 *
 * <h2>Por que se piden uno mas de los que caben</h2>
 *
 * <p>Para no pagar un {@code count} en el camino normal. Si vuelven {@code limite + 1} filas es que
 * no cabe, y solo entonces se pregunta la cifra exacta que hay que decir. El caso corriente —cabe—
 * cuesta una consulta de lotes y una de conteo, la de {@code sinGeometria}.
 *
 * <h2>Y por que existe esta clase, ademas de la frontera transaccional</h2>
 *
 * <p>Por lo mismo que {@link ConsultaDePredios} y {@link ConsultaDeVias}: sin la anotacion
 * transaccional no se emite el {@code SET LOCAL app.municipalidad_id} que la politica RLS exige, y
 * la consulta no devuelve vacio —<b>revienta</b>, con «invalid input syntax for type bigint: ""»
 * (#486)—. Aqui ademas las tres consultas tienen que ver el mismo padron: el conteo que justifica
 * una negativa y la lista que la evita no pueden salir de dos instantes distintos.
 *
 * <p>No recibe el identificador de municipalidad (regla 2). No audita: no publica ni el titular ni
 * su codigo, asi que no hay nada que dejar dicho en la bitacora —lo que si lo deja es resolver el
 * titular al clic, que es otra operacion y otro permiso (ADR-0015 §2.4)—.
 */
@Service
public class ConsultaDelPlanoCatastral {

    private final CatastroRepository catastro;

    public ConsultaDelPlanoCatastral(CatastroRepository catastro) {
        this.catastro = catastro;
    }

    /**
     * @param limite cuantos lotes admite quien pregunta; el tope propio del servidor lo aplica la
     *     capa web, que es donde se lee el parametro
     * @throws MarcoConDemasiadosLotes si en el marco caben mas de {@code limite}
     */
    @Transactional(readOnly = true)
    public PlanoDelCatastro lotesDe(FiltroDelPlano filtro, int limite) {
        if (limite <= 0) {
            throw new IllegalArgumentException(
                    "El tope de lotes del plano tiene que ser positivo: llego " + limite);
        }

        List<LoteDelPlano> lotes = catastro.lotesDelPlano(filtro, limite + 1);
        if (lotes.size() > limite) {
            throw new MarcoConDemasiadosLotes(catastro.lotesEnElMarco(filtro), limite);
        }
        return new PlanoDelCatastro(lotes, catastro.prediosSinGeometria(filtro));
    }

    /**
     * El rectangulo que envuelve la geometria ya cargada, para saber por donde abrir (#612).
     *
     * <p>Es la lectura que hasta ahora faltaba: {@link #lotesDe(FiltroDelPlano, int)} exige un
     * marco y <b>ninguna operacion del contrato decia donde esta la municipalidad</b>, asi que
     * quien dibuja el plano no tenia de donde sacar el primero. Sin ella el visor abre sobre un
     * marco declarado —el pais entero—, y el dia que haya geometria cargada ese marco contiene mas
     * lotes que el tope: la respuesta pasa a ser «acercate» y desde la pantalla no se sabe hacia
     * donde.
     *
     * <p><b>No lleva tope ni se puede negar</b>, al reves que su hermana: es un agregado de cuatro
     * cifras y una cuenta, asi que su respuesta pesa lo mismo con un lote que con cien mil. Por eso
     * tampoco necesita el {@code limite + 1} de aquella.
     *
     * <p>Recibe la <b>misma</b> acotacion que el plano, y eso es lo que hace que el encuadre
     * contenga lo que despues se dibuja. Recibe {@link AcotacionDelPlano} y no {@link
     * FiltroDelPlano} porque un marco no se puede exigir a la lectura que existe para calcularlo.
     *
     * <p>Transaccional por lo de siempre: sin la anotacion no se emite el {@code SET LOCAL
     * app.municipalidad_id} y la politica RLS no devuelve vacio, <b>revienta</b> (#486). No audita,
     * por lo mismo que el plano: no publica ni un identificador de predio.
     */
    @Transactional(readOnly = true)
    public MarcoDeLoLevantado marcoDe(AcotacionDelPlano acotacion) {
        return catastro.marcoDeLoLevantado(acotacion);
    }

    /**
     * En el marco caben mas lotes de los que se sirven.
     *
     * <p>Lleva las dos cifras porque las dos hacen falta para saber que hacer: cuantos hay dice
     * cuanto hay que acercarse, y el tope dice cuando parar. «No caben» no es una respuesta que se
     * pueda obedecer.
     */
    public static final class MarcoConDemasiadosLotes extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final long cuantos;
        private final int tope;

        MarcoConDemasiadosLotes(long cuantos, int tope) {
            super(
                    "En este marco hay "
                            + cuantos
                            + " lotes y el maximo que se sirve son "
                            + tope
                            + ": acerca el plano o acota por sector o manzana. No se devuelven los"
                            + " primeros, porque un plano al que le faltan lotes se lee como un"
                            + " plano donde no hay lotes");
            this.cuantos = cuantos;
            this.tope = tope;
        }

        public long cuantos() {
            return cuantos;
        }

        public int tope() {
            return tope;
        }
    }
}
