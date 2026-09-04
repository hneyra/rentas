package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import org.jspecify.annotations.Nullable;

/**
 * El porcentaje de depreciacion de una edificacion, por <b>uso</b>, material, estado de
 * conservacion y antiguedad (RT-002 a RT-004, NEG-05 de {@code ../srtm}).
 *
 * <p><b>El uso es parte de la identidad de la fila, no un adorno</b> (H-15, {@code V57}). El Anexo
 * I del Reglamento Nacional de Tasaciones publica <b>cuatro</b> tablas —01 vivienda, 02 tiendas y
 * depositos, 03 oficinas, 04 salud, industria y educacion— y {@code uso} guarda el numero con que
 * la propia norma las identifica. Sin el, las cuatro colapsan en una: de las 492 celdas que el
 * Anexo tabula quedarian 127, y 120 de esas 127 llevan un porcentaje <b>distinto</b> segun el uso.
 * Que tabla le toca a un predio es criterio y no vive aqui: {@code RT-004} todavia no esta escrita.
 *
 * <p>{@code antiguedadHasta} es un tramo, no un ano exacto: la tabla oficial da la depreciacion
 * «hasta N anios de antiguedad», y la fila con el {@code antiguedadHasta} mas alto entre las que lo
 * cubren es la que aplica —la misma logica de tramo que ya usa esta tabla desde V1, y que {@link
 * ValorUnitarioEdificacion} adopta ahora para el ano de construccion—. <b>Nulo es «mas de 50
 * anios»</b>, el tramo abierto con que cierra cada tabla: un centinela seria una cifra inventada
 * dentro de un cuadro normativo, y ademas una que se lee igual que un tope de verdad.
 *
 * <p>Cuelga de un conjunto sellado y no de un ejercicio suelto (#17, mismo mecanismo que #10): un
 * ejercicio puede tener mas de una version sellada, y solo el conjunto dice cual rigio una
 * determinacion concreta.
 *
 * @param id nulo mientras la fila no se ha guardado; lo asigna la base
 * @param uso la tabla del Anexo I a la que pertenece la fila: {@code 01}..{@code 04}
 * @param antiguedadHasta el tope del tramo en anios; nulo es el tramo abierto, sin tope
 */
public record Depreciacion(
        @Nullable Long id,
        String uso,
        String material,
        String estadoConservacion,
        @Nullable Integer antiguedadHasta,
        Alicuota porcentaje,
        String documentoFuente) {

    private static final int TEXTO_MAXIMO = 20;
    private static final int DOCUMENTO_MAXIMO = 200;

    public Depreciacion {
        Objects.requireNonNull(uso, "La depreciacion necesita la tabla del Anexo I a la que va");
        Objects.requireNonNull(material, "La depreciacion necesita el material");
        Objects.requireNonNull(estadoConservacion, "La depreciacion necesita el estado");
        Objects.requireNonNull(porcentaje, "La depreciacion necesita su porcentaje");
        Objects.requireNonNull(documentoFuente, "Cargar sin documento fuente falla (ADR-0007)");
        uso = uso.strip();
        if (!uso.matches("0[1-4]")) {
            throw new IllegalArgumentException(
                    "El uso es una de las cuatro tablas del Anexo I (01..04): '" + uso + "'");
        }
        material = exigirCorto(material, "material");
        estadoConservacion = exigirCorto(estadoConservacion, "estado de conservacion");
        if (antiguedadHasta != null && antiguedadHasta <= 0) {
            throw new IllegalArgumentException(
                    "El tramo de antiguedad es mayor que cero, o nulo si no tiene tope: "
                            + antiguedadHasta);
        }
        documentoFuente = documentoFuente.strip();
        if (documentoFuente.isEmpty() || documentoFuente.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento fuente va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
    }

    private static String exigirCorto(String valor, String nombre) {
        String recortado = valor.strip();
        if (recortado.isEmpty() || recortado.length() > TEXTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El " + nombre + " va de 1 a " + TEXTO_MAXIMO + " caracteres: '" + valor + "'");
        }
        return recortado;
    }

    /** Una depreciacion que todavia no esta en la base. */
    public static Depreciacion nueva(
            String uso,
            String material,
            String estadoConservacion,
            @Nullable Integer antiguedadHasta,
            Alicuota porcentaje,
            String documentoFuente) {
        return new Depreciacion(
                null,
                uso,
                material,
                estadoConservacion,
                antiguedadHasta,
                porcentaje,
                documentoFuente);
    }

    /** Si esta fila cubre el tramo abierto con que cierra su tabla («mas de 50 anios»). */
    public boolean sinTope() {
        return antiguedadHasta == null;
    }
}
