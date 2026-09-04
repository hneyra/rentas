package kamayuk.rentas.parametros.dominio;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un conjunto sellado de {@code normativa}, tal como llega y tal como se guarda (ADR-0025 §1).
 *
 * <p>Es el mismo contenido que {@code normativa} compone, con la forma minima que hace falta para
 * escribirlo en la cache local y leerlo despues. No lleva ningun tipo del dominio de {@code
 * normativa}: los valores viajan como texto y se convierten al construir {@link
 * kamayuk.rentas.parametros.ParametrosSellados}, porque quien decide como se lee un {@code
 * ValorNormativo} es este sistema y no el que lo publica.
 *
 * <p><b>La vigencia viaja sin resolver</b>, a proposito: un conjunto sellado contiene el historico
 * de una llave —cinco filas de {@code UIT}, de 2022 a 2026— y cual rige lo decide el lector contra
 * el ejercicio del conjunto (#659). Que {@code normativa} lo resolviera moveria esa decision al
 * servidor y la haria invisible desde aqui.
 *
 * @param sha256 la huella de los bytes con que llego, ya verificada. Se guarda con la fila: sin
 *     ella no se puede decir despues que se comprobo
 */
public record SnapshotDeNormativa(
        long conjuntoId,
        Ejercicio ejercicio,
        int version,
        String ambito,
        String sha256,
        String origen,
        List<Parametro> parametros,
        List<ValorUnitario> valoresUnitarios,
        List<Depreciacion> depreciaciones,
        List<ValorReferencial> valoresReferenciales) {

    public SnapshotDeNormativa {
        Objects.requireNonNull(ejercicio, "El snapshot lleva el ejercicio de su conjunto");
        Objects.requireNonNull(ambito, "El snapshot lleva el ambito con que se pidio");
        Objects.requireNonNull(
                sha256, "Un snapshot sin huella no se puede guardar: no se verifico");
        parametros = List.copyOf(parametros);
        valoresUnitarios = List.copyOf(valoresUnitarios);
        depreciaciones = List.copyOf(depreciaciones);
        valoresReferenciales = List.copyOf(valoresReferenciales);
    }

    public int filas() {
        return parametros.size()
                + valoresUnitarios.size()
                + depreciaciones.size()
                + valoresReferenciales.size();
    }

    /** Un valor normativo con su vigencia, sin resolver. */
    public record Parametro(
            String tipo,
            @Nullable String clave,
            @Nullable String valorNumerico,
            @Nullable String valorTexto,
            @Nullable String vigenciaDesde,
            @Nullable String vigenciaHasta,
            String documentoFuente) {}

    /** Una celda del cuadro de valores unitarios de edificacion. */
    public record ValorUnitario(
            String partida,
            String categoria,
            int anioConstruccionDesde,
            @Nullable Integer anioConstruccionHasta,
            String valorM2,
            String documentoFuente) {}

    /** Una fila del cuadro de depreciacion. El tope nulo es «mas de 50 anios», no cero. */
    public record Depreciacion(
            String uso,
            String material,
            String estadoConservacion,
            @Nullable Integer antiguedadHasta,
            String porcentaje,
            String documentoFuente) {}

    /** Una fila del anexo de valores referenciales del MEF. */
    public record ValorReferencial(
            int ejercicio,
            String categoria,
            String marca,
            String modelo,
            int anioFabricacion,
            String valor,
            String documentoFuente) {}
}
