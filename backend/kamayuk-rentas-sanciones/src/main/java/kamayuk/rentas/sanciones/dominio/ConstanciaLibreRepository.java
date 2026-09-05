package kamayuk.rentas.sanciones.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Las constancias libres de infracciones contra PostgreSQL. Ningún método recibe la municipalidad
 * (regla 2).
 *
 * <p><b>Solo inserta y lee.</b> V47 no le concede a {@code kamayuk_app} ni {@code UPDATE} ni {@code
 * DELETE} sobre {@code constancia_libre}, por lo mismo que V41 se los negó a {@code
 * resolucion_gerencia}: la constancia se entrega al administrado, que se lleva el papel.
 */
public interface ConstanciaLibreRepository {

    ConstanciaLibre registrar(ConstanciaLibre constancia);

    Optional<ConstanciaLibre> porNumero(String numero);

    /** El padrón de constancias emitidas ({@code transito_padron_constancias}). */
    Pagina<ConstanciaLibre> buscar(CriterioDeConstancias criterio, Paginacion paginacion);
}
