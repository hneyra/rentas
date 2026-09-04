package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.CategoriasConstructivas;
import kamayuk.rentas.catastro.dominio.Colindante;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.EstadoDeConservacion;
import kamayuk.rentas.catastro.dominio.MaterialEstructural;
import kamayuk.rentas.catastro.dominio.Orientacion;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.ParticipacionComun;
import kamayuk.rentas.catastro.dominio.Riego;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Carga <b>lo que hay dentro de la ficha</b>: construcciones por piso, obras complementarias, y el
 * detalle que depende del tipo —actividades economicas, bienes comunes con su reparto, grupos de
 * tierra con sus colindantes—.
 *
 * <p>Es la pieza que le faltaba a {@link ImportarFichas}. Ese inscribe el predio y su primera
 * ficha, pero la inscribe <b>vacia por dentro</b> —{@code List.of(), List.of(), null}—, y sin esto
 * las pantallas de la ficha catastral dibujan sus secciones sin una sola fila: un predio urbano sin
 * nada edificado, uno rural sin sus grupos de tierra, uno de bienes comunes sin su reparto.
 *
 * <h2>Ninguna cifra normativa, y conviene decir por que no la hay</h2>
 *
 * <p>Todo lo que entra aqui son <b>caracteristicas</b> del predio: metros construidos, ano,
 * material, estado de conservacion, categorias constructivas, hectareas, riego. Ninguna es un valor
 * unitario ni un arancel. Lo que sigue bloqueado por D-02a es la <b>valorizacion</b> —multiplicar
 * esas caracteristicas por el cuadro de valores unitarios—, no describirlas: la ficha catastral es
 * la descripcion del predio, y existe con independencia de cuanto valga.
 *
 * <h2>La unidad de carga es el PREDIO, no la fila</h2>
 *
 * <p>Una version de ficha es atomica: sus construcciones, sus instalaciones y su detalle entran en
 * una sola llamada a {@link ActualizarFichaCatastral#actualizar}, porque {@code siguienteVersion}
 * copia de la anterior lo que no se le mande y media version es una ficha que miente. Asi que este
 * importador <b>agrupa por codigo predial</b> —conservando el orden del archivo— y cada grupo es un
 * intento: su propia llamada, su propia transaccion, su propio rechazo. Una fila mala tumba el
 * predio al que pertenece y ninguno mas.
 *
 * <p>De ahi que {@code nuevas} cuente <b>fichas versionadas</b> y no filas leidas, al reves que en
 * los otros importadores: aqui varias filas son una escritura. {@code totalFilas} sigue contando
 * filas, y el numero de linea de un rechazo es el de la <b>primera</b> fila de su grupo, que es
 * donde quien abra el archivo tiene que empezar a mirar.
 *
 * <h2>Versiona, no sobrescribe</h2>
 *
 * <p>Se apoya en {@code actualizar} y no en {@code registrarPrimera}, asi que cada predio acaba con
 * <b>dos</b> versiones de ficha: la que inscribio {@code ImportarFichas} y esta. No es un efecto
 * colateral que haya que disculpar: es la invariante del catastro —una ficha nunca se sobrescribe,
 * se versiona— ejercitada de verdad, y de paso deja historial que mirar en la pantalla que lo lee.
 */
@Service
public class ImportarDetalleDeFichas {

    /** Las cinco columnas de cabecera mas las siete de la seccion. */
    private static final int COLUMNAS = 13;

    /** Donde empiezan las columnas cuyo significado depende de la seccion. */
    private static final int PRIMERA_DE_LA_SECCION = 6;

    private final ActualizarFichaCatastral fichas;
    private final PrediosPorCodigo predios;

    public ImportarDetalleDeFichas(ActualizarFichaCatastral fichas, PrediosPorCodigo predios) {
        this.fichas = fichas;
        this.predios = predios;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int versionadas = 0;

        for (Map.Entry<String, List<FilaCsv>> grupo : agruparPorPredio(filas).entrySet()) {
            List<FilaCsv> delPredio = grupo.getValue();
            int primeraLinea = delPredio.get(0).numeroDeLinea();
            try {
                versionar(grupo.getKey(), delPredio, observacion);
                versionadas++;
            } catch (IllegalArgumentException | ActualizarFichaCatastral.SinFichaVigente e) {
                rechazadas.add(FilaRechazada.de(primeraLinea, e));
            }
        }

        return new InformeDeImportacion(filas.size(), versionadas, rechazadas);
    }

    // ------------------------------------------------------------------

    private void versionar(String codigoPredial, List<FilaCsv> filas, Observacion observacion) {
        Cabecera cabecera = cabeceraComun(filas);
        long predioId =
                predios.identificadorDe(CodigoReferenciaCatastral.de(codigoPredial))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No hay ningun predio con el codigo '"
                                                        + codigoPredial
                                                        + "'"));

        List<Construccion> construcciones = new ArrayList<>();
        List<OtraInstalacion> instalaciones = new ArrayList<>();
        List<ActividadEconomica> actividades = new ArrayList<>();
        List<BienComun> bienes = new ArrayList<>();
        List<ParticipacionComun> participaciones = new ArrayList<>();
        List<TierraRural> tierras = new ArrayList<>();
        List<Colindante> colindantes = new ArrayList<>();

        for (FilaCsv fila : filas) {
            List<String> c = fila.campos();
            switch (seccion(c.get(5))) {
                case CONSTRUCCION -> construcciones.add(construccion(c));
                case INSTALACION -> instalaciones.add(instalacion(c));
                case ACTIVIDAD -> actividades.add(actividad(c));
                case BIEN_COMUN -> bienes.add(bienComun(c));
                case PARTICIPACION -> participaciones.add(participacion(c));
                case TIERRA -> tierras.add(tierra(c));
                case COLINDANTE -> colindantes.add(colindante(c));
                // Inalcanzable: `seccion` ya rechazo cualquier texto que no sea uno de los
                // siete, nombrandolos. Esta rama existe porque Checkstyle exige `default`
                // (MissingSwitchDefault), y lanza en vez de callar para que anadir una
                // seccion al enum y olvidarse de tratarla aqui reviente en la primera fila
                // que la use, en lugar de descartarla en silencio.
                default -> throw new IllegalStateException("Seccion sin tratar: " + c.get(5));
            }
        }

        DetalleDeLaFicha detalle =
                detalleDe(
                        cabecera.tipo(),
                        actividades,
                        bienes,
                        participaciones,
                        tierras,
                        colindantes);

        fichas.actualizar(
                predioId,
                cabecera.tipo(),
                cabecera.desde(),
                cabecera.origen(),
                cabecera.documentoOrigen(),
                construcciones,
                instalaciones,
                detalle,
                observacion);
    }

    /**
     * Lo que un predio declara una sola vez —tipo de ficha, vigencia, origen y documento— y todas
     * sus filas tienen que repetir igual.
     *
     * <p>Se exige que coincidan en vez de tomar la primera y callar: son propiedades de la
     * <b>version</b>, no de la fila, y dos filas del mismo predio que digan fechas distintas no
     * tienen una lectura correcta que elegir.
     */
    private static Cabecera cabeceraComun(List<FilaCsv> filas) {
        Cabecera primera = cabecera(filas.get(0).campos());
        for (FilaCsv fila : filas) {
            if (!cabecera(fila.campos()).equals(primera)) {
                throw new IllegalArgumentException(
                        "Las filas de un mismo predio tienen que declarar el mismo tipo de ficha,"
                                + " la misma vigencia y el mismo documento de origen: la linea "
                                + fila.numeroDeLinea()
                                + " no coincide con la primera de su grupo");
            }
        }
        return primera;
    }

    private record Cabecera(
            TipoFicha tipo, LocalDate desde, OrigenDeLaFicha origen, String documentoOrigen) {}

    private static Cabecera cabecera(List<String> c) {
        return new Cabecera(
                enumerado(TipoFicha.class, c.get(1), "tipo de ficha"),
                fecha(c.get(2)),
                enumerado(OrigenDeLaFicha.class, c.get(3), "origen de la ficha"),
                exigir(c.get(4), "documentoOrigen"));
    }

    /**
     * El detalle que corresponde al tipo, y solo el suyo: una ficha {@code ECONOMICA} con grupos de
     * tierra la rechazaria el constructor de la ficha, pero se rechaza antes y nombrando la seccion
     * sobrante, que es lo que quien edita el archivo necesita leer.
     */
    private static @Nullable DetalleDeLaFicha detalleDe(
            TipoFicha tipo,
            List<ActividadEconomica> actividades,
            List<BienComun> bienes,
            List<ParticipacionComun> participaciones,
            List<TierraRural> tierras,
            List<Colindante> colindantes) {

        return switch (tipo) {
            case UNICA -> {
                exigirVacias("UNICA", actividades, bienes, participaciones, tierras, colindantes);
                yield null;
            }
            case ECONOMICA -> {
                exigirVacias("ECONOMICA", bienes, participaciones, tierras, colindantes);
                yield actividades.isEmpty() ? null : new DetalleEconomico(actividades, null);
            }
            case BIENES_COMUNES -> {
                exigirVacias("BIENES_COMUNES", actividades, tierras, colindantes);
                yield bienes.isEmpty() && participaciones.isEmpty()
                        ? null
                        : new DetalleDeBienesComunes(bienes, participaciones);
            }
            case RURAL -> {
                exigirVacias("RURAL", actividades, bienes, participaciones);
                yield tierras.isEmpty() && colindantes.isEmpty()
                        ? null
                        : new DetalleRural(tierras, colindantes);
            }
        };
    }

    @SafeVarargs
    private static void exigirVacias(String tipo, List<?>... queNoDeberianTraerNada) {
        for (List<?> lista : queNoDeberianTraerNada) {
            if (!lista.isEmpty()) {
                throw new IllegalArgumentException(
                        "Una ficha "
                                + tipo
                                + " no lleva esa seccion: revisa las secciones declaradas para"
                                + " este predio");
            }
        }
    }

    // --- una fila de cada seccion ---------------------------------------

    private enum Seccion {
        CONSTRUCCION,
        INSTALACION,
        ACTIVIDAD,
        BIEN_COMUN,
        PARTICIPACION,
        TIERRA,
        COLINDANTE
    }

    private static Seccion seccion(String texto) {
        return enumerado(Seccion.class, texto, "seccion");
    }

    private static Construccion construccion(List<String> c) {
        return new Construccion(
                null,
                null,
                exigir(campo(c, 0), "piso"),
                AreaM2.de(exigir(campo(c, 1), "area construida")),
                ejercicioOpcional(campo(c, 2)),
                opcionalEnumerado(MaterialEstructural.class, campo(c, 3), "material"),
                opcionalEnumerado(
                        EstadoDeConservacion.class, campo(c, 4), "estado de conservacion"),
                categorias(campo(c, 5)),
                porcentajeOpcional(campo(c, 6)));
    }

    private static OtraInstalacion instalacion(List<String> c) {
        return new OtraInstalacion(
                null,
                null,
                exigir(campo(c, 0), "descripcion"),
                Medida.de(exigir(campo(c, 1), "cantidad"), exigir(campo(c, 2), "unidad")),
                ejercicioOpcional(campo(c, 3)),
                opcionalEnumerado(
                        EstadoDeConservacion.class, campo(c, 4), "estado de conservacion"));
    }

    private static ActividadEconomica actividad(List<String> c) {
        String area = campo(c, 3);
        return new ActividadEconomica(
                null,
                null,
                exigir(campo(c, 0), "conductor"),
                campo(c, 1),
                campo(c, 2),
                area == null ? null : AreaM2.de(area),
                null,
                null,
                null,
                null,
                null);
    }

    private static BienComun bienComun(List<String> c) {
        return new BienComun(
                null,
                null,
                exigir(campo(c, 0), "descripcion"),
                AreaM2.de(exigir(campo(c, 1), "area")),
                opcionalEnumerado(MaterialEstructural.class, campo(c, 2), "material"),
                opcionalEnumerado(
                        EstadoDeConservacion.class, campo(c, 3), "estado de conservacion"),
                ejercicioOpcional(campo(c, 4)));
    }

    /**
     * La participacion nombra al predio participe <b>por su codigo catastral</b>, no por su
     * identificador: el archivo no puede conocer un identificador que asigna la base.
     */
    private ParticipacionComun participacion(List<String> c) {
        String codigo = exigir(campo(c, 0), "codigo predial del participe");
        long participeId =
                predios.identificadorDe(CodigoReferenciaCatastral.de(codigo))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "La participacion nombra el predio '"
                                                        + codigo
                                                        + "', que no esta inscrito"));
        return new ParticipacionComun(
                null, null, participeId, Porcentaje.de(exigir(campo(c, 1), "porcentaje")));
    }

    private static TierraRural tierra(List<String> c) {
        String comunes = campo(c, 4);
        return new TierraRural(
                null,
                null,
                exigir(campo(c, 0), "clasificacion"),
                campo(c, 1),
                enumerado(Riego.class, exigir(campo(c, 2), "riego"), "riego"),
                Medida.de(exigir(campo(c, 3), "hectareas"), "HA"),
                comunes == null ? null : Medida.de(comunes, "HA"));
    }

    private static Colindante colindante(List<String> c) {
        return new Colindante(
                null,
                null,
                enumerado(Orientacion.class, exigir(campo(c, 0), "orientacion"), "orientacion"),
                exigir(campo(c, 1), "descripcion"));
    }

    // --- analisis ------------------------------------------------------

    private static Map<String, List<FilaCsv>> agruparPorPredio(List<FilaCsv> filas) {
        Map<String, List<FilaCsv>> porPredio = new LinkedHashMap<>();
        for (FilaCsv fila : filas) {
            if (fila.campos().size() < COLUMNAS) {
                // Una fila corta no se puede ni asignar a un predio: se agrupa por lo que
                // traiga en la primera columna, y su grupo entero se rechaza al leer la
                // cabecera. Descartarla en silencio seria perder la fila y el aviso.
                porPredio
                        .computeIfAbsent(fila.campos().get(0), llave -> new ArrayList<>())
                        .add(fila);
                continue;
            }
            porPredio
                    .computeIfAbsent(fila.campos().get(0).strip(), llave -> new ArrayList<>())
                    .add(fila);
        }
        return porPredio;
    }

    /** La columna {@code n} de la seccion, o {@code null} si viene vacia. */
    private static @Nullable String campo(List<String> campos, int n) {
        if (campos.size() < COLUMNAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS
                            + ": codigoPredial, tipoFicha, vigenciaDesde, origen, documentoOrigen,"
                            + " seccion y las siete de la seccion");
        }
        String valor = campos.get(PRIMERA_DE_LA_SECCION + n).strip();
        return valor.isEmpty() ? null : valor;
    }

    private static String exigir(@Nullable String valor, String queEs) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Falta el campo obligatorio '" + queEs + "'");
        }
        return valor.strip();
    }

    private static <E extends Enum<E>> E enumerado(Class<E> tipo, String texto, String queEs) {
        try {
            return Enum.valueOf(tipo, texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new IllegalArgumentException(
                    "El "
                            + queEs
                            + " no es uno de los admitidos: '"
                            + texto
                            + "'. Los validos son "
                            + java.util.Arrays.toString(tipo.getEnumConstants()),
                    desconocido);
        }
    }

    private static <E extends Enum<E>> @Nullable E opcionalEnumerado(
            Class<E> tipo, @Nullable String texto, String queEs) {
        return texto == null ? null : enumerado(tipo, texto, queEs);
    }

    private static @Nullable Ejercicio ejercicioOpcional(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return new Ejercicio(Integer.parseInt(texto));
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El anio no es un anio: '" + texto + "'", noEsNumero);
        }
    }

    private static @Nullable Porcentaje porcentajeOpcional(@Nullable String texto) {
        return texto == null ? null : Porcentaje.de(texto);
    }

    /**
     * Las siete categorias constructivas en una sola columna, como las escribe el manual: siete
     * letras de la A a la I en el orden muros, techos, pisos, puertas, revestimientos, banios,
     * instalaciones. Un guion en una posicion es «esa no se declara».
     */
    private static CategoriasConstructivas categorias(@Nullable String texto) {
        if (texto == null) {
            return CategoriasConstructivas.ninguna();
        }
        String letras = texto.strip().toUpperCase(Locale.ROOT);
        if (letras.length() != 7) {
            throw new IllegalArgumentException(
                    "Las categorias constructivas van en siete posiciones —muros, techos, pisos,"
                            + " puertas, revestimientos, banios, instalaciones—, con un guion en la"
                            + " que no se declare: '"
                            + texto
                            + "'");
        }
        return new CategoriasConstructivas(
                letra(letras, 0),
                letra(letras, 1),
                letra(letras, 2),
                letra(letras, 3),
                letra(letras, 4),
                letra(letras, 5),
                letra(letras, 6));
    }

    private static @Nullable Character letra(String letras, int posicion) {
        char letra = letras.charAt(posicion);
        return letra == '-' ? null : letra;
    }

    private static LocalDate fecha(String texto) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new IllegalArgumentException(
                    "La fecha va en formato AAAA-MM-DD: '" + texto + "'", malFormada);
        }
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de detalle de fichas", e);
        }
    }
}
