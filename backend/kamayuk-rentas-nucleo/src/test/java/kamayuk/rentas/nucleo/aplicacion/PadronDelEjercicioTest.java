package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P5C / AC 1 — el padron del ejercicio, escrito a un archivo para poder compararlo.
 *
 * <p><b>No sustituye a {@code PadronRecalculadoTest}</b>, que es el de P5B y compara otra cosa:
 * alli lo que se movio fue de donde salen los PARAMETROS, y el archivo lleva las 33 filas del
 * conjunto sellado. Aqui lo que se mueve es de donde sale el PREDIO —su titularidad, su ficha— y el
 * archivo lleva la ponderacion, los tramos y el cronograma de doce contribuyentes. Los dos miden el
 * mismo invariante por dos lados distintos, y por eso conviven.
 *
 * <h2>Para que existe</h2>
 *
 * <p>El criterio 1 de P5C pide que la corrida de emision de despues produzca <b>el mismo padron,
 * centimo a centimo</b>, que la de antes, y que se compare como archivos y no revisandolo. Esta
 * clase escribe ese archivo. Es la misma forma con que P5B comparo el cuadro de parametros, y por
 * el mismo motivo: un diff no se puede discutir.
 *
 * <h2>Que se compara, exactamente</h2>
 *
 * <p>Doce contribuyentes con veinte predios entre todos, elegidos para que el archivo <b>cambie</b>
 * si cambia cualquiera de las piezas que P5C toca:
 *
 * <ul>
 *   <li>los tres tramos del art. 13 y sus <b>dos fronteras exactas</b> —15 y 60 UIT—, que son donde
 *       un centimo de diferencia en la base salta de tramo;
 *   <li>el <b>minimo imponible</b>, con un contribuyente por debajo de el;
 *   <li>la <b>ponderacion por porcentaje de propiedad</b>, con copropiedades al 50, 33.33 y 25 —el
 *       dato que sale de {@code titularidad}, que es de {@code catastro} y por tanto lo que esta
 *       etapa mueve—;
 *   <li>el <b>reparto en cuatro cuotas</b> con el centimo que no cabe, y el derecho de emision.
 * </ul>
 *
 * <p>La cuenta la hace la regla de produccion, no la prueba: si el impuesto se escribiera aqui, el
 * archivo mediria la prueba y no el sistema.
 *
 * <h2>Lo que este archivo NO cubre, dicho aqui y no descubierto despues</h2>
 *
 * <p><b>No lleva ninguna cifra que salga de una valuacion.</b> No puede: el sistema no sabe
 * valorizar un predio todavia —faltan el cuadro de valores unitarios y la depreciacion (GOB-03,
 * H-14 y H-15), los aranceles de la ordenanza (D-02b) y el {@code % actualizacion}, que sigue sin
 * fuente (D-11)—, y por eso el autovaluo se <b>declara</b> (#395). Lo que este padron mide es todo
 * lo que el sistema SI calcula hoy: la ponderacion, los tramos, el minimo, el redondeo y el
 * cronograma. Una corrida de valuacion de {@code catastro} con cifras no existe todavia ni aqui ni
 * en el monolito, asi que no hay dos de ellas que comparar.
 */
@DisplayName("P5C / AC 1 — el padron del ejercicio, comparado como archivo")
class PadronDelEjercicioTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Observacion PORQUE =
            Observacion.de("Corrida del padron para la comparacion de P5C");

    /** Donde se escribe. La compara `diff`, fuera de la prueba. */
    private static final Path ARCHIVO =
            Path.of(
                    System.getProperty(
                            "kamayuk.padron.delEjercicio", "build/padron-del-ejercicio.csv"));

    private final DeterminacionesEnMemoria determinaciones = new DeterminacionesEnMemoria();

    @Test
    @DisplayName("escribe el padron del ejercicio y su huella")
    void escribeElPadron() throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("seccion,clave,valor");

        ParametrosSellados sellados = conjunto().construir();
        lineas.add("cuadro,uit," + sellados.exigirNumero("UIT", null).valor().toPlainString());
        lineas.add(
                "cuadro,minimo,"
                        + sellados.exigirNumero("PREDIAL_MINIMO", null).valor().toPlainString());
        lineas.add(
                "cuadro,derechoDeEmision,"
                        + sellados.exigirNumero("DERECHO_EMISION_PREDIAL", null)
                                .valor()
                                .toPlainString());

        for (CasoDelPadron caso : PADRON) {
            DeterminacionPredialCalculada calculada = determinar(caso);
            lineas.add(
                    "contribuyente,"
                            + caso.codigo()
                            + ","
                            + calculada.valuoAfecto().valor().toPlainString()
                            + "|"
                            + calculada.cabecera().baseImponible().valor().toPlainString()
                            + "|"
                            + calculada.impuestoInsoluto().valor().toPlainString()
                            + "|"
                            + calculada.derechoDeEmision().valor().toPlainString());
            for (PredioEnLaBase predio : calculada.predios()) {
                lineas.add(
                        "predio,"
                                + caso.codigo()
                                + ":"
                                + predio.codigoReferenciaCatastral()
                                + ","
                                + predio.porcentajePropiedad().valor().toPlainString()
                                + "|"
                                + predio.baseImponiblePredio().valor().toPlainString());
            }
            for (CuotaDelPredial cuota : calculada.cuotas()) {
                lineas.add(
                        "cuota,"
                                + caso.codigo()
                                + ":"
                                + cuota.numero()
                                + ","
                                + cuota.vencimiento()
                                + "|"
                                + cuota.importe().valor().toPlainString());
            }
        }

        String contenido = String.join("\n", lineas) + "\n";
        Files.writeString(ARCHIVO, contenido, StandardCharsets.UTF_8);

        assertThat(lineas)
                .as("el archivo tiene que traer algo que comparar")
                .hasSizeGreaterThan(60);
        System.out.println("padron escrito en " + ARCHIVO + " sha256=" + sha256(contenido));
    }

    // ---------------------------------------------------------------- el padron

    /**
     * @param autovaluos el autovaluo DECLARADO de cada predio, y el porcentaje de propiedad que
     *     {@code titularidad} registra para este contribuyente
     */
    private record CasoDelPadron(String codigo, String nombre, List<PredioDelCaso> autovaluos) {}

    private record PredioDelCaso(String codigo, String autovaluo, String porcentaje) {}

    /**
     * Los doce casos, y por que cada uno.
     *
     * <p>Las dos fronteras de tramo se calculan sobre la UIT de 5 500: 15 UIT son 82 500 y 60 UIT
     * son 330 000. Los casos que las tocan las tocan <b>exactamente</b>, y hay uno un centimo por
     * encima y otro un centimo por debajo de cada una.
     */
    private static final List<CasoDelPadron> PADRON =
            List.of(
                    // Por debajo del minimo imponible (0.6 % de la UIT = 33.00).
                    new CasoDelPadron("C-01", "MINIMO", List.of(p("P-01", "1000.00", "100"))),
                    // Primer tramo, holgado.
                    new CasoDelPadron("C-02", "TRAMO 1", List.of(p("P-02", "50000.00", "100"))),
                    // La frontera exacta de 15 UIT, y sus dos vecinos.
                    new CasoDelPadron("C-03", "15 UIT", List.of(p("P-03", "82500.00", "100"))),
                    new CasoDelPadron("C-04", "15 UIT -1c", List.of(p("P-04", "82499.99", "100"))),
                    new CasoDelPadron("C-05", "15 UIT +1c", List.of(p("P-05", "82500.01", "100"))),
                    // Segundo tramo.
                    new CasoDelPadron("C-06", "TRAMO 2", List.of(p("P-06", "200000.00", "100"))),
                    // La frontera exacta de 60 UIT, y sus dos vecinos.
                    new CasoDelPadron("C-07", "60 UIT", List.of(p("P-07", "330000.00", "100"))),
                    new CasoDelPadron("C-08", "60 UIT -1c", List.of(p("P-08", "329999.99", "100"))),
                    new CasoDelPadron("C-09", "60 UIT +1c", List.of(p("P-09", "330000.01", "100"))),
                    // Tercer tramo.
                    new CasoDelPadron("C-10", "TRAMO 3", List.of(p("P-10", "1000000.00", "100"))),
                    // LA PONDERACION. Dos predios al 50 %: la base es 100 000, no 200 000. Es el
                    // hallazgo que #395 midio —sin ponderar, 330 soles de menos por contribuyente
                    // en todo el padron— y el dato con el que se pondera sale de `titularidad`,
                    // que es de `catastro`: si esta etapa lo rompiera, esta linea lo diria.
                    new CasoDelPadron(
                            "C-11",
                            "COPROPIEDAD",
                            List.of(p("P-11", "100000.00", "50"), p("P-12", "100000.00", "50"))),
                    // Tres predios con cuotas que no son redondas: donde el redondeo de la base
                    // del predio se ve.
                    new CasoDelPadron(
                            "C-12",
                            "TRES CUOTAS",
                            List.of(
                                    p("P-13", "90000.00", "33.33"),
                                    p("P-14", "150000.00", "25"),
                                    p("P-15", "77777.77", "66.67"))));

    private static PredioDelCaso p(String codigo, String autovaluo, String porcentaje) {
        return new PredioDelCaso(codigo, autovaluo, porcentaje);
    }

    private DeterminacionPredialCalculada determinar(CasoDelPadron caso) {
        PrediosDePrueba predios = new PrediosDePrueba();
        List<DeterminarPredial.PredioDeclarado> declarados = new ArrayList<>();
        long predioId = 1;
        for (PredioDelCaso predio : caso.autovaluos()) {
            predios.con(
                    predioId,
                    predio.codigo(),
                    "Direccion de " + predio.codigo(),
                    Porcentaje.de(predio.porcentaje()));
            declarados.add(
                    new DeterminarPredial.PredioDeclarado(
                            predioId, Dinero.de(predio.autovaluo()), null));
            predioId++;
        }
        return servicio(caso, predios)
                .determinar(
                        new DeterminarPredial.Peticion(
                                EJERCICIO, caso.codigo(), declarados, "TRIMESTRAL", true),
                        PORQUE);
    }

    private DeterminarPredial servicio(CasoDelPadron caso, PrediosDelContribuyente predios) {
        LectorDeParametros lector = lector(conjunto().construir());
        return new DeterminarPredial(
                new PadronPredialDelEjercicio(determinaciones),
                predios,
                new SinCaracteristicas(),
                new DirectorioDelPadron(caso),
                new CuadroPredialParametrizado(lector),
                new RegistrarDeterminacionPredial(determinaciones, lector, registro -> {}, RELOJ),
                RELOJ);
    }

    // ---------------------------------------------------------------- el cuadro

    private static ParametrosSellados.Constructor conjunto() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .texto("REDONDEO", "CUOTA", "HALF_UP");
    }

    private static LectorDeParametros lector(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto conjunto) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }

    private static String sha256(String contenido) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(contenido.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    // ---------------------------------------------------------------- dobles

    private static final class PrediosDePrueba implements PrediosDelContribuyente {

        private final List<PredioDelContribuyente> suyos = new ArrayList<>();

        void con(long predioId, String codigo, String direccion, Porcentaje cuota) {
            suyos.add(new PredioDelContribuyente(predioId, codigo, "URBANO", direccion, cuota));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.copyOf(suyos);
        }
    }

    private static final class SinCaracteristicas implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class DirectorioDelPadron implements DirectorioDeContribuyentes {

        private final ResumenDeContribuyente quien;

        DirectorioDelPadron(CasoDelPadron caso) {
            this.quien = new ResumenDeContribuyente(501L, caso.codigo(), caso.nombre(), "03593174");
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return quien.codigo().equals(codigo) ? Optional.of(quien) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            if (ids.contains(quien.id())) {
                encontrados.put(quien.id(), quien);
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /** Sin nada sembrado: cada caso se determina por primera vez, y en simulacion. */
    private static final class DeterminacionesEnMemoria implements DeterminacionRepository {

        @Override
        public Optional<Determinacion> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.of();
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return Optional.empty();
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return List.of();
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            return determinacion;
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            return determinacion;
        }
    }
}
