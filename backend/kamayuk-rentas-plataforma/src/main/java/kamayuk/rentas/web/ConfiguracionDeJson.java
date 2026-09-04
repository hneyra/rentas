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
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeJson {

    @Bean
    public SimpleModule moduloDeObjetosDeValor() {
        SimpleModule modulo = new SimpleModule("sgtm-objetos-de-valor");

        registrar(modulo, Dinero.class, d -> d.valor().toPlainString(), Dinero::de);
        registrar(modulo, Alicuota.class, a -> a.valor().toPlainString(), Alicuota::de);
        registrar(modulo, Porcentaje.class, p -> p.valor().toPlainString(), Porcentaje::de);
        registrar(modulo, AreaM2.class, a -> a.valor().toPlainString(), AreaM2::de);

        return modulo;
    }

    private static <T> void registrar(
            SimpleModule modulo,
            Class<T> tipo,
            Function<T, String> aTexto,
            Function<String, T> desdeTexto) {

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
                        return desdeTexto.apply(new BigDecimal(texto.trim()).toPlainString());
                    }
                });
    }
}
