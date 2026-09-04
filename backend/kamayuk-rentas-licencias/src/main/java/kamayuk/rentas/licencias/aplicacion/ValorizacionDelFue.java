package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.TablaDeValoresUnitarios;
import kamayuk.rentas.licencias.dominio.ValorizacionDeObra;
import kamayuk.rentas.parametros.LectorDeParametros;
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
 */
@Service
public class ValorizacionDelFue {

    private final LectorDeValoresUnitarios cuadro;

    public ValorizacionDelFue(LectorDeValoresUnitarios cuadro) {
        this.cuadro = cuadro;
    }

    /**
     * Valoriza esas estructuras con el cuadro que rige esa fecha.
     *
     * <p>No lanza por falta de datos normativos: eso vuelve dentro del {@link Resultado}.
     *
     * @param estructuras las lineas declaradas en la seccion de valorizacion
     * @param fechaDelActo el dia con el que se resuelve el conjunto sellado (regla 6)
     */
    public Resultado valorizar(List<EstructuraDelProyecto> estructuras, LocalDate fechaDelActo) {
        Objects.requireNonNull(estructuras, "La lista de estructuras es vacia, no nula");
        Objects.requireNonNull(fechaDelActo, "La fecha entra como argumento (regla 6)");

        Ejercicio ejercicio = Ejercicio.de(fechaDelActo);
        if (estructuras.isEmpty()) {
            return Resultado.noDisponible(
                    ejercicio,
                    "El proyecto todavia no declara ninguna partida en ningun piso: la seccion de"
                            + " valorizacion esta sin completar",
                    null);
        }

        List<ValorUnitarioPublicado> celdas;
        try {
            celdas = cuadro.valoresUnitariosVigentesEn(ejercicio);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return Resultado.noDisponible(
                    ejercicio,
                    "No hay ningun conjunto de parametros sellado para el ejercicio "
                            + ejercicio
                            + ", asi que no hay cuadro de valores unitarios con que valorizar la"
                            + " obra. Las cifras del cuadro las espera #197 (D-02a)",
                    null);
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
            return Resultado.noDisponible(
                    ejercicio,
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no trae ninguna celda del cuadro de valores unitarios que rija una"
                            + " edificacion de "
                            + ejercicio.valor()
                            + ". Las cifras las espera #197 (D-02a)",
                    null);
        }

        try {
            return Resultado.calculada(ejercicio, ValorizacionDeObra.valorizar(estructuras, tabla));
        } catch (TablaDeValoresUnitarios.ValorUnitarioSinParametrizar falta) {
            return Resultado.noDisponible(ejercicio, mensajeDe(falta), falta.celda());
        }
    }

    /**
     * Valoriza varias listas de estructuras con <b>una sola</b> lectura del cuadro.
     *
     * <p>Lo pide el reporte general, que pinta el valor de obra de cada fila. Llamar al metodo de
     * una en una resolveria el conjunto sellado una vez por fila; peor todavia, si entre dos
     * lecturas se sellara una version nueva, media pagina saldria con un cuadro y media con otro.
     *
     * @param porExpediente las estructuras de cada expediente, por su identificador
     * @param fechaDelActo el dia con el que se resuelve el conjunto sellado (regla 6)
     */
    public Map<Long, Resultado> valorizarVarias(
            Map<Long, List<EstructuraDelProyecto>> porExpediente, LocalDate fechaDelActo) {

        Objects.requireNonNull(porExpediente, "El mapa de estructuras es vacio, no nulo");
        Objects.requireNonNull(fechaDelActo, "La fecha entra como argumento (regla 6)");

        Ejercicio ejercicio = Ejercicio.de(fechaDelActo);
        List<ValorUnitarioPublicado> celdas;
        try {
            celdas = cuadro.valoresUnitariosVigentesEn(ejercicio);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            Map<Long, Resultado> sinCuadro = new LinkedHashMap<>();
            for (Long fueId : porExpediente.keySet()) {
                sinCuadro.put(
                        fueId,
                        Resultado.noDisponible(
                                ejercicio,
                                "No hay ningun conjunto de parametros sellado para el ejercicio "
                                        + ejercicio
                                        + ": las cifras del cuadro de valores unitarios las espera"
                                        + " #197 (D-02a)",
                                null));
            }
            return Map.copyOf(sinCuadro);
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
        TablaDeValoresUnitarios tabla =
                TablaDeValoresUnitarios.de(traducidas, ejercicio, ejercicio.valor());

        Map<Long, Resultado> resultados = new LinkedHashMap<>();
        for (Map.Entry<Long, List<EstructuraDelProyecto>> entrada : porExpediente.entrySet()) {
            List<EstructuraDelProyecto> estructuras = entrada.getValue();
            if (estructuras.isEmpty() || tabla.tamano() == 0) {
                resultados.put(
                        entrada.getKey(),
                        Resultado.noDisponible(
                                ejercicio,
                                estructuras.isEmpty()
                                        ? "El expediente todavia no declara ninguna partida en"
                                                + " ningun piso"
                                        : "El conjunto sellado del ejercicio "
                                                + ejercicio
                                                + " no trae ninguna celda del cuadro de valores"
                                                + " unitarios. Las cifras las espera #197 (D-02a)",
                                null));
                continue;
            }
            try {
                resultados.put(
                        entrada.getKey(),
                        Resultado.calculada(
                                ejercicio, ValorizacionDeObra.valorizar(estructuras, tabla)));
            } catch (TablaDeValoresUnitarios.ValorUnitarioSinParametrizar falta) {
                resultados.put(
                        entrada.getKey(),
                        Resultado.noDisponible(ejercicio, mensajeDe(falta), falta.celda()));
            }
        }
        return Map.copyOf(resultados);
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El cuadro de valores unitarios sellado esta incompleto" : mensaje;
    }

    // ------------------------------------------------------------------

    /**
     * La valorizacion, o el motivo por el que hoy no hay ninguna.
     *
     * @param ejercicio el ejercicio con que se resolvio el conjunto sellado
     * @param valorizacion la obra valorizada; nula cuando no se pudo
     * @param motivo por que no se pudo; nulo cuando si se pudo
     * @param llaveQueFalta la celda que falta, {@code partida:categoria}, cuando es eso lo que pasa
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
