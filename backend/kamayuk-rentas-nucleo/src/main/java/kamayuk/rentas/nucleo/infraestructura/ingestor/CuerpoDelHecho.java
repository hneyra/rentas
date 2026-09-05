package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee el cuerpo de un hecho de {@code catastro} (C-8).
 *
 * <h2>Un cuerpo que no se puede leer NO se reintenta</h2>
 *
 * <p>Sale como {@link ProyeccionDeCatastro.NoSePuedeAplicar}, que es un fallo permanente: si al
 * emisor le falta un campo o lo escribe con otro tipo, ningun reintento lo va a arreglar y lo unico
 * que consigue es bloquear la cola detras de ese hecho. Es la misma decision que `caja` tomo con
 * {@code Rechazado}, leida desde el receptor.
 *
 * <h2>Los importes y las areas llegan como CADENA y se leen como cadena</h2>
 *
 * <p>Y de ahi van a una columna {@code numeric} sin pasar por ningun {@code double} (regla 1,
 * RNF-055). Leerlos como numero de coma flotante volveria a introducir por la puerta de atras el
 * defecto que {@code Dinero} existe para evitar — y aqui el sitio donde entraria es este, que es el
 * unico que toca los bytes del emisor.
 */
public final class CuerpoDelHecho {

    private final JsonMapper json;

    public CuerpoDelHecho(JsonMapper json) {
        this.json = json;
    }

    /** El predio y las versiones de su ficha. */
    Predio predio(String cuerpo) {
        JsonNode raiz = leer(cuerpo);
        List<Ficha> fichas = new ArrayList<>();
        for (JsonNode ficha : raiz.path("fichas")) {
            fichas.add(
                    new Ficha(
                            exigirLargo(ficha, "fichaId"),
                            exigirTexto(ficha, "tipo"),
                            (int) exigirLargo(ficha, "version"),
                            fecha(exigirTexto(ficha, "vigenciaDesde"), "fichas[].vigenciaDesde"),
                            fechaOpcional(ficha, "vigenciaHasta"),
                            texto(ficha, "areaTerreno"),
                            texto(ficha, "uso")));
        }
        return new Predio(
                exigirLargo(raiz, "predioId"),
                exigirTexto(raiz, "codigoRefCatastral"),
                exigirTexto(raiz, "direccion"),
                texto(raiz, "sectorCodigo"),
                exigirTexto(raiz, "estado"),
                List.copyOf(fichas));
    }

    /** La valuacion sellada de un predio. */
    Valuacion valuacion(String cuerpo) {
        JsonNode raiz = leer(cuerpo);
        return new Valuacion(
                exigirLargo(raiz, "predioId"),
                (int) exigirLargo(raiz, "ejercicio"),
                fecha(exigirTexto(raiz, "fechaDeCorte"), "fechaDeCorte"),
                texto(raiz, "valorTerreno"),
                texto(raiz, "valorConstruccion"),
                texto(raiz, "valorObras"),
                texto(raiz, "valorDelPredio"),
                texto(raiz, "motivo"),
                texto(raiz, "llaveQueFalta"),
                largoOpcional(raiz, "fichaCatastralId"),
                exigirLargo(raiz, "conjuntoId"),
                exigirTexto(raiz, "reglasVersion"),
                raiz.path("reglasAplicadas").asString(""));
    }

    /** El cierre de una corrida. */
    Corrida corrida(String cuerpo) {
        JsonNode raiz = leer(cuerpo);
        return new Corrida(
                exigirLargo(raiz, "corridaId"),
                (int) exigirLargo(raiz, "ejercicio"),
                fecha(exigirTexto(raiz, "fechaDeCorte"), "fechaDeCorte"),
                exigirLargo(raiz, "conjuntoId"),
                exigirTexto(raiz, "reglasVersion"),
                (int) exigirLargo(raiz, "conteo"),
                exigirTexto(raiz, "huella"),
                instante(exigirTexto(raiz, "cerradaEn")));
    }

    // ------------------------------------------------------------------

    record Predio(
            long predioId,
            String codigoRefCatastral,
            String direccion,
            @Nullable String sectorCodigo,
            String estado,
            List<Ficha> fichas) {}

    record Ficha(
            long fichaId,
            String tipo,
            int version,
            LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            @Nullable String areaTerreno,
            @Nullable String uso) {}

    record Valuacion(
            long predioId,
            int ejercicio,
            LocalDate fechaDeCorte,
            @Nullable String valorTerreno,
            @Nullable String valorConstruccion,
            @Nullable String valorObras,
            @Nullable String valorDelPredio,
            @Nullable String motivo,
            @Nullable String llaveQueFalta,
            @Nullable Long fichaCatastralId,
            long conjuntoId,
            String reglasVersion,
            String reglasAplicadas) {}

    record Corrida(
            long corridaId,
            int ejercicio,
            LocalDate fechaDeCorte,
            long conjuntoId,
            String reglasVersion,
            int conteo,
            String huella,
            Instant cerradaEn) {}

    // ------------------------------------------------------------------

    private JsonNode leer(String cuerpo) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException ilegible) {
            throw new ProyeccionDeCatastro.NoSePuedeAplicar(
                    "El cuerpo del hecho no es JSON. Esto no se reintenta: ningun reintento va a"
                            + " cambiar los bytes que el emisor mando",
                    ilegible);
        }
    }

    private static long exigirLargo(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        if (valor.isMissingNode() || valor.isNull() || !valor.isNumber()) {
            throw new ProyeccionDeCatastro.NoSePuedeAplicar(
                    "El cuerpo del hecho no trae el campo numerico «" + campo + "»");
        }
        return valor.asLong();
    }

    private static @Nullable Long largoOpcional(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asLong();
    }

    private static String exigirTexto(JsonNode nodo, String campo) {
        String valor = texto(nodo, campo);
        if (valor == null || valor.isBlank()) {
            throw new ProyeccionDeCatastro.NoSePuedeAplicar(
                    "El cuerpo del hecho no trae el campo «" + campo + "»");
        }
        return valor;
    }

    private static @Nullable String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asString();
    }

    private static LocalDate fecha(String texto, String campo) {
        try {
            return LocalDate.parse(texto);
        } catch (java.time.format.DateTimeParseException malEscrita) {
            throw new ProyeccionDeCatastro.NoSePuedeAplicar(
                    "El campo «" + campo + "» no es una fecha ISO: " + texto, malEscrita);
        }
    }

    private static @Nullable LocalDate fechaOpcional(JsonNode nodo, String campo) {
        String valor = texto(nodo, campo);
        return valor == null || valor.isBlank() ? null : fecha(valor, campo);
    }

    private static Instant instante(String texto) {
        try {
            return Instant.parse(texto);
        } catch (java.time.format.DateTimeParseException malEscrito) {
            throw new ProyeccionDeCatastro.NoSePuedeAplicar(
                    "«cerradaEn» no es un instante ISO: " + texto, malEscrito);
        }
    }
}
