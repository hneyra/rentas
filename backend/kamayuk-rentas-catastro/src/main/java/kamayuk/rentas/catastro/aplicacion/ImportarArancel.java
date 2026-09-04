package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Carga masiva de aranceles de terreno por via, desde un archivo (misma practica que {@link
 * ImportarVias}, #121): una fila por arancel, columnas {@code
 * viaCodigo,tramo,valorM2,documentoFuente} —{@code tramo} admite quedar vacio, para la via que
 * tiene un solo arancel a lo largo de toda su extension—.
 *
 * <p>Pensado para el archivo {@code arancel_<ejercicio>.csv} que produce {@code
 * scripts/valores-normativos/importar_arancel_via_gpkg.py} a partir del plano grafico de aranceles
 * del MEF (docs/10-negocio/valores-normativos/aranceles-2026.md S1.3): esa misma practica —extraer
 * del gpkg, revisar el resumen, cargar este archivo— es la que se repite con el gpkg de cada
 * municipalidad nueva.
 *
 * <p>Carga <b>contra un conjunto de parametros que el llamador ya resolvio</b> (ver {@link
 * TablasDeValuacion}): abrir la version y sellarla es decision de {@code AdministrarParametros}, no
 * de este importador, igual que {@link TablasDeValuacion#cargarArancel} ya documenta para la carga
 * de una sola fila.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Cada fila pasa por {@link RegistrarArancel#registrar}, un {@code @Service} <b>distinto</b> de
 * este con su propio {@code @Transactional}: este metodo no lleva la anotacion, asi que cada
 * llamada abre su propia transaccion en vez de heredar una que ya existiera. Si una fila viola la
 * restriccion de unicidad —o el conjunto ya esta sellado, o la via no existe—, esa transaccion se
 * aborta y ninguna otra: las filas siguientes abren la suya y entran con normalidad. Es la misma
 * propiedad, y la misma razon, que documenta {@link ImportarVias}.
 */
@Service
public class ImportarArancel {

    private static final int COLUMNAS = 4;

    private final RegistrarArancel registrarArancel;

    public ImportarArancel(RegistrarArancel registrarArancel) {
        this.registrarArancel = registrarArancel;
    }

    public InformeDeImportacion importar(
            Reader archivo, IdentificadorDeConjunto conjunto, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            FilaParseada parseada;
            try {
                parseada = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrarArancel.registrar(
                        parseada.codigoVia(),
                        parseada.tramo(),
                        parseada.valorM2(),
                        parseada.documentoFuente(),
                        conjunto,
                        observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DuplicateKeyException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya existe un arancel para la via '"
                                        + parseada.codigoVia()
                                        + "'"
                                        + (parseada.tramo() == null
                                                ? ""
                                                : " y el tramo '" + parseada.tramo() + "'")
                                        + " en este conjunto"));
            } catch (DataAccessException e) {
                // La causa mas probable, sin repetir el mensaje crudo de la base (ARQ-04 S5):
                // el disparador de V18 rechazando la escritura porque el conjunto ya esta
                // sellado. No es una violacion de unicidad —esa se distingue arriba—.
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "No se pudo cargar el arancel de la via '"
                                        + parseada.codigoVia()
                                        + "': el conjunto de parametros esta sellado"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de aranceles", e);
        }
    }

    private static FilaParseada parsear(List<String> campos) {
        if (campos.size() < COLUMNAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS
                            + ": viaCodigo, tramo, valorM2, documentoFuente");
        }
        String codigoVia = campos.get(0);
        if (codigoVia.isBlank()) {
            throw new IllegalArgumentException("El arancel necesita el codigo de la via");
        }
        String tramo = campos.get(1).isBlank() ? null : campos.get(1);
        String valorTexto = campos.get(2);
        String documentoFuente = campos.get(3);

        ValorNormativo valorM2;
        try {
            valorM2 = ValorNormativo.de(valorTexto);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El valor por m2 no es un numero: '" + valorTexto + "'");
        }

        return new FilaParseada(codigoVia, tramo, valorM2, documentoFuente);
    }

    private record FilaParseada(
            String codigoVia,
            @Nullable String tramo,
            ValorNormativo valorM2,
            String documentoFuente) {}
}
