package kamayuk.rentas.parametros;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Aplica las reglas vigentes resolviendo el <b>grafo</b> que declaran, no una lista ordenada a
 * mano.
 *
 * <p>En cada vuelta aplica toda regla cuyos insumos ya estan calculados. Si en una vuelta no puede
 * aplicar ninguna y quedan reglas, el grafo no cierra: o falta un dato declarado —y se dice cual— o
 * hay un ciclo. Nunca devuelve un resultado parcial: un importe calculado a medias es una cifra
 * plausible y equivocada.
 *
 * <p>El calculo por contribuyente son dos fases, en el orden que NEG-05 §1 fija: primero el grafo
 * de cada predio, despues la agregacion sobre el conjunto. Aplicar los tramos progresivos predio
 * por predio produce un error sistematico a la baja en todo el padron.
 */
public final class MotorDeReglas {

    private final CatalogoDeReglas catalogo;

    public MotorDeReglas(CatalogoDeReglas catalogo) {
        this.catalogo = Objects.requireNonNull(catalogo, "El motor necesita su catalogo de reglas");
    }

    /** El calculo de una partida: un predio, un vehiculo. */
    public ResultadoDelCalculo aplicarA(EntradaDeCalculo entrada) {
        Objects.requireNonNull(entrada, "El motor necesita su entrada");
        List<ReglaTributaria> vigentes = catalogo.vigentesEn(entrada.ejercicio());
        if (vigentes.isEmpty()) {
            throw new SinReglasVigentes(entrada.ejercicio());
        }

        verificarQueNadieDuplicaUnConcepto(vigentes);

        EstadoDelCalculo estado = entrada.declarados();
        List<IdentificadorDeRegla> aplicadas = new ArrayList<>();
        List<ReglaTributaria> pendientes = new ArrayList<>(vigentes);

        while (!pendientes.isEmpty()) {
            List<ReglaTributaria> aplicablesAhora = new ArrayList<>();
            for (ReglaTributaria regla : pendientes) {
                if (estado.conceptos().containsAll(regla.requiere())) {
                    aplicablesAhora.add(regla);
                }
            }
            if (aplicablesAhora.isEmpty()) {
                throw new ElGrafoNoCierra(pendientes, estado);
            }
            // Dentro de una vuelta el orden da igual: ninguna de estas reglas consume lo que otra
            // de la misma vuelta produce —si lo hiciera, no estaria aplicable todavia—.
            for (ReglaTributaria regla : aplicablesAhora) {
                Dinero producido =
                        regla.calcular(
                                new InsumosDeLaRegla(
                                        regla.identificador(),
                                        regla.requiere(),
                                        estado,
                                        entrada.ejercicio(),
                                        entrada.caracteristicas(),
                                        entrada.parametros(),
                                        entrada.redondeo()));
                Objects.requireNonNull(
                        producido, regla.identificador() + " devolvio nulo en vez de un importe");
                estado = estado.mas(regla.produce(), producido);
                aplicadas.add(regla.identificador());
            }
            pendientes.removeAll(aplicablesAhora);
        }

        return new ResultadoDelCalculo(
                estado, entrada.ejercicio(), entrada.parametros().version(), aplicadas);
    }

    /**
     * El calculo de un contribuyente: el grafo de cada predio y despues la agregacion sobre el
     * conjunto. La base imponible es la de <b>todos</b> sus predios, y sobre ese total se aplican
     * los tramos (NEG-05 §1).
     */
    public ResultadoDelContribuyente aplicarAlContribuyente(List<EntradaDeCalculo> partidas) {
        Objects.requireNonNull(partidas, "La lista de partidas es vacia, no nula");
        if (partidas.isEmpty()) {
            throw new SinPartidas();
        }

        Ejercicio ejercicio = partidas.get(0).ejercicio();
        for (EntradaDeCalculo partida : partidas) {
            if (!ejercicio.equals(partida.ejercicio())) {
                throw new IllegalArgumentException(
                        "Las partidas de un contribuyente son del mismo ejercicio: llegaron "
                                + ejercicio
                                + " y "
                                + partida.ejercicio());
            }
        }

        List<ResultadoDelCalculo> porPartida = new ArrayList<>();
        for (EntradaDeCalculo partida : partidas) {
            porPartida.add(aplicarA(partida));
        }

        List<ReglaDeAgregacion> agregaciones = catalogo.agregacionesVigentesEn(ejercicio);
        EstadoDelCalculo agregado = EstadoDelCalculo.vacio();
        List<IdentificadorDeRegla> aplicadas = new ArrayList<>();
        InsumosDeLaAgregacion insumos = partidas.get(0).paraAgregacion();

        for (ReglaDeAgregacion regla : agregaciones) {
            List<Dinero> aportes = new ArrayList<>();
            for (ResultadoDelCalculo resultado : porPartida) {
                aportes.add(
                        resultado
                                .valor(regla.deCadaPartida())
                                .orElseThrow(
                                        () ->
                                                new PartidaSinElAporte(
                                                        regla.identificador(),
                                                        regla.deCadaPartida())));
            }
            Dinero producido = regla.agregar(List.copyOf(aportes), insumos);
            Objects.requireNonNull(
                    producido, regla.identificador() + " devolvio nulo en vez de un importe");
            agregado = agregado.mas(regla.produce(), producido);
            aplicadas.add(regla.identificador());
        }

        return new ResultadoDelContribuyente(
                List.copyOf(porPartida), agregado, ejercicio, List.copyOf(aplicadas));
    }

    private static void verificarQueNadieDuplicaUnConcepto(List<ReglaTributaria> vigentes) {
        Set<Concepto> producidos = new LinkedHashSet<>();
        for (ReglaTributaria regla : vigentes) {
            if (!producidos.add(regla.produce())) {
                throw new DosReglasProducenLoMismo(regla.produce());
            }
        }
    }

    /** Ninguna regla rige el ejercicio. Devolver la base sin tocar seria peor. */
    public static final class SinReglasVigentes extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinReglasVigentes(Ejercicio ejercicio) {
            super(
                    "Ninguna regla rige el ejercicio "
                            + ejercicio
                            + ". Devolver la base sin tocar produciria una cifra plausible y"
                            + " equivocada, sin ningun error de por medio");
        }
    }

    /** Se pidio calcular un contribuyente sin ningun predio. */
    public static final class SinPartidas extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinPartidas() {
            super(
                    "Un contribuyente sin partidas no tiene base imponible cero: no tiene"
                            + " determinacion. Emitir un valor de cero es un acto distinto");
        }
    }

    /**
     * Quedan reglas por aplicar y ninguna puede: falta un dato declarado o hay un ciclo. El mensaje
     * nombra que falta, porque «el calculo no salio» no le sirve a nadie en ventanilla.
     */
    public static final class ElGrafoNoCierra extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ElGrafoNoCierra(List<ReglaTributaria> pendientes, EstadoDelCalculo estado) {
            super(construirMensaje(pendientes, estado));
        }

        private static String construirMensaje(
                List<ReglaTributaria> pendientes, EstadoDelCalculo estado) {
            Set<Concepto> faltantes = new TreeSet<>();
            Set<Concepto> producibles = new LinkedHashSet<>();
            for (ReglaTributaria regla : pendientes) {
                producibles.add(regla.produce());
            }
            for (ReglaTributaria regla : pendientes) {
                for (Concepto requerido : regla.requiere()) {
                    if (!estado.conoce(requerido) && !producibles.contains(requerido)) {
                        faltantes.add(requerido);
                    }
                }
            }
            List<String> nombres = pendientes.stream().map(r -> r.identificador().valor()).toList();
            if (faltantes.isEmpty()) {
                return "Las reglas "
                        + nombres
                        + " se esperan entre si: hay un ciclo en el grafo y ninguna puede empezar";
            }
            return "No se puede aplicar "
                    + nombres
                    + ": falta "
                    + faltantes
                    + ". No se emite con lo que hay —seria una cifra incompleta que nadie"
                    + " distinguiria de una correcta— (ARQ-09 §2.5)";
        }
    }

    /** Dos reglas vigentes calculan el mismo concepto: el importe dependeria de cual gane. */
    public static final class DosReglasProducenLoMismo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DosReglasProducenLoMismo(Concepto concepto) {
            super(
                    "Dos reglas vigentes producen "
                            + concepto
                            + ". El importe dependeria de cual se aplique, y recalcular el pasado"
                            + " dejaria de ser reproducible");
        }
    }

    /** Una partida no calculo el concepto que la agregacion suma. */
    public static final class PartidaSinElAporte extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PartidaSinElAporte(IdentificadorDeRegla regla, Concepto concepto) {
            super(
                    regla
                            + " suma "
                            + concepto
                            + " de cada predio y una partida no lo calculo. Sumar solo las que si"
                            + " lo tienen daria una base menor que la real, que es el error"
                            + " sistematico a la baja que NEG-05 §1 advierte");
        }
    }
}
