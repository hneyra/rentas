package kamayuk.rentas.nucleo.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import org.jspecify.annotations.Nullable;

/**
 * Los valores referenciales, siempre <b>acotados a un conjunto</b>.
 *
 * <p>Ningun metodo admite solo el ejercicio, y es deliberado: resolver por ejercicio devuelve la
 * version que rige hoy, no la que se uso al determinar. Con dos versiones selladas del mismo
 * ejercicio —un valor corregido a mitad de año— recalcular una determinacion daria otra cifra sin
 * ningun error de por medio. Quien traduce el ejercicio a un conjunto es {@code
 * LectorDeParametros}, y una sola vez.
 */
public interface ValorReferencialRepository {

    /**
     * El valor referencial de un vehiculo, si el cuadro sellado lo trae.
     *
     * <p><b>La categoria es parte de la identidad de la fila</b> (#360): el anexo del MEF publica
     * un mismo {@code (marca, modelo, ano)} en mas de una categoria —en la TVR 2026, 466 pares
     * cruzan categorias y 163 traen cifras distintas—. Con la categoria del vehiculo, la consulta
     * se acota a ella; sin ella ({@code null}), se miran todas las del anexo.
     *
     * <p>Si quedan varias filas <b>con cifras distintas</b>, lanza {@link ValorReferencialAmbiguo}
     * en vez de quedarse con la primera: un camion valorizado con la cifra de una camioneta no
     * produce ningun error, produce otra base imponible (ARQ-09 §2.5). Si todas traen <b>la misma
     * cifra</b>, la base no depende de la categoria y se devuelve esa: es la misma suposicion con
     * que ya se aceptaba la fila unica de un vehiculo sin categoria.
     *
     * @param categoria la categoria del vehiculo en el padron, tal como la escribe el anexo; nula
     *     si el padron no la tiene
     */
    Optional<ValorReferencial> buscar(
            IdentificadorDeConjunto conjunto,
            String marca,
            String modelo,
            int anioFabricacion,
            @Nullable String categoria);

    /**
     * Las categorias con que el cuadro del conjunto publica sus filas, ordenadas y sin repetir: el
     * vocabulario del anexo (A1…A4, CAMIONETAS, CAMIONES…), leido del dato y no escrito aqui.
     */
    List<String> categorias(IdentificadorDeConjunto conjunto);

    /** El catalogo de marcas y modelos del conjunto, ordenado y sin repetir. */
    List<MarcaYModelo> catalogo(IdentificadorDeConjunto conjunto);

    /**
     * El cuadro trae mas de una cifra para ese vehiculo y falta la categoria para elegir.
     *
     * <p>No es un fallo del dato: es como la norma lo publica. Lo que falta es el otro lado, la
     * categoria del vehiculo del padron, y por eso el mensaje nombra las categorias candidatas: es
     * lo que la ventanilla tiene que completar.
     *
     * <p>Vivia dentro del adaptador JDBC, y esa era la razon de que el controlador no la viera: la
     * ambiguedad salia como 500 con incidencia (#360). Es de dominio, y su sitio es el puerto que
     * la promete.
     */
    final class ValorReferencialAmbiguo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final List<String> categorias;

        public ValorReferencialAmbiguo(
                String marca,
                String modelo,
                int anioFabricacion,
                @Nullable String categoria,
                List<String> categorias) {
            super(mensaje(marca, modelo, anioFabricacion, categoria, categorias));
            this.categorias = List.copyOf(categorias);
        }

        /** Las categorias de las filas candidatas, ordenadas y sin repetir. */
        public List<String> categorias() {
            return categorias;
        }

        private static String mensaje(
                String marca,
                String modelo,
                int anioFabricacion,
                @Nullable String categoria,
                List<String> categorias) {
            String cual = marca + " " + modelo + " del " + anioFabricacion;
            if (categoria != null) {
                // No deberia ocurrir: `normativa` rechaza la fila repetida dentro de una
                // edicion. Si ocurre, se dice asi y no se elige.
                return "El cuadro sellado trae mas de un valor distinto para "
                        + cual
                        + " dentro de la categoria "
                        + categoria
                        + ": no se puede elegir uno sin dar otra base imponible (ARQ-09 §2.5)";
            }
            return "El cuadro sellado trae valores distintos para "
                    + cual
                    + " en las categorias "
                    + String.join(", ", categorias)
                    + " del anexo, y el vehiculo no tiene categoria en el padron. Registre cual"
                    + " es: elegir una sin saberlo daria otra base imponible sin ningun error de"
                    + " por medio (ARQ-09 §2.5)";
        }
    }
}
