package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Una version de la ficha catastral de un predio.
 *
 * <p>La invariante que el manual exige y que aqui es el modelo: <b>modificar una ficha no
 * sobrescribe</b> (cap. 2 §Actualizacion del Catastro). El sistema copia la vigente, crea una
 * version nueva con los datos modificados y registra autor, fecha, hora y observacion. La anterior
 * queda cerrada, entera, con todo lo que colgaba de ella.
 *
 * <p>No es formalismo documental. Una determinacion de 2027 se calculo sobre la ficha que estaba
 * vigente en 2027; si esa ficha se hubiera editado en el sitio, la determinacion ya no se podria
 * reproducir ni defender ante una reclamacion.
 *
 * <p><b>La observacion es obligatoria y esta en el tipo</b>, no en un campo opcional del cuerpo de
 * la peticion: la columna es {@code NOT NULL} y {@link Observacion} valida que diga algo (regla 10,
 * RNF-052).
 *
 * <h2>Los cuatro tipos</h2>
 *
 * <p>El mismo tipo sirve para los cuatro (RF-001 a RF-004) porque el mecanismo es el mismo:
 * version, vigencia y copia. Lo que cambia entre ellos va en {@link #detalle}, que es {@link
 * DetalleDeLaFicha} —sellado— y cuyo {@code tipo()} tiene que coincidir con el de la ficha. Las
 * construcciones y las obras complementarias no estan ahi: las pueden tener los cuatro.
 *
 * <p>Aqui no se calcula nada. El area se guarda, no se valoriza; el autovaluo es de rentas y esta
 * bloqueado por D-02a.
 *
 * @param version empieza en 1 y sube de uno en uno por predio y tipo
 * @param denominacion como se llama la unidad: la edificacion, el predio rustico
 * @param vigenciaHasta nulo mientras la version es la vigente
 * @param detalle lo propio del tipo; nulo en la ficha {@code UNICA}, cuyo detalle son las
 *     construcciones
 */
public record FichaCatastral(
        @Nullable Long id,
        long predioId,
        TipoFicha tipo,
        int version,
        AreaM2 areaTerreno,
        String uso,
        @Nullable Medida frontis,
        @Nullable String condicionPropiedad,
        @Nullable String tipoEdificacion,
        @Nullable String denominacion,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        OrigenDeLaFicha origen,
        String documentoOrigen,
        Observacion observacion,
        List<Construccion> construcciones,
        List<OtraInstalacion> instalaciones,
        @Nullable DetalleDeLaFicha detalle) {

    private static final int USO_MAXIMO = 60;
    private static final int DOCUMENTO_MAXIMO = 80;
    private static final int TEXTO_MAXIMO = 40;
    private static final int DENOMINACION_MAXIMA = 160;

    public FichaCatastral {
        Objects.requireNonNull(tipo, "La ficha necesita su tipo");
        Objects.requireNonNull(areaTerreno, "La ficha necesita el area del terreno");
        Objects.requireNonNull(uso, "La ficha necesita el uso del predio");
        Objects.requireNonNull(vigenciaDesde, "La ficha necesita desde cuando rige");
        Objects.requireNonNull(origen, "La ficha necesita decir de donde salio");
        Objects.requireNonNull(documentoOrigen, "La ficha necesita su documento de origen");
        Objects.requireNonNull(
                observacion,
                "Sin observacion no se guarda una version de la ficha (regla 10, RNF-052)");
        Objects.requireNonNull(construcciones, "La lista de construcciones es vacia, no nula");
        Objects.requireNonNull(instalaciones, "La lista de instalaciones es vacia, no nula");

        uso = uso.strip();
        documentoOrigen = documentoOrigen.strip();
        construcciones = List.copyOf(construcciones);
        instalaciones = List.copyOf(instalaciones);

        if (version < 1) {
            throw new IllegalArgumentException("La version de una ficha empieza en 1: " + version);
        }
        if (uso.isEmpty() || uso.length() > USO_MAXIMO) {
            throw new IllegalArgumentException("El uso va de 1 a " + USO_MAXIMO + " caracteres");
        }
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (condicionPropiedad != null && condicionPropiedad.strip().length() > TEXTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "La condicion de propiedad excede " + TEXTO_MAXIMO + " caracteres");
        }
        if (tipoEdificacion != null && tipoEdificacion.strip().length() > TEXTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tipo de edificacion excede " + TEXTO_MAXIMO + " caracteres");
        }
        if (denominacion != null) {
            denominacion = denominacion.strip();
            if (denominacion.isEmpty()) {
                denominacion = null;
            } else if (denominacion.length() > DENOMINACION_MAXIMA) {
                throw new IllegalArgumentException(
                        "La denominacion excede " + DENOMINACION_MAXIMA + " caracteres");
            }
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Una ficha no puede dejar de regir antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
        // El detalle es de un tipo concreto de ficha, y aqui es donde se comprueba. Sin esta
        // linea, una ficha ECONOMICA con grupos de tierra se escribe sin ruido y se descubre al
        // leerla, cuando ya nadie recuerda quien la escribio.
        if (detalle != null && detalle.tipo() != tipo) {
            throw new IllegalArgumentException(
                    "Una ficha "
                            + tipo
                            + " no lleva detalle de "
                            + detalle.tipo()
                            + ": son dos fichas distintas del mismo predio, no una");
        }
    }

    /** La primera version de la ficha de un predio. */
    public static FichaCatastral primera(
            long predioId,
            TipoFicha tipo,
            AreaM2 areaTerreno,
            String uso,
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            Observacion observacion) {
        return new FichaCatastral(
                null,
                predioId,
                tipo,
                1,
                areaTerreno,
                uso,
                null,
                null,
                null,
                null,
                desde,
                null,
                origen,
                documentoOrigen,
                observacion,
                List.of(),
                List.of(),
                null);
    }

    public boolean esNueva() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si rige en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /**
     * La version siguiente: misma ficha, {@code version + 1}, con la observacion que justifica el
     * cambio y <b>una copia de todo lo que colgaba</b>.
     *
     * <p>Copiar es la parte que se olvida. Sin ella, la version anterior se quedaria con sus
     * construcciones, sus actividades o sus grupos de tierra y la nueva naceria vacia, que en la
     * practica seria borrar lo declarado sin que ningun {@code DELETE} apareciera en el diff.
     */
    public FichaCatastral siguienteVersion(
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            Observacion observacion) {
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "Se quiso versionar la version "
                            + version
                            + ", que ya se cerro el "
                            + vigenciaHasta
                            + ". Solo la vigente se versiona; si no, el historial se ramifica");
        }
        return new FichaCatastral(
                null,
                predioId,
                tipo,
                version + 1,
                areaTerreno,
                uso,
                frontis,
                condicionPropiedad,
                tipoEdificacion,
                denominacion,
                desde,
                null,
                origen,
                documentoOrigen,
                observacion,
                construcciones,
                instalaciones,
                detalle);
    }

    /** Cierra la version. Sus datos no se tocan: lo unico que cambia es hasta cuando rigio. */
    public FichaCatastral cerradaEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar una version exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "La version " + version + " ya se cerro el " + vigenciaHasta);
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " una version que empezo a regir el "
                            + vigenciaDesde);
        }
        return copia(fecha, areaTerreno, construcciones, instalaciones, denominacion, detalle);
    }

    public FichaCatastral con(List<Construccion> otrasConstrucciones) {
        return copia(
                vigenciaHasta,
                areaTerreno,
                otrasConstrucciones,
                instalaciones,
                denominacion,
                detalle);
    }

    public FichaCatastral conInstalaciones(List<OtraInstalacion> otras) {
        return copia(vigenciaHasta, areaTerreno, construcciones, otras, denominacion, detalle);
    }

    public FichaCatastral conArea(AreaM2 otraArea) {
        return copia(vigenciaHasta, otraArea, construcciones, instalaciones, denominacion, detalle);
    }

    /**
     * La misma version con otro uso.
     *
     * <p>Existe desde #52: la transferencia de fiscalizacion inscribe el uso <b>observado</b>, que
     * es el otro lado del contraste que la liquidacion guarda —un predio declarado como casa
     * habitacion y hallado como comercio no cambia de area y si de tributacion—. Pasa por el
     * constructor, que es quien valida el largo.
     */
    public FichaCatastral conUso(String otroUso) {
        return new FichaCatastral(
                id,
                predioId,
                tipo,
                version,
                areaTerreno,
                otroUso,
                frontis,
                condicionPropiedad,
                tipoEdificacion,
                denominacion,
                vigenciaDesde,
                vigenciaHasta,
                origen,
                documentoOrigen,
                observacion,
                construcciones,
                instalaciones,
                detalle);
    }

    public FichaCatastral conDenominacion(@Nullable String otra) {
        return copia(vigenciaHasta, areaTerreno, construcciones, instalaciones, otra, detalle);
    }

    /**
     * La misma version con otro detalle. Lo rechaza si el detalle no es del tipo de la ficha: la
     * comprobacion esta en el constructor y esta llamada pasa por el.
     */
    public FichaCatastral conDetalle(@Nullable DetalleDeLaFicha otro) {
        return copia(vigenciaHasta, areaTerreno, construcciones, instalaciones, denominacion, otro);
    }

    /** Lo que no varia en ninguna copia, en un solo sitio. */
    private FichaCatastral copia(
            @Nullable LocalDate hasta,
            AreaM2 area,
            List<Construccion> conConstrucciones,
            List<OtraInstalacion> conInstalaciones,
            @Nullable String conDenominacion,
            @Nullable DetalleDeLaFicha conDetalle) {
        return new FichaCatastral(
                id,
                predioId,
                tipo,
                version,
                area,
                uso,
                frontis,
                condicionPropiedad,
                tipoEdificacion,
                conDenominacion,
                vigenciaDesde,
                hasta,
                origen,
                documentoOrigen,
                observacion,
                conConstrucciones,
                conInstalaciones,
                conDetalle);
    }
}
