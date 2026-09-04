package kamayuk.rentas.coactiva.aplicacion;

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
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.UnidadDePlazo;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Que la clave con la que el derivado publica el plazo de la REC-1 es <b>exactamente</b> la que
 * {@link PlazosCoactivosParametrizados} lee (#192).
 *
 * <p>Es la hermana de {@code valores.PlazosDelDerivadoTest}, y cierra el mismo defecto invisible:
 * un plazo publicado bajo una clave que nadie lee se informa como publicado, se sella dentro del
 * conjunto y la operacion sigue fallando con {@code PlazoSinParametrizar}, que es el sintoma de «no
 * esta cargado» y no el de «esta cargado con otro nombre».
 *
 * <p>Y aqui la diferencia se paga en dias: el art. 14.1 de la Ley 26979 cuenta el plazo en dias
 * <b>habiles</b>, asi que ademas de la clave se comprueba que la unidad que el archivo publica sea
 * esa. Con {@code DIAS_CALENDARIO} el vencimiento cae antes, y una medida cautelar dictada antes de
 * tiempo es nula.
 */
@DisplayName("El plazo de la REC-1, leido del derivado que se publica (#192)")
class PlazoDeLaRec1DelDerivadoTest {

    /** El derivado que este repositorio versiona, tal como se despliega. */
    private static final Path DERIVADO = CorpusDeNormativa.derivadoPublicable();

    private static final String TIPO_PLAZO = "PLAZO";
    private static final String CLAVE_REC1 = "REC1_CUMPLIMIENTO";

    /** La fecha de una diligencia cualquiera: el conjunto lo entrega el doble. */
    private static final LocalDate UN_DIA = LocalDate.of(2026, 3, 16);

    @Test
    @DisplayName("el derivado publica PLAZO:REC1_CUMPLIMIENTO, y es la llave que esta clase lee")
    void elDerivadoPublicaLaLlaveQueSeLee() throws IOException {
        Map<String, String> publicados = plazosDelDerivado();
        PlazosCoactivosParametrizados plazos =
                new PlazosCoactivosParametrizados(new DelDerivado(publicados));

        Plazo leido = plazos.aLaFechaDe(UN_DIA).paraCumplirLaRec1();

        assertThat(leido.toString())
                .as(
                        "si el derivado lo publicara con otra clave, el plazo estaria cargado y la"
                                + " REC-1 seguiria sin poder dictarse")
                .isEqualTo(publicados.get(CLAVE_REC1));
        assertThat(leido.unidad())
                .as(
                        "el art. 14.1 cuenta en dias HABILES: en calendario el vencimiento cae"
                                + " antes, y la medida cautelar dictada antes de tiempo es nula")
                .isEqualTo(UnidadDePlazo.DIAS_HABILES);
    }

    @Test
    @DisplayName("y el vencimiento que produce salta el fin de semana, no lo atraviesa")
    void elVencimientoCuentaDiasHabiles() throws IOException {
        PlazosCoactivosParametrizados plazos =
                new PlazosCoactivosParametrizados(new DelDerivado(plazosDelDerivado()));
        Plazo leido = plazos.aLaFechaDe(UN_DIA).paraCumplirLaRec1();

        LocalDate lunes = LocalDate.of(2026, 3, 16);
        LocalDate vencimiento = leido.vencimientoDesde(lunes, CalendarioHabil.sinFeriados());

        assertThat(vencimiento.getDayOfWeek().getValue())
                .as("ningun plazo en dias habiles vence en sabado ni en domingo")
                .isLessThanOrEqualTo(5);
        assertThat(vencimiento)
                .as("y cae mas alla de los siete dias de calendario, porque salta el fin de semana")
                .isAfter(lunes.plusDays(leido.cantidad()));
    }

    @Test
    @DisplayName("sin esa fila, la operacion falla nombrando la llave que falta")
    void sinLaFilaFallaNombrandoLaLlave() {
        PlazosCoactivosParametrizados plazos =
                new PlazosCoactivosParametrizados(new DelDerivado(Map.of()));

        assertThatThrownBy(() -> plazos.aLaFechaDe(UN_DIA).paraCumplirLaRec1())
                .as(
                        "es la contraprueba: sin el parametro esto es rojo, asi que lo que la"
                                + " primera demuestra es que el derivado lo trae")
                .isInstanceOf(PlazosCoactivosParametrizados.PlazoSinParametrizar.class)
                .hasMessageContaining("PLAZO:" + CLAVE_REC1);
    }

    // ------------------------------------------------------------------

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
