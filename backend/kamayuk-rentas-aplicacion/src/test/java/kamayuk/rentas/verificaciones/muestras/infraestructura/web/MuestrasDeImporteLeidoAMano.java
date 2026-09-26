package kamayuk.rentas.verificaciones.muestras.infraestructura.web;

import java.math.BigDecimal;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.web.EntradaNumerica;

/**
 * Muestras para {@code ElImporteTecleadoSeLeeEnUnSitioTest} (#395): tres formas de leer a mano un
 * importe o un area tecleados en un controlador, y la que si vale.
 *
 * <p>Viven en {@code src/test}, en un paquete {@code infraestructura.web} para que la regla las
 * mire igual que a un controlador; la regla de produccion solo importa {@code src/main}.
 */
@SuppressWarnings("unused")
public final class MuestrasDeImporteLeidoAMano {

    private MuestrasDeImporteLeidoAMano() {}

    /** El parser de antes de #395: acepta cualquier escala. */
    static Dinero conBigDecimal(String texto) {
        return new Dinero(new BigDecimal(texto));
    }

    /** Lo mismo, por la fabrica del objeto de valor. */
    static Dinero conLaFabrica(String texto) {
        return Dinero.de(texto);
    }

    /** Y el area. */
    static AreaM2 elArea(String texto) {
        return AreaM2.de(texto);
    }

    /** La que vale: la lectura unica, que rechaza el tercer decimal. */
    static Dinero comoSeLee(String texto) {
        return new Dinero(EntradaNumerica.leer(texto, "insoluto", "no es un importe"));
    }
}
