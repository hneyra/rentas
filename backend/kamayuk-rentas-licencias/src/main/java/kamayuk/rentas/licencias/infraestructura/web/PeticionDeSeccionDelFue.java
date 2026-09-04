package kamayuk.rentas.licencias.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla manda para completar <b>una</b> seccion del FUE (#48 AC 1, RF-113).
 *
 * <p>Una sola peticion para las cinco secciones, discriminada por {@code seccion}. La alternativa
 * —cinco rutas— seria cinco operaciones del contrato para una sola opcion del menu que la pantalla
 * dibuja como pestanas del mismo formulario.
 *
 * <p><b>Lo que no corresponde a la seccion se ignora</b>, y el controlador lo dice: mandar la
 * valorizacion dentro de la peticion del terreno seria una peticion que parece guardar dos cosas y
 * guarda una.
 *
 * @param seccion TERRENO, PROYECTO, VALORIZACION, PROFESIONALES o DOCUMENTOS
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeSeccionDelFue(
        @Nullable String seccion,
        @Nullable String codCatastral,
        @Nullable String direccion,
        @Nullable String mz,
        @Nullable String lt,
        @Nullable String areaDelTerrenoM,
        @Nullable String zonificacion,
        @Nullable String partidaRegistral,
        @Nullable String frenteM,
        @Nullable String fondoM,
        @Nullable String usoDeLaEdificacion,
        @Nullable Integer nDePisos,
        @Nullable String areaTechadaTotalM,
        @Nullable String areaLibreM,
        @Nullable Integer nDeEstacionamientos,
        @Nullable Integer plazoDeEjecucionMeses,
        @Nullable List<LineaDeValorizacion> valorizacion,
        @Nullable List<ProfesionalDeclarado> profesionales,
        @Nullable List<DocumentoDeclarado> documentos,
        @Nullable String observacion) {

    /**
     * Una linea de la valorizacion por pisos y estructuras.
     *
     * <p><b>Sin importe, y no se admite ninguno.</b> El valor por metro cuadrado sale del cuadro de
     * #17; aceptarlo del cliente dejaria que quien teclea eligiera cuanto vale la obra (AC 2).
     */
    public record LineaDeValorizacion(
            @Nullable Integer piso,
            @Nullable String partida,
            @Nullable String categoria,
            @Nullable String areaM) {}

    /** Un proyectista o el responsable de obra. */
    public record ProfesionalDeclarado(
            @Nullable String tipo,
            @Nullable String nombre,
            @Nullable String colegio,
            @Nullable String colegiatura) {}

    /** Un documento adjunto, con el nombre que el TUPA le da. */
    public record DocumentoDeclarado(
            @Nullable String requisito, @Nullable Boolean presentado, @Nullable Integer folios) {}
}
