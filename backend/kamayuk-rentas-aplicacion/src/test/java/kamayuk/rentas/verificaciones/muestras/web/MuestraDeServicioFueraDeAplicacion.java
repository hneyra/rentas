package kamayuk.rentas.verificaciones.muestras.web;

import org.springframework.stereotype.Service;

/**
 * Muestra para {@code ApiPublicaSinJavadocTest} (#642): un {@code @Service} fuera de un paquete
 * {@code ..aplicacion..} no es un caso de uso, y sus metodos no se miran. Es la misma definicion
 * que {@code CasosDeUsoSinLlamadorTest}.
 *
 * <p>Clase interna NO estatica, como las de {@code MuestrasDeApiSinJavadoc}, para que el arranque
 * no la registre como bean.
 */
@SuppressWarnings("unused")
public final class MuestraDeServicioFueraDeAplicacion {

    private MuestraDeServicioFueraDeAplicacion() {}

    /** Documentada, porque el tipo si se mira en cualquier paquete. */
    @Service
    public class ServicioDelBorde {

        public void hacer() {}
    }
}
