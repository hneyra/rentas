package kamayuk.rentas.web;

import java.math.BigDecimal;
import java.util.function.Function;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Como se serializan los objetos de valor del dominio.
 *
 * <p><b>Todo decimal sale como cadena, nunca como numero JSON.</b> El {@code number} de JavaScript
 * es un binario de doble precision: {@code 0.1 + 0.2} no es {@code 0.3}, y un importe con muchos
 * digitos se redondea al leerlo en el navegador. Es exactamente el defecto que la regla 1 prohibe
 * en Java, y no tendria sentido protegerlo en el servidor y perderlo en el transporte (RNF-055). El
 * contrato lo dice igual: su esquema {@code Importe} es {@code type: string}.
 *
 * <p>Se resuelve aqui, en un modulo de Jackson, y no anotando cada DTO: una anotacion que hay que
 * acordarse de poner en 134 pantallas es una anotacion que faltara en alguna.
 *
 * <p>API de <b>Jackson 3</b> ({@code tools.jackson}), que es la que trae Spring Boot 4: {@code
 * ValueSerializer} donde Jackson 2 tenia {@code JsonSerializer}.
 *
 * <h2>Un importe o un area que LLEGAN en el cuerpo se leen como los tecleados (#395, #629)</h2>
 *
 * <p>El cuerpo JSON es la tercera puerta de lo tecleado, despues del parametro del controlador y
 * del archivo que se importa, y lee con la misma regla: {@link EntradaNumerica}, o sea {@link
 * kamayuk.rentas.compartido.CifraTecleada}. Un tercer decimal es 422 nombrando el campo; hasta #629
 * se aceptaba cualquier escala y la columna {@code dinero numeric(15,2)} la redondeaba en silencio.
 * Al medirlo ningun cuerpo de produccion llevaba un {@link Dinero} ni un {@link AreaM2} —los doce
 * controladores leen texto—, asi que la puerta estaba abierta sin que nadie pasara todavia: el
 * primero que tipara un campo con el objeto de valor habria heredado el redondeo.
 *
 * <p>La alicuota y el porcentaje no: tienen su propia escala, y los lee su objeto de valor.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeJson {

    /** El campo de un valor suelto, que no esta dentro de ningun objeto. */
    private static final String SIN_CAMPO = "valor";

    @Bean
    public SimpleModule moduloDeObjetosDeValor() {
        SimpleModule modulo = new SimpleModule("kamayuk-objetos-de-valor");

        registrar(
                modulo,
                Dinero.class,
                d -> d.valor().toPlainString(),
                (texto, campo) ->
                        new Dinero(
                                EntradaNumerica.leer(
                                        texto,
                                        campo,
                                        "El campo '"
                                                + campo
                                                + "' no es un importe: '"
                                                + texto
                                                + "'")));
        registrar(
                modulo,
                Alicuota.class,
                a -> a.valor().toPlainString(),
                (texto, campo) -> Alicuota.de(enTextoPlano(texto)));
        registrar(
                modulo,
                Porcentaje.class,
                p -> p.valor().toPlainString(),
                (texto, campo) -> Porcentaje.de(enTextoPlano(texto)));
        registrar(
                modulo,
                AreaM2.class,
                a -> a.valor().toPlainString(),
                (texto, campo) ->
                        new AreaM2(
                                EntradaNumerica.leer(
                                        texto,
                                        campo,
                                        "El campo '"
                                                + campo
                                                + "' no es un area: '"
                                                + texto
                                                + "'")));

        return modulo;
    }

    /**
     * Como se lee un objeto de valor del texto que llego, sabiendo en que campo llego: el nombre
     * hace falta para decir cual se rechaza.
     */
    @FunctionalInterface
    private interface Lectura<T> {
        T leer(String texto, String campo);
    }

    /**
     * La alicuota y el porcentaje, como siempre: sin blancos y sin notacion cientifica. Su escala
     * no es la de lo tecleado, y la valida su objeto de valor.
     */
    private static String enTextoPlano(String texto) {
        return new BigDecimal(texto.strip()).toPlainString();
    }

    private static <T> void registrar(
            SimpleModule modulo, Class<T> tipo, Function<T, String> aTexto, Lectura<T> desdeTexto) {

        modulo.addSerializer(
                tipo,
                new ValueSerializer<T>() {
                    @Override
                    public void serialize(
                            T valor, JsonGenerator generador, SerializationContext contexto) {
                        generador.writeString(aTexto.apply(valor));
                    }
                });

        modulo.addDeserializer(
                tipo,
                new ValueDeserializer<T>() {
                    @Override
                    public T deserialize(JsonParser lector, DeserializationContext contexto) {
                        // Se acepta tambien el numero, para no romper a un cliente que
                        // mande 100 en vez de "100.00"; lo que no se hace nunca es
                        // *emitir* un numero. BigDecimal lee el texto exacto.
                        String texto = lector.getValueAsString();
                        String campo = lector.currentName();
                        return desdeTexto.leer(texto, campo == null ? SIN_CAMPO : campo);
                    }
                });
    }
}
