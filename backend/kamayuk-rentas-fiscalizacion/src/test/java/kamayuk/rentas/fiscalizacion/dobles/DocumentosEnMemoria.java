package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.documentos.DocumentoEmitido;
import kamayuk.rentas.documentos.DocumentoRepository;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Un {@link DocumentoRepository} en memoria, para probar el transporte HTTP sin base de datos.
 *
 * <p>Lo que <b>no</b> se imita es el disparador {@code documento_inmutable_trg} (V15): aqui {@code
 * registrarReimpresion} se limita a sumar uno, porque el unico que puede demostrar que la base
 * impide cambiar cualquier otra columna es PostgreSQL, y eso ya lo hace {@code
 * EmitirDocumentoTest}.
 */
public final class DocumentosEnMemoria implements DocumentoRepository {

    private final List<DocumentoEmitido> guardados = new ArrayList<>();
    private final Map<String, Long> correlativos = new LinkedHashMap<>();
    private long siguienteId = 1;

    @Override
    public Optional<DocumentoEmitido> porNumero(String tipo, Ejercicio ejercicio, String numero) {
        return guardados.stream()
                .filter(
                        d ->
                                d.tipo().equals(tipo)
                                        && d.ejercicio().equals(ejercicio)
                                        && d.numero().equals(numero))
                .findFirst();
    }

    @Override
    public List<DocumentoEmitido> de(String tipo, String referencia) {
        return guardados.stream()
                .filter(d -> d.tipo().equals(tipo) && d.referencia().equals(referencia))
                .toList();
    }

    @Override
    public DocumentoEmitido insertar(DocumentoEmitido documento) {
        DocumentoEmitido conId =
                new DocumentoEmitido(
                        siguienteId++,
                        documento.tipo(),
                        documento.numero(),
                        documento.ejercicio(),
                        documento.referencia(),
                        documento.datos(),
                        documento.formato(),
                        documento.resumen(),
                        documento.fechaEmision(),
                        documento.reimpresiones(),
                        documento.observacion());
        guardados.add(conId);
        return conId;
    }

    @Override
    public DocumentoEmitido registrarReimpresion(DocumentoEmitido documento) {
        DocumentoEmitido conUnaMas = documento.conUnaReimpresionMas();
        guardados.replaceAll(d -> d.id().equals(documento.id()) ? conUnaMas : d);
        return conUnaMas;
    }

    /** Cuantos documentos hay guardados. Para comprobar que mirar uno no emite otro (#593). */
    public int cuantos() {
        return guardados.size();
    }

    /** Cuantas veces se registro una reimpresion de ese numero. Cero es el original. */
    public int reimpresionesDe(String numero) {
        return guardados.stream()
                .filter(d -> d.numero().equals(numero))
                .mapToInt(DocumentoEmitido::reimpresiones)
                .max()
                .orElseThrow(() -> new IllegalStateException("No hay ningun documento " + numero));
    }

    /**
     * Deja el documento con un resumen que ya no es el de sus bytes.
     *
     * <p>Es lo que pasaria si alguien cambiara el renderizador —una fuente, un margen— entre la
     * emision y la descarga: los datos guardados siguen ahi y ya no dan los mismos bytes. No se
     * puede provocar de otra forma sin tener dos renderizadores distintos en la misma prueba.
     */
    public void corromperElResumenDe(String numero) {
        guardados.replaceAll(
                d ->
                        d.numero().equals(numero)
                                ? new DocumentoEmitido(
                                        d.id(),
                                        d.tipo(),
                                        d.numero(),
                                        d.ejercicio(),
                                        d.referencia(),
                                        d.datos(),
                                        d.formato(),
                                        "0".repeat(64),
                                        d.fechaEmision(),
                                        d.reimpresiones(),
                                        d.observacion())
                                : d);
    }

    @Override
    public long siguienteCorrelativo(String tipo, Ejercicio ejercicio) {
        String llave = tipo + "-" + ejercicio.valor();
        long siguiente = correlativos.getOrDefault(llave, 0L) + 1;
        correlativos.put(llave, siguiente);
        return siguiente;
    }
}
