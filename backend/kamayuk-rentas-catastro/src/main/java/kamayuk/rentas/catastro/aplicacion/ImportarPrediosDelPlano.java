package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.ComposicionCatastral;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga de lotes desde el plano: una fila es <b>un predio y su poligono</b>, sin ficha (ADR-0021).
 *
 * <p>Es el camino por el que una municipalidad real puebla su catastro. Hasta #400 no existia:
 * {@link ImportarFichas} solo era alcanzable desde {@link CargarFichasDeDemostracion}, que exige
 * {@code municipalidad.es_demostracion = true} y por tanto no escribe nada en una instalacion de
 * verdad. Es el mismo hueco que #430 encontro para {@code area} y {@code caja}: el caso de uso
 * estaba y no habia por donde llamarlo.
 *
 * <h2>El codigo se compone, no se copia</h2>
 *
 * <p>Las primeras columnas son los <b>tramos</b> del codigo en el orden de la composicion vigente,
 * igual que en {@link ImportarFichas} y por lo mismo: una columna con los 23 digitos ya escritos
 * convierte cada archivo en una copia de la plantilla del manual, y el dia que D-10 se cierre en
 * otro largo hay que reescribirlos todos a mano.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>{@code importar} <b>no</b> lleva {@code @Transactional}: cada fila abre la suya al llamar a
 * {@link InscribirPredioDelPlano#inscribir}, que si la lleva y es un {@code @Service} distinto. Un
 * lote que nombra un sector inexistente se rechaza solo y los siguientes entran. Envolver el bucle
 * haria algo peor que perder la fila que sigue a la mala: la fila rechazada marca la transaccion
 * como <i>rollback-only</i> y la corrida entera revienta con {@code UnexpectedRollbackException},
 * sin llegar a devolver el informe que la explicaba (#247 §2).
 */
@Service
public class ImportarPrediosDelPlano {

    /** Lo que va detras de los tramos del codigo. */
    private static final int COLUMNAS_DEL_LOTE = 8;

    private final InscribirPredioDelPlano inscribir;

    public ImportarPrediosDelPlano(InscribirPredioDelPlano inscribir) {
        this.inscribir = inscribir;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        return importar(archivo, ComposicionCatastral.DEL_MANUAL, observacion);
    }

    /**
     * @param composicion la vigente; se recibe para que cerrar D-10 sea pasar otra, y no tocar este
     *     codigo
     */
    public InformeDeImportacion importar(
            Reader archivo, ComposicionCatastral composicion, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevos = 0;

        for (FilaCsv fila : filas) {
            InscribirPredioDelPlano.DatosDelLote lote;
            try {
                lote = parsear(fila.campos(), composicion);
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                if (inscribir.inscribir(lote, observacion)) {
                    nuevos++;
                }
            } catch (IllegalArgumentException
                    | InscribirFicha.ReferenciaInexistente
                    | InscribirFicha.PredioDadoDeBaja e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                // Ni tabla, ni restriccion, ni SQL: lo que puede fallar aqui es la geometria que
                // el motor rechaza por su tipo, y el archivo es lo que hay que arreglar.
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "El motor rechazo el lote '"
                                        + lote.codigo().valor()
                                        + "': revise que la geometria sea un MULTIPOLYGON valido"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevos, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de lotes", e);
        }
    }

    private static InscribirPredioDelPlano.DatosDelLote parsear(
            List<String> campos, ComposicionCatastral composicion) {
        int tramos = composicion.tramos().size();
        int minimas = tramos + COLUMNAS_DEL_LOTE;
        if (campos.size() < minimas) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + minimas
                            + ": los "
                            + tramos
                            + " tramos del codigo de referencia catastral y despues tipoPredio,"
                            + " direccion, codigoVia, numeroMunicipal, codigoSector,"
                            + " codigoManzana, lote, geometria");
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
        TipoPredio tipo = tipoDe(campos.get(c++));
        String direccion = campos.get(c++);
        String codigoVia = opcional(campos, c++);
        String numeroMunicipal = opcional(campos, c++);
        String codigoSector = opcional(campos, c++);
        String codigoManzana = opcional(campos, c++);
        String lote = opcional(campos, c++);
        String geometria = campos.get(c);

        return new InscribirPredioDelPlano.DatosDelLote(
                codigo,
                tipo,
                direccion,
                codigoVia,
                numeroMunicipal,
                codigoSector,
                codigoManzana,
                lote,
                // El ubigeo lo declara el propio codigo en sus tres primeros tramos: pedirlo otra
                // vez en el archivo seria dejar que las dos copias se contradigan.
                codigo.ubigeo(),
                geometria);
    }

    private static TipoPredio tipoDe(String texto) {
        try {
            return TipoPredio.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo de predio desconocido: '"
                            + texto
                            + "'. Los admitidos son URBANO y RUSTICO");
        }
    }

    private static @Nullable String opcional(List<String> campos, int indice) {
        String valor = campos.get(indice).strip();
        return valor.isEmpty() ? null : valor;
    }
}
