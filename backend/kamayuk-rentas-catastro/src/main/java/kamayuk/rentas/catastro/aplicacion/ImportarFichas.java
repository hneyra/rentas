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
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.ComposicionCatastral;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga de predios fichados desde un archivo: una fila es <b>un predio, su primera ficha y su
 * titular</b>, que es exactamente el acto que {@link InscribirFicha} hace atomico.
 *
 * <h2>El codigo de referencia catastral se compone, no se copia</h2>
 *
 * <p>Las primeras columnas del archivo son los <b>tramos</b> del codigo, en el orden que declara la
 * composicion vigente ({@link ComposicionCatastral#DEL_MANUAL} mientras D-10 siga abierta), y el
 * codigo se arma con {@link CodigoReferenciaCatastral#componer}. Ni este importador ni el archivo
 * escriben en ningun sitio cuantos digitos ocupa un tramo: cuantas columnas se leen sale de {@code
 * composicion.tramos().size()}, y el relleno con ceros lo pone el dominio.
 *
 * <p>La alternativa —una columna con el codigo ya escrito, de 23 digitos— convierte cada archivo de
 * ejemplo en una copia de la plantilla del manual. El dia que D-10 se cierre en las 21 posiciones
 * del prototipo, esos archivos se rechazan enteros y hay que reescribirlos a mano uno a uno.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Mismo patron que {@link ImportarVias}: {@code importar} <b>no</b> lleva
 * {@code @Transactional}, para que cada fila abra la suya al llamar a {@link
 * InscribirFicha#inscribir}, que si la lleva y es un {@code @Service} distinto de este. Una fila
 * que nombra un sector inexistente, o que repite un codigo de predio ya fichado, se rechaza sola:
 * las siguientes entran.
 *
 * <p>Dentro de una fila no hay medias tintas: {@link InscribirFicha} escribe el predio, la ficha y
 * la titularidad en una sola transaccion, asi que una fila con un contribuyente que no existe no
 * deja el predio suelto sin ficha.
 */
@Service
public class ImportarFichas {

    /** Lo que va detras de los tramos del codigo: del tipo de predio al documento del titular. */
    private static final int COLUMNAS_DE_LA_FICHA = 15;

    private final InscribirFicha inscribir;

    public ImportarFichas(InscribirFicha inscribir) {
        this.inscribir = inscribir;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        return importar(archivo, ComposicionCatastral.DEL_MANUAL, observacion);
    }

    /**
     * @param composicion la vigente; se recibe para que cerrar D-10 sea pasar otra, y no tocar este
     *     codigo (ver {@link ComposicionCatastral})
     */
    public InformeDeImportacion importar(
            Reader archivo, ComposicionCatastral composicion, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            Fila leida;
            try {
                leida = parsear(fila.campos(), composicion);
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                inscribir.inscribir(leida.predio(), leida.ficha(), leida.titular(), observacion);
                nuevas++;
            } catch (IllegalArgumentException
                    | InscribirFicha.ReferenciaInexistente
                    | InscribirFicha.PredioDadoDeBaja
                    | ActualizarFichaCatastral.YaTieneFicha e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "El predio '"
                                        + leida.predio().codigo().valor()
                                        + "' ya tiene una ficha "
                                        + leida.ficha().tipo()
                                        + " vigente"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    // ------------------------------------------------------------------

    private record Fila(
            InscribirFicha.DatosDelPredio predio,
            InscribirFicha.DatosDeLaFicha ficha,
            InscribirFicha.@Nullable DatosDelTitular titular) {}

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de fichas", e);
        }
    }

    private static Fila parsear(List<String> campos, ComposicionCatastral composicion) {
        int tramos = composicion.tramos().size();
        int minimas = tramos + COLUMNAS_DE_LA_FICHA;
        if (campos.size() < minimas) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + minimas
                            + ": los "
                            + tramos
                            + " tramos del codigo de referencia catastral y despues tipoPredio,"
                            + " direccion, codigoVia, numeroMunicipal, tipoFicha, areaTerreno, uso,"
                            + " denominacion, vigenciaDesde, origen, documentoOrigen,"
                            + " codigoContribuyente, condicionTitular, porcentaje,"
                            + " documentoTitular");
        }

        Map<String, String> porTramo = new LinkedHashMap<>();
        for (int i = 0; i < tramos; i++) {
            String valor = campos.get(i).strip();
            if (!valor.isEmpty()) {
                porTramo.put(composicion.tramos().get(i).nombre(), valor);
            }
        }
        CodigoReferenciaCatastral codigo =
                CodigoReferenciaCatastral.componer(porTramo, composicion);

        int c = tramos;
        TipoPredio tipoPredio = enumerado(TipoPredio.class, campos.get(c++), "tipo de predio");
        String direccion = campos.get(c++);
        String codigoVia = opcional(campos, c++);
        String numeroMunicipal = opcional(campos, c++);
        TipoFicha tipoFicha = enumerado(TipoFicha.class, campos.get(c++), "tipo de ficha");
        AreaM2 area = area(campos.get(c++));
        String uso = campos.get(c++);
        String denominacion = opcional(campos, c++);
        LocalDate desde = fecha(campos.get(c++));
        OrigenDeLaFicha origen =
                enumerado(OrigenDeLaFicha.class, campos.get(c++), "origen de la ficha");
        String documentoOrigen = campos.get(c++);
        String codigoContribuyente = opcional(campos, c++);
        String condicionTexto = opcional(campos, c++);
        String porcentajeTexto = opcional(campos, c++);
        String documentoTitular = opcional(campos, c);

        InscribirFicha.DatosDelPredio predio =
                new InscribirFicha.DatosDelPredio(
                        codigo,
                        tipoPredio,
                        direccion,
                        codigoVia,
                        numeroMunicipal,
                        porTramo.get("sector"),
                        porTramo.get("manzana"),
                        porTramo.get("lote"),
                        codigo.ubigeo());

        InscribirFicha.DatosDeLaFicha ficha =
                new InscribirFicha.DatosDeLaFicha(
                        tipoFicha,
                        area,
                        uso,
                        denominacion,
                        desde,
                        origen,
                        documentoOrigen,
                        List.of(),
                        List.of(),
                        null);

        return new Fila(
                predio,
                ficha,
                titular(codigoContribuyente, condicionTexto, porcentajeTexto, documentoTitular));
    }

    private static InscribirFicha.@Nullable DatosDelTitular titular(
            @Nullable String codigoContribuyente,
            @Nullable String condicionTexto,
            @Nullable String porcentajeTexto,
            @Nullable String documentoTitular) {
        if (codigoContribuyente == null) {
            // Un predio fichado antes de identificar a su propietario es lo normal en un
            // levantamiento catastral, y InscribirFicha lo admite.
            return null;
        }
        if (condicionTexto == null || documentoTitular == null) {
            throw new IllegalArgumentException(
                    "Un titular necesita su condicion y el documento que la sustenta");
        }
        CondicionDeTitularidad condicion =
                enumerado(CondicionDeTitularidad.class, condicionTexto, "condicion de titularidad");
        Porcentaje porcentaje = porcentajeTexto == null ? null : porcentaje(porcentajeTexto);
        return new InscribirFicha.DatosDelTitular(
                codigoContribuyente, condicion, porcentaje, documentoTitular);
    }

    private static @Nullable String opcional(List<String> campos, int posicion) {
        String valor = campos.get(posicion).strip();
        return valor.isEmpty() ? null : valor;
    }

    private static AreaM2 area(String texto) {
        try {
            return AreaM2.de(texto.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El area de terreno se escribe en metros cuadrados, con punto decimal: '"
                            + texto
                            + "'");
        }
    }

    private static Porcentaje porcentaje(String texto) {
        try {
            return Porcentaje.de(texto);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El porcentaje de propiedad se escribe con punto decimal: '" + texto + "'");
        }
    }

    private static LocalDate fecha(String texto) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "La fecha de vigencia se escribe aaaa-mm-dd: '" + texto + "'");
        }
    }

    private static <E extends Enum<E>> E enumerado(Class<E> tipo, String texto, String que) {
        try {
            return Enum.valueOf(tipo, texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "El "
                            + que
                            + " '"
                            + texto
                            + "' no existe. Los validos son "
                            + java.util.Arrays.toString(tipo.getEnumConstants()));
        }
    }
}
