package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.TipoValor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Que las claves con las que el derivado publica los plazos son <b>exactamente</b> las que esta
 * clase lee (#192).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>Uno que no se ve: un plazo publicado bajo una clave que nadie lee. El proceso de publicacion
 * lo informaria como publicado, el conjunto se sellaria con el dentro, {@code
 * verificar-publicacion.mjs} pasaria en verde —la cifra esta en la norma, las firmas son las del
 * corpus— y la operacion seguiria fallando con {@code PlazoSinParametrizar}, que es el sintoma de
 * «no esta cargado» y no el de «esta cargado con otro nombre». Un simple guion bajo en {@code
 * PRESCRIPCION-DECLARACION_PRESENTADA} basta.
 *
 * <p>Aqui no se escribe ninguna cifra ni ninguna clave: las dos se <b>leen del archivo</b> que el
 * repositorio despliega, y lo que se comprueba es que {@link PlazosParametrizados} las encuentre.
 * La ida y la vuelta contra PostgreSQL —publicar, componer, sellar y releer— la hace {@code
 * PublicarParametrosTest}; lo que falta aqui es el ultimo tramo, el del consumidor real.
 */
@DisplayName("Los plazos de prescripcion, leidos del derivado que se publica (#192)")
class PlazosDelDerivadoTest {

    /** El derivado que este repositorio versiona, tal como se despliega. */
    private static final Path DERIVADO = CorpusDeNormativa.derivadoPublicable();

    private static final String TIPO_PLAZO = "PLAZO";

    /** Una fecha cualquiera dentro de un ejercicio: el conjunto lo entrega el doble. */
    private static final LocalDate UN_DIA = LocalDate.of(2026, 3, 16);

    @ParameterizedTest
    @EnumSource(CausalDePrescripcion.class)
    @DisplayName("cada causal del art. 43 encuentra su plazo, con la clave que el derivado publica")
    void cadaCausalEncuentraSuPlazo(CausalDePrescripcion causal) throws IOException {
        Map<String, String> publicados = plazosDelDerivado();
        PlazosParametrizados plazos = new PlazosParametrizados(new DelDerivado(publicados));

        Plazo leido = plazos.aLaFechaDe(UN_DIA).paraPrescribir(causal);

        assertThat(leido.toString())
                .as(
                        "PLAZO:PRESCRIPCION-%s es la llave que esta clase lee; si el derivado la"
                                + " publica con otra, el plazo esta cargado y la operacion falla"
                                + " igual",
                        causal.name())
                .isEqualTo(publicados.get("PRESCRIPCION-" + causal.name()));
    }

    @Test
    @DisplayName("y sin esa fila, la operacion falla nombrando la llave que falta")
    void sinLaFilaFallaNombrandoLaLlave() {
        PlazosParametrizados plazos = new PlazosParametrizados(new DelDerivado(Map.of()));

        assertThatThrownBy(
                        () ->
                                plazos.aLaFechaDe(UN_DIA)
                                        .paraPrescribir(CausalDePrescripcion.SIN_DECLARACION))
                .as(
                        "es la contraprueba de la de arriba: sin el parametro esto es rojo, asi que"
                                + " lo que la otra demuestra es que el derivado lo trae")
                .isInstanceOf(PlazosParametrizados.PlazoSinParametrizar.class)
                .hasMessageContaining("PLAZO:PRESCRIPCION-SIN_DECLARACION");
    }

    @ParameterizedTest
    @EnumSource(TipoValor.class)
    @DisplayName(
            "cada tipo de valor encuentra su plazo de reclamacion, con la clave que el derivado"
                    + " publica")
    void cadaTipoDeValorEncuentraSuPlazoDeReclamacion(TipoValor tipo) throws IOException {
        Map<String, String> publicados = plazosDelDerivado();
        PlazosParametrizados plazos = new PlazosParametrizados(new DelDerivado(publicados));

        Plazo leido = plazos.aLaFechaDe(UN_DIA).paraNotificar(tipo);

        assertThat(leido.toString())
                .as(
                        "PLAZO:NOTIFICACION_VALOR-%s es la llave que esta clase lee; si el derivado"
                                + " la publica con otra, el plazo esta cargado y la operacion falla"
                                + " igual",
                        tipo.codigo())
                .isEqualTo(publicados.get("NOTIFICACION_VALOR-" + tipo.codigo()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREDIAL", "VEHICULAR"})
    @DisplayName(
            "el inicio del computo de cada tributo declarado, con la clave que el derivado publica")
    void elInicioDelComputoDeCadaTributoDeclarado(String tributo) throws IOException {
        Map<String, String> publicados = plazosDelDerivado();
        PlazosParametrizados plazos = new PlazosParametrizados(new DelDerivado(publicados));

        Plazo leido = plazos.aLaFechaDe(UN_DIA).inicioDelComputo(tributo);

        assertThat(leido.toString())
                .as(
                        "PLAZO:PRESCRIPCION_INICIO-%s es la llave que esta clase lee; arbitrios no"
                                + " esta aqui a proposito: su numeral del art. 44 es una decision"
                                + " abierta y el derivado no lo publica",
                        tributo)
                .isEqualTo(publicados.get("PRESCRIPCION_INICIO-" + tributo));
    }

    @Test
    @DisplayName("el derivado publica los tres plazos del art. 43, y ninguno se lee sin unidad")
    void losTresPlazosDelArticulo43() throws IOException {
        Map<String, String> publicados = plazosDelDerivado();

        for (CausalDePrescripcion causal : CausalDePrescripcion.values()) {
            String forma = publicados.get("PRESCRIPCION-" + causal.name());
            assertThat(forma)
                    .as("la causal %s no tiene fila en el derivado", causal.name())
                    .isNotNull();
            assertThat(Plazo.de(forma).unidad())
                    .as("una cantidad sin unidad no es un plazo, y esto lo lee del archivo")
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------

    /** Las filas {@code PLAZO} del derivado: {@code clave -> valor_maquina}. */
    static Map<String, String> plazosDelDerivado() throws IOException {
        Map<String, String> plazos = new LinkedHashMap<>();
        try (Reader lectura = Files.newBufferedReader(DERIVADO, StandardCharsets.UTF_8)) {
            for (FilaCsv fila : LectorDeFilasCsv.leer(lectura)) {
                List<String> campos = fila.campos();
                if (campos.get(0).equals(TIPO_PLAZO)) {
                    // La columna 10 es `valor_maquina`, la forma que el codigo consume: la norma
                    // escribe «cuatro (4) anios» y Plazo.de solo acepta «4 ANIOS» (#192).
                    plazos.put(campos.get(1), campos.get(10));
                }
            }
        }
        return plazos;
    }

    /** Un {@link LectorDeParametros} con un solo conjunto dentro: el que dice el archivo. */
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
