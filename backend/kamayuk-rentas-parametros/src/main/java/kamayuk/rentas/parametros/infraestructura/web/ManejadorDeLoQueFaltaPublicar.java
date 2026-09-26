package kamayuk.rentas.parametros.infraestructura.web;

import java.util.Objects;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * La familia «falta publicar» entera contesta su 422 con {@code parametroQueFalta}, la nombre un
 * {@code catch} o no (#435).
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>Hasta #435 cada controlador enumeraba a mano, en su {@code catch}, las excepciones que su caso
 * de uso podia lanzar. La cifra que se parametrizara despues y se olvidara en una de esas listas
 * caia en {@code ManejadorDeErrores.cualquierOtra}: {@code 500 ERROR_INTERNO} con incidencia, en
 * vez del 422 que dice «hay que publicar esta llave». Aqui la familia es un tipo, {@link
 * CifraSinPublicar}, y se traduce una sola vez.
 *
 * <h2>Por que aqui y no en {@code ManejadorDeErrores}</h2>
 *
 * <p>{@code kamayuk-rentas-plataforma} es la base del grafo y no depende de ningun contexto, asi
 * que no puede nombrar {@link CifraSinPublicar}. Este modulo si puede nombrar las dos mitades, y el
 * cuerpo lo sigue componiendo {@link ManejadorDeErrores}: aqui solo se decide <b>que</b> problema
 * es, no como se escribe.
 *
 * <h2>Por que {@code @Order(HIGHEST_PRECEDENCE)}</h2>
 *
 * <p>Spring elige el <b>primer</b> <i>advice</i> que tenga un manejador aplicable, no el mas
 * especifico entre todos, y {@code ManejadorDeErrores} tiene uno para {@code Exception.class}. Sin
 * el orden, cual gana depende del orden en que se registren los beans, y el 500 vuelve en silencio.
 * Que las dos cosas hacen falta lo mide {@code LoQueFaltaPublicarSeTraduceEnUnSitioTest}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorDeLoQueFaltaPublicar {

    private final ManejadorDeErrores manejador;

    public ManejadorDeLoQueFaltaPublicar(ManejadorDeErrores manejador) {
        this.manejador = Objects.requireNonNull(manejador, "El cuerpo lo compone la plataforma");
    }

    @ExceptionHandler(CifraSinPublicar.class)
    public ResponseEntity<ProblemDetail> faltaPublicar(CifraSinPublicar falta) {
        return manejador.problemaDeNegocio(FaltaPublicar.problema(falta));
    }
}
