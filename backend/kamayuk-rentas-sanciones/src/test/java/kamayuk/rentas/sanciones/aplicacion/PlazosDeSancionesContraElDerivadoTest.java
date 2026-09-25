package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.UnidadDePlazo;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cada llave de plazo que {@code sanciones} pide, contra el derivado que {@code normativa}
 * despliega (#410; el patron de {@code valores.PlazosDelDerivadoTest} y {@code
 * coactiva.PlazoDeLaRec1DelDerivadoTest}, #192, con las dos direcciones de {@code
 * nucleo.LlavesDelConjuntoContraElDerivadoTest}, #376).
 *
 * <h2>Que ata</h2>
 *
 * <p>#410 nombro una llave nueva, {@code PLAZO:RG_RECURSO}: lo que la sancionadora y la RIS
 * conceden para impugnarlas. <b>El nombre lo fija la transcripcion en {@code normativa}</b>, y hoy
 * no esta transcrita —ni esa ni las otras dos que este modulo lee—. El defecto que esta clase
 * existe para impedir es el de #192: que el dia que {@code normativa} la publique lo haga con
 * <b>otro</b> nombre, el conjunto se selle con ella dentro y la RIS siga contestando 422 «falta
 * publicar» sobre una cifra publicada.
 *
 * <ul>
 *   <li><b>De la llave al derivado.</b> Las llaves no se escriben aqui: se <b>preguntan</b> a
 *       {@link PlazosDeSancionesParametrizados} con un conjunto vacio, y cada una sale nombrada en
 *       su {@code PlazoSinParametrizar}. Una llave nueva —o un cuarto tipo de resolucion— entra
 *       sola. La que el derivado todavia no publica se declara en {@link #SIN_PUBLICAR}; la que ya
 *       publica tiene que leerse en dias <b>habiles</b>.
 *   <li><b>Del derivado a la llave.</b> Toda fila {@code PLAZO} del derivado la lee este modulo o
 *       esta declarada en {@link #DE_OTROS_MODULOS} con quien la lee. Es la que pone rojo el dia
 *       que llegue {@code RECURSO_RECONSIDERACION} en vez de {@code RG_RECURSO}: la primera
 *       direccion sola seguiria verde, porque solo mira que el derivado no publique <b>su</b>
 *       nombre.
 * </ul>
 */
@DisplayName("#410 — Cada plazo que sanciones pide, contra el derivado que normativa despliega")
class PlazosDeSancionesContraElDerivadoTest {

    /** El derivado que este repositorio versiona, tal como se despliega. */
    private static final Path DERIVADO = CorpusDeNormativa.derivadoPublicable();

    private static final String TIPO_PLAZO = "PLAZO";

    private static final LocalDate UN_DIA = LocalDate.of(2026, 8, 3);

    /**
     * Las que el derivado no publica hoy, y por que. No son un olvido: son el 422 que la operacion
     * contesta hoy nombrando la llave, y es el correcto. El dia que una se publique, sale de aqui.
     */
    private static final Map<String, String> SIN_PUBLICAR =
            Map.of(
                    "PLAZO:DESCARGO_PAPELETA",
                    "el plazo del descargo de la papeleta (pantalla transito_descargos) no esta"
                            + " transcrito en el corpus",
                    "PLAZO:RG_ORDINARIA_CUMPLIMIENTO",
                    "el plazo de pago de la ordinaria de transito (pantalla transito_rg_ordinaria)"
                            + " no esta transcrito en el corpus",
                    "PLAZO:RG_RECURSO",
                    "#410: el plazo para impugnar la sancionadora y la RIS (art. 218.2 del TUO de"
                            + " la Ley 27444) se nombra aqui y lo transcribe y sella normativa");

    /**
     * Los prefijos de las filas {@code PLAZO} que el derivado publica y otro modulo lee, con quien.
     * Una fila que no case con ninguno ni la pida {@code sanciones} es, casi siempre, una de las de
     * {@link #SIN_PUBLICAR} publicada con otro nombre.
     */
    private static final Map<String, String> DE_OTROS_MODULOS =
            Map.of(
                    "PRESCRIPCION-",
                    "valores.PlazosParametrizados (art. 43 del TUO del Codigo Tributario)",
                    "PRESCRIPCION_INICIO-",
                    "valores.PlazosParametrizados (art. 44)",
                    "NOTIFICACION_VALOR-",
                    "valores.PlazosParametrizados (arts. 137 y 78)",
                    "REC1_CUMPLIMIENTO",
                    "coactiva.PlazosCoactivosParametrizados (art. 14.1 de la Ley 26979)");

    @Test
    @DisplayName("cada llave que sanciones pide esta publicada en dias habiles, o dice por que no")
    void cadaLlaveEstaPublicadaODicePorQueNo() throws IOException {
        Map<String, String> publicados = plazosDelDerivado();

        for (String llave : llavesQueSancionesPide()) {
            String clave = llave.substring((TIPO_PLAZO + ":").length());
            if (SIN_PUBLICAR.containsKey(llave)) {
                assertThat(publicados)
                        .as(
                                "%s ya se publica en el derivado: sacala de SIN_PUBLICAR, y esta"
                                        + " prueba comprobara que se lea en dias habiles",
                                llave)
                        .doesNotContainKey(clave);
                continue;
            }
            assertThat(publicados)
                    .as(
                            "%s la pide sanciones y el derivado no la publica: o normativa la"
                                    + " publica con otro nombre, o falta declararla en"
                                    + " SIN_PUBLICAR con su motivo",
                            llave)
                    .containsKey(clave);
            Plazo leido = Plazo.de(publicados.get(clave));
            assertThat(leido.unidad())
                    .as(
                            "los plazos del procedimiento administrativo se cuentan en dias"
                                    + " habiles (art. 145 del TUO de la Ley 27444): %s",
                            llave)
                    .isEqualTo(UnidadDePlazo.DIAS_HABILES);
        }
    }

    @Test
    @DisplayName("y toda fila PLAZO del derivado la lee sanciones o un modulo declarado")
    void todaFilaDelDerivadoTieneQuienLaLea() throws IOException {
        Set<String> deSanciones = new TreeSet<>();
        for (String llave : llavesQueSancionesPide()) {
            deSanciones.add(llave.substring((TIPO_PLAZO + ":").length()));
        }

        Set<String> huerfanas = new TreeSet<>();
        for (String clave : plazosDelDerivado().keySet()) {
            boolean deOtro = DE_OTROS_MODULOS.keySet().stream().anyMatch(clave::startsWith);
            if (!deOtro && !deSanciones.contains(clave)) {
                huerfanas.add(clave);
            }
        }

        assertThat(huerfanas)
                .as(
                        "estas filas PLAZO no las pide nadie: si alguna es %s publicada con otro"
                                + " nombre, la operacion sigue contestando 422 sobre una cifra"
                                + " sellada. Renombra la llave que se pide, o declara quien la lee"
                                + " en DE_OTROS_MODULOS",
                        SIN_PUBLICAR.keySet())
                .isEmpty();
    }

    @Test
    @DisplayName("las llaves se preguntan a la clase, y son las tres que #410 dejo")
    void lasLlavesSonLasDeLaClase() {
        assertThat(llavesQueSancionesPide())
                .as(
                        "es la contraprueba de las otras dos: si la pregunta no devolviera nada,"
                                + " las dos pasarian en verde sin mirar ninguna llave")
                .containsExactlyInAnyOrder(
                        "PLAZO:DESCARGO_PAPELETA",
                        "PLAZO:RG_ORDINARIA_CUMPLIMIENTO",
                        "PLAZO:RG_RECURSO");
    }

    // ------------------------------------------------------------------

    /**
     * Las llaves que {@link PlazosDeSancionesParametrizados} pide, preguntadas con un conjunto sin
     * ningun plazo: cada lectura falla nombrando la suya. Recorre <b>todos</b> los tipos de
     * resolucion, asi que un cuarto tipo entra sin tocar esta prueba.
     */
    private static Set<String> llavesQueSancionesPide() {
        PlazosDeSancionesParametrizados.Vigentes vacio =
                new PlazosDeSancionesParametrizados(new DelDerivado(Map.of())).aLaFechaDe(UN_DIA);
        Set<String> llaves = new TreeSet<>();
        llaves.add(llaveQueFalta(vacio::paraDescargar));
        for (TipoDeResolucionDeGerencia tipo : TipoDeResolucionDeGerencia.values()) {
            llaves.add(llaveQueFalta(() -> vacio.queConcede(tipo)));
        }
        return llaves;
    }

    private static String llaveQueFalta(Runnable lectura) {
        try {
            lectura.run();
        } catch (PlazosDeSancionesParametrizados.PlazoSinParametrizar falta) {
            return falta.llave().orElseThrow();
        }
        throw new AssertionError("con un conjunto vacio, la lectura tenia que fallar");
    }

    /** Las filas {@code PLAZO} del derivado: {@code clave -> valor_maquina}. */
    private static Map<String, String> plazosDelDerivado() throws IOException {
        Map<String, String> plazos = new LinkedHashMap<>();
        try (Reader lectura = Files.newBufferedReader(DERIVADO, StandardCharsets.UTF_8)) {
            for (FilaCsv fila : LectorDeFilasCsv.leer(lectura)) {
                List<String> campos = fila.campos();
                if (campos.get(0).equals(TIPO_PLAZO)) {
                    // La columna 10 es `valor_maquina`: la norma escribe «siete (7) dias habiles»
                    // y Plazo.de solo acepta «7 DIAS_HABILES» (#192).
                    plazos.put(campos.get(1), campos.get(10));
                }
            }
        }
        return plazos;
    }

    /** Un {@link LectorDeParametros} con un solo conjunto dentro. */
    private record DelDerivado(Map<String, String> plazos) implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            plazos.forEach((clave, valor) -> constructor.texto(TIPO_PLAZO, clave, valor));
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(2026));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1L);
        }
    }
}
