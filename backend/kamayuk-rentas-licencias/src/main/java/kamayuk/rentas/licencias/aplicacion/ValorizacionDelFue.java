package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.TablaDeValoresUnitarios;
import kamayuk.rentas.licencias.dominio.ValorizacionDeObra;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pone la tabla de #17 delante de la funcion pura que valoriza (#48 AC 2, RF-113).
 *
 * <h2>Que hace exactamente, y por que esta partido en dos</h2>
 *
 * <p>{@link ValorizacionDeObra} es la regla: pura, sin base de datos y sin reloj (regla 6, regla
 * 7). Esta clase es lo que le trae los datos —el cuadro de valores unitarios del conjunto sellado
 * que rige la fecha del acto— y lo que traduce sus fallos en algo que una pantalla pueda dibujar.
 * Juntarlas obligaria a la regla a conocer {@code catastro} y a levantar Spring para probarla.
 *
 * <h2>Cuando no hay cifra, no se inventa una: se dice cual falta</h2>
 *
 * <p>Las celdas del cuadro estan bloqueadas por D-02a (#197, #200, #233) y hoy, en una instalacion
 * recien implantada, <b>no hay ninguna</b>. Devolver cero seria dar una valorizacion indistinguible
 * de una correcta; devolver un error 500 dejaria la pantalla del FUE inservible por un dato de
 * configuracion. Se devuelve un {@link Resultado} que o trae la valorizacion o trae <b>el motivo,
 * con la llave que falta</b>: la pantalla lo muestra y el papel imprime «—».
 *
 * <h2>La fecha del acto, no la de hoy</h2>
 *
 * <p>El ejercicio con que se resuelve el conjunto es el del acto: la fecha de emision si la
 * licencia ya se otorgo, y la de la declaracion mientras no. Sin eso, revisar dentro de dos anios
 * con que cuadro se valorizo una obra devolveria el vigente y podria dar otra cifra, sin avisar
 * (ARQ-09 §3, regla 9).
 *
 * <h2>Y la cifra sale redondeada, o no sale (#378)</h2>
 *
 * <p>Hasta #378 la valorizacion devolvia el producto crudo y el papel de la licencia lo imprimia:
 * «Valor de obra (S/) 103320.31500000». ADR-0018 de {@code normativa} redondea al cierre de cada
 * regla, a centimo, {@code HALF_UP}, y lo publica como dato: la fila {@code
 * REDONDEO:VALOR_DE_OBRA_DEL_FUE} del conjunto sellado del mismo ejercicio. Se lee aqui, con {@link
 * PoliticasDeRedondeoSelladas#en}, y si el conjunto no la publica pasa lo mismo que cuando falta el
 * cuadro: el papel imprime «—» con su motivo y la ficha nombra la fila que falta. Imprimir el
 * producto sin redondear seria justo lo que el ADR descarta.
 */
@Service
public class ValorizacionDelFue {

    private final LectorDeValoresUnitarios cuadro;
    private final LectorDeParametros parametros;

    public ValorizacionDelFue(LectorDeValoresUnitarios cuadro, LectorDeParametros parametros) {
        this.cuadro = cuadro;
        this.parametros = parametros;
    }

    /**
     * Valoriza esas estructuras con el cuadro que rige esa fecha.
     *
     * <p>No lanza por falta de datos normativos: eso vuelve dentro del {@link Resultado}. Es el
     * caso de un elemento de {@link #valorizarVarias}, y no una copia suya (#455): hasta #455 eran
     * dos metodos con la misma traduccion del cuadro escrita dos veces, y ya daban motivos
     * distintos para el mismo expediente.
     *
     * @param estructuras las lineas declaradas en la seccion de valorizacion
     * @param fechaDelActo el dia con el que se resuelve el conjunto sellado (regla 6)
     */
    public Resultado valorizar(List<EstructuraDelProyecto> estructuras, LocalDate fechaDelActo) {
        return valorizarUna(new ObraAValorizar(estructuras, fechaDelActo), new HashMap<>());
    }

    /**
     * Valoriza varias obras, cada una con el cuadro <b>de su acto</b>, leyendo cada cuadro una sola
     * vez (#455).
     *
     * <p>Lo pide el reporte general, que pinta el valor de obra de cada fila. Hasta #455 las
     * valorizaba todas con el cuadro de la fecha de CORTE del reporte, y la misma licencia salia
     * con otra cifra que su ficha y su papel, que usan la del acto. Leer el cuadro una vez por
     * ejercicio distinto da la coherencia que aquella fecha unica buscaba —que media hoja no salga
     * con un cuadro y media con otro de la misma version— sin cambiar de acto.
     *
     * @param obras las estructuras y la fecha del acto de cada expediente, por su identificador
     */
    public Map<Long, Resultado> valorizarVarias(Map<Long, ObraAValorizar> obras) {
        Objects.requireNonNull(obras, "El mapa de obras es vacio, no nulo");

        Map<Ejercicio, Cuadro> cuadros = new HashMap<>();
        Map<Long, Resultado> resultados = new LinkedHashMap<>();
        for (Map.Entry<Long, ObraAValorizar> entrada : obras.entrySet()) {
            resultados.put(entrada.getKey(), valorizarUna(entrada.getValue(), cuadros));
        }
        return Map.copyOf(resultados);
    }

    /**
     * Una obra, con un solo orden para los motivos: primero las estructuras —que no necesitan el
     * cuadro, y por eso sin ellas no se pide—, despues el cuadro del ejercicio de su acto.
     */
    private Resultado valorizarUna(ObraAValorizar obra, Map<Ejercicio, Cuadro> cuadros) {
        Ejercicio ejercicio = Ejercicio.de(obra.fechaDelActo());
        if (obra.estructuras().isEmpty()) {
            return Resultado.sinEstructuras(ejercicio);
        }
        return switch (cuadros.computeIfAbsent(ejercicio, this::cuadroDe)) {
            case Cuadro.Ausente ausente -> ausente.porQue();
            case Cuadro.Disponible cuadro -> {
                try {
                    yield Resultado.calculada(
                            ejercicio,
                            ValorizacionDeObra.valorizar(
                                    obra.estructuras(), cuadro.tabla(), cuadro.politica()));
                } catch (TablaDeValoresUnitarios.ValorUnitarioSinParametrizar falta) {
                    yield Resultado.noDisponible(ejercicio, mensajeDe(falta), falta.celda());
                }
            }
        };
    }

    /**
     * El cuadro y la politica de redondeo de un ejercicio, o el {@link Resultado} que dice por que
     * no hay con que valorizar en el. Es la unica traduccion del cuadro de este modulo.
     */
    private Cuadro cuadroDe(Ejercicio ejercicio) {
        List<ValorUnitarioPublicado> celdas;
        try {
            celdas = cuadro.valoresUnitariosVigentesEn(ejercicio);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return new Cuadro.Ausente(Resultado.sinSellar(ejercicio));
        }

        List<TablaDeValoresUnitarios.Celda> traducidas = new ArrayList<>(celdas.size());
        for (ValorUnitarioPublicado celda : celdas) {
            traducidas.add(
                    new TablaDeValoresUnitarios.Celda(
                            celda.partida(),
                            celda.categoria(),
                            celda.anioConstruccionDesde(),
                            celda.anioConstruccionHasta(),
                            celda.valorM2()));
        }
        // El anio de construccion de una obra que se autoriza es el del acto: el cuadro es una
        // matriz de categoria por anio de construccion (NEG-05 §RT-002), y elegir la fila por el
        // ejercicio del conjunto en vez de por el anio de la obra es el defecto que ese documento
        // describe.
        TablaDeValoresUnitarios tabla =
                TablaDeValoresUnitarios.de(traducidas, ejercicio, ejercicio.valor());
        if (tabla.tamano() == 0) {
            return new Cuadro.Ausente(Resultado.sinCeldas(ejercicio));
        }

        Redondeo redondeo = redondeoEn(ejercicio);
        PoliticaDeRedondeo politica = redondeo.politica();
        if (politica == null) {
            return new Cuadro.Ausente(redondeo.sinCifra(ejercicio));
        }
        return new Cuadro.Disponible(tabla, politica);
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El cuadro de valores unitarios sellado esta incompleto" : mensaje;
    }

    /**
     * La politica con que se redondea el valor de obra en el ejercicio del acto, o por que no la
     * hay (#378).
     *
     * <p>No lanza: lo que falta publicar vuelve dentro del {@link Resultado}, como la celda del
     * cuadro que falta. Las cinco excepciones de {@link PoliticasDeRedondeoSelladas} nombran su
     * fila —{@code REDONDEO:VALOR_DE_OBRA_DEL_FUE}, o el bloque {@code REDONDEO} entero si no hay
     * ninguna—, y esa llave es la que la ficha publica en {@code llaveQueFalta}.
     */
    private Redondeo redondeoEn(Ejercicio ejercicio) {
        try {
            return new Redondeo(
                    PoliticasDeRedondeoSelladas.en(
                            parametros.vigenteEn(ejercicio), ValorizacionDeObra.PUNTO_DE_REDONDEO),
                    null,
                    null);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return new Redondeo(
                    null,
                    "No hay ningun conjunto de parametros sellado para el ejercicio "
                            + ejercicio
                            + ", asi que no hay politica con que redondear el valor de obra"
                            + " (ADR-0018)",
                    null);
        } catch (CifraSinPublicar falta) {
            return new Redondeo(
                    null,
                    "El valor de obra no se imprime sin redondear, y el conjunto sellado no dice"
                            + " como redondearlo (ADR-0018). "
                            + mensajeDe(falta),
                    falta.llave().orElse(null));
        }
    }

    /**
     * La politica del punto, o el motivo y la llave de por que no la hay: exactamente uno de los
     * dos lados.
     */
    private record Redondeo(
            @Nullable PoliticaDeRedondeo politica,
            @Nullable String motivo,
            @Nullable String llaveQueFalta) {

        Resultado sinCifra(Ejercicio ejercicio) {
            String porQue = motivo;
            return Resultado.noDisponible(
                    ejercicio,
                    porQue == null ? "Falta la politica de redondeo del valor de obra" : porQue,
                    llaveQueFalta);
        }
    }

    /** El cuadro de un ejercicio con su politica, o el resultado que dice por que no lo hay. */
    private sealed interface Cuadro {

        record Disponible(TablaDeValoresUnitarios tabla, PoliticaDeRedondeo politica)
                implements Cuadro {}

        record Ausente(Resultado porQue) implements Cuadro {}
    }

    /**
     * Lo que se valoriza de un expediente: sus estructuras y la fecha de su acto, que es la que
     * decide el cuadro (#455). La fecha del acto la resuelve {@code LecturaDelFue}: la de la
     * emision si la hubo, y la de la declaracion mientras no.
     */
    public record ObraAValorizar(List<EstructuraDelProyecto> estructuras, LocalDate fechaDelActo) {

        public ObraAValorizar {
            Objects.requireNonNull(estructuras, "La lista de estructuras es vacia, no nula");
            Objects.requireNonNull(fechaDelActo, "La fecha entra como argumento (regla 6)");
            estructuras = List.copyOf(estructuras);
        }
    }

    // ------------------------------------------------------------------

    /**
     * La valorizacion, o el motivo por el que hoy no hay ninguna.
     *
     * @param ejercicio el ejercicio con que se resolvio el conjunto sellado
     * @param valorizacion la obra valorizada; nula cuando no se pudo
     * @param motivo por que no se pudo; nulo cuando si se pudo
     * @param llaveQueFalta la celda que falta, {@code partida:categoria}, cuando es eso lo que
     *     pasa; o la fila de redondeo que falta, {@code REDONDEO:VALOR_DE_OBRA_DEL_FUE} (#378)
     */
    public record Resultado(
            Ejercicio ejercicio,
            ValorizacionDeObra.@Nullable Valorizacion valorizacion,
            @Nullable String motivo,
            @Nullable String llaveQueFalta) {

        static Resultado calculada(
                Ejercicio ejercicio, ValorizacionDeObra.Valorizacion valorizacion) {
            return new Resultado(ejercicio, valorizacion, null, null);
        }

        static Resultado noDisponible(
                Ejercicio ejercicio, String motivo, @Nullable String llaveQueFalta) {
            return new Resultado(ejercicio, null, motivo, llaveQueFalta);
        }

        /** El expediente todavia no declara ninguna estructura: no hace falta mirar el cuadro. */
        static Resultado sinEstructuras(Ejercicio ejercicio) {
            return noDisponible(
                    ejercicio,
                    "El proyecto todavia no declara ninguna partida en ningun piso: la seccion de"
                            + " valorizacion esta sin completar",
                    null);
        }

        /** El ejercicio del acto no tiene ningun conjunto sellado. */
        static Resultado sinSellar(Ejercicio ejercicio) {
            return noDisponible(
                    ejercicio,
                    "No hay ningun conjunto de parametros sellado para el ejercicio "
                            + ejercicio
                            + ", asi que no hay cuadro de valores unitarios con que valorizar la"
                            + " obra. Las cifras del cuadro las espera #197 (D-02a)",
                    null);
        }

        /** El conjunto esta sellado y no trae ninguna celda que rija una obra de ese anio. */
        static Resultado sinCeldas(Ejercicio ejercicio) {
            return noDisponible(
                    ejercicio,
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no trae ninguna celda del cuadro de valores unitarios que rija una"
                            + " edificacion de "
                            + ejercicio.valor()
                            + ". Las cifras las espera #197 (D-02a)",
                    null);
        }

        public boolean estaDisponible() {
            return valorizacion != null;
        }

        public Optional<ValorizacionDeObra.Valorizacion> obra() {
            return Optional.ofNullable(valorizacion);
        }

        /**
         * Lo que se imprime donde iria la cifra cuando no la hay.
         *
         * <p>Una raya, y no un cero ni un vacio: el cero se lee como «vale cero» y el vacio como
         * «se olvidaron de ponerlo».
         */
        public static final String SIN_CIFRA = "—";
    }
}
