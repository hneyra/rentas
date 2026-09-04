package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * Lo construido en un piso: su area, su antiguedad, su material, su estado y sus siete categorias.
 *
 * <p><b>No hay un solo importe aqui.</b> El valor unitario de cada categoria, el incremento del 5 %
 * y la tabla de depreciacion son valores normativos que cambian por ejercicio y viven en datos
 * versionados (regla 5, #17). Lo que la ficha guarda es lo que el tecnico midio y clasifico: eso no
 * cambia cuando cambia el cuadro de valores, y por eso recalcular 2027 en 2037 sigue siendo
 * posible.
 *
 * <p>Cuelga de una version de la ficha, no del predio: al versionar se copia con ella, y por eso
 * una version anterior conserva las construcciones que declaraba entonces.
 */
public record Construccion(
        @Nullable Long id,
        @Nullable Long fichaId,
        String piso,
        AreaM2 areaConstruida,
        @Nullable Ejercicio anioConstruccion,
        @Nullable MaterialEstructural material,
        @Nullable EstadoDeConservacion estadoConservacion,
        CategoriasConstructivas categorias,
        @Nullable Porcentaje porcentajeConstruido) {

    private static final int PISO_MAXIMO = 10;

    public Construccion {
        Objects.requireNonNull(piso, "La construccion necesita decir de que piso es");
        Objects.requireNonNull(areaConstruida, "La construccion necesita su area");
        Objects.requireNonNull(categorias, "Las categorias son un objeto, vacio si no hay");
        piso = piso.strip();
        if (piso.isEmpty() || piso.length() > PISO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El piso va de 1 a " + PISO_MAXIMO + " caracteres: '" + piso + "'");
        }
    }

    /** Una construccion que todavia no cuelga de ninguna version de la ficha. */
    public static Construccion en(String piso, AreaM2 area, CategoriasConstructivas categorias) {
        return new Construccion(null, null, piso, area, null, null, null, categorias, null);
    }

    /** La misma construccion colgada de otra version: es lo que hace la copia al versionar. */
    public Construccion enLaFicha(long otraFichaId) {
        return new Construccion(
                null,
                otraFichaId,
                piso,
                areaConstruida,
                anioConstruccion,
                material,
                estadoConservacion,
                categorias,
                porcentajeConstruido);
    }

    public Construccion con(
            Ejercicio anio, MaterialEstructural material, EstadoDeConservacion estado) {
        return new Construccion(
                id,
                fichaId,
                piso,
                areaConstruida,
                anio,
                material,
                estado,
                categorias,
                porcentajeConstruido);
    }
}
