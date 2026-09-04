package kamayuk.rentas.documentos;

import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emitir un documento, y volver a imprimirlo identico anos despues (RF-132).
 *
 * <h2>Por que reimprimir no es volver a generar</h2>
 *
 * <p>La deuda de 2027 recalculada en 2037 no da la misma cifra: los parametros cambiaron, la ficha
 * del predio se versiono, el contribuyente se mudo. Y el contribuyente tiene el papel de 2027 en la
 * mano. Asi que el documento <b>guarda los datos con que se dibujo</b>, y reimprimir es volver a
 * dibujarlos.
 *
 * <p>Que salga identico no se afirma: se comprueba. La emision guarda el resumen SHA-256 de los
 * bytes, y {@link #reimprimir} vuelve a calcularlo. Si no coinciden —porque alguien cambio el
 * renderizador— la reimpresion <b>falla</b> en vez de entregar un papel distinto al original con el
 * mismo numero.
 */
@Service
public class EmitirDocumento {

    private final DocumentoRepository repositorio;
    private final GeneradorDeDocumentos generador;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirDocumento(
            DocumentoRepository repositorio,
            GeneradorDeDocumentos generador,
            Auditoria auditoria,
            Clock reloj) {
        this.repositorio = repositorio;
        this.generador = generador;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite el documento y lo deja registrado con sus datos.
     *
     * @return los bytes que se entregan, y el registro con su numero
     */
    @Transactional
    public Emision emitir(
            String tipo,
            Ejercicio ejercicio,
            String referencia,
            ModeloDeDocumento modelo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        String numero =
                String.format(
                        "%s-%d-%06d",
                        tipo, ejercicio.valor(), repositorio.siguienteCorrelativo(tipo, ejercicio));

        // Se marca ANTES de guardar, no solo al dibujar. Un documento emitido por una
        // instalacion de demostracion nace marcado y se queda marcado: lo que se guarda
        // es el modelo con la marca dentro, asi que reimprimirlo dentro de diez anos
        // —y con la instalacion ya en produccion— sigue dando el mismo papel y el mismo
        // resumen. Marcar solo al dibujar habria hecho que la marca de un papel ya
        // emitido dependiera del regimen del dia en que alguien pide el duplicado.
        ModeloDeDocumento marcado = generador.marcar(modelo);
        byte[] documento = generador.generarFirmado(marcado, formato);

        DocumentoEmitido registrado =
                repositorio.insertar(
                        new DocumentoEmitido(
                                null,
                                tipo,
                                numero,
                                ejercicio,
                                referencia,
                                marcado,
                                formato,
                                GeneradorDeDocumentos.resumenDe(documento),
                                LocalDate.now(reloj),
                                0,
                                observacion));

        auditar(registrado, Operacion.ALTA, observacion);
        return new Emision(registrado, documento);
    }

    /**
     * Vuelve a sacar un documento ya emitido, marcado como duplicado.
     *
     * <p>El duplicado sale <b>marcado</b>. Uno sin marcar circula como si fuera el original, y en
     * un expediente coactivo eso es un documento de mas.
     *
     * <p>Y sale <b>en el formato que se pida</b>, no necesariamente en el que se emitio: quien
     * recibio un PDF tiene derecho a pedir la misma emision en hoja de calculo. Guardar el archivo
     * en vez de los datos habria hecho eso imposible.
     */
    @Transactional
    public Emision reimprimir(
            String tipo,
            Ejercicio ejercicio,
            String numero,
            FormatoDeDocumento formato,
            Observacion observacion) {

        DocumentoEmitido original =
                repositorio
                        .porNumero(tipo, ejercicio, numero)
                        .orElseThrow(() -> new DocumentoNoEmitido(tipo, ejercicio, numero));

        exigirQueSalgaIgual(original);

        DocumentoEmitido conDuplicadoMas = repositorio.registrarReimpresion(original);
        byte[] documento =
                generador.generarFirmado(
                        original.datos().comoDuplicado(conDuplicadoMas.reimpresiones()), formato);

        auditar(conDuplicadoMas, Operacion.MODIFICACION, observacion);
        return new Emision(conDuplicadoMas, documento);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentoEmitido> buscar(String tipo, Ejercicio ejercicio, String numero) {
        return repositorio.porNumero(tipo, ejercicio, numero);
    }

    /**
     * Vuelve a dibujar un documento ya emitido, <b>sin registrar nada y sin marcarlo</b>.
     *
     * <h2>Por que existe, si ya esta {@link #reimprimir}</h2>
     *
     * <p>Porque no todo documento emitido <b>entrego</b> sus bytes. El recibo de caja si: la
     * ventanilla se lleva el papel en la cobranza, asi que sacarlo otra vez es un duplicado y tiene
     * que salir marcado y contado. La resolucion de determinacion de fiscalizacion no: {@code
     * TransferirARentas} la emite, la numera, guarda su modelo y su resumen, y los bytes se
     * <b>descartan</b> —{@code POST /fiscalizacion/transferencias} devuelve JSON—. La primera vez
     * que ese papel sale del sistema es por su pantalla de consulta, y entregarlo diciendo
     * «DUPLICADO N° 1» seria falso.
     *
     * <p>Asi que la eleccion no es de gusto: <b>si la emision entrego el papel, se reimprime; si no
     * lo entrego, se copia</b>. Y copiar no escribe, de modo que no pide observacion (regla 10) ni
     * gasta un correlativo: el numero es el que la emision ya puso.
     *
     * <p>Lo que si comparte con {@link #reimprimir} es la comprobacion que hace verdadera la
     * promesa: se exige que dibujar los datos guardados siga dando los mismos bytes. Sin ella, un
     * cambio del renderizador haria que el mismo numero identificara dos papeles distintos, y no lo
     * notaria nadie.
     *
     * @return los bytes y el registro que los respalda, o vacio si nunca se emitio ese documento
     */
    @Transactional(readOnly = true)
    public Optional<Emision> copia(
            String tipo, Ejercicio ejercicio, String numero, FormatoDeDocumento formato) {
        return repositorio
                .porNumero(tipo, ejercicio, numero)
                .map(
                        original -> {
                            exigirQueSalgaIgual(original);
                            return new Emision(
                                    original, generador.generarFirmado(original.datos(), formato));
                        });
    }

    /**
     * Emision masiva: escribe cada documento en el flujo que devuelva {@code destino} y lo olvida.
     *
     * <p>Recibe un {@link Iterator} y no una {@code List} a proposito. Con una lista, emitir el
     * padron entero significa tenerlo entero en memoria antes de escribir el primer documento; con
     * un iterador sobre un cursor, la memoria no depende de cuantos sean.
     *
     * <p><b>Es reanudable sin ninguna tabla de progreso</b>: el propio registro de documentos
     * emitidos dice cuales ya salieron. Quien reanude filtra por lo que no esta.
     *
     * @param destino de donde sacar el flujo de cada documento; el llamador lo abre y lo cierra
     * @return cuantos se escribieron
     */
    public long emitirEnLote(
            Iterator<ModeloDeDocumento> modelos,
            FormatoDeDocumento formato,
            java.util.function.Function<ModeloDeDocumento, OutputStream> destino) {

        long escritos = 0;
        while (modelos.hasNext()) {
            ModeloDeDocumento modelo = modelos.next();
            generador.escribir(modelo, formato, destino.apply(modelo));
            escritos++;
        }
        return escritos;
    }

    /**
     * Comprueba que dibujar los datos guardados sigue dando los mismos bytes.
     *
     * <p>Es la unica manera de que «reimprimir devuelve exactamente el original» sea una afirmacion
     * comprobable y no una intencion. Si alguien cambia el renderizador —una fuente, un margen—,
     * esto salta en la primera reimpresion en vez de entregar en silencio un papel distinto al
     * original con el mismo numero.
     */
    private void exigirQueSalgaIgual(DocumentoEmitido original) {
        String ahora = generador.resumenDe(original.datos(), original.formato());
        if (!ahora.equals(original.resumen())) {
            throw new LaReimpresionNoCoincide(original, ahora);
        }
    }

    private void auditar(DocumentoEmitido documento, Operacion operacion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "documento_emitido",
                                String.valueOf(documento.id()),
                                operacion,
                                observacion)
                        .con(null, descripcion(documento)));
    }

    private static String descripcion(DocumentoEmitido documento) {
        return "{\"tipo\":\""
                + documento.tipo()
                + "\",\"numero\":\""
                + documento.numero()
                + "\",\"formato\":\""
                + documento.formato()
                + "\",\"reimpresiones\":"
                + documento.reimpresiones()
                + "}";
    }

    /** Los bytes que se entregan y el registro que los respalda. */
    public record Emision(DocumentoEmitido registro, byte[] contenido) {

        public String nombreDeArchivo() {
            return registro.formato().nombreDeArchivo(registro.numero());
        }
    }

    /** Se pidio reimprimir algo que nunca se emitio. */
    public static final class DocumentoNoEmitido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DocumentoNoEmitido(String tipo, Ejercicio ejercicio, String numero) {
            super(
                    "No hay ningun "
                            + tipo
                            + " numero "
                            + numero
                            + " del ejercicio "
                            + ejercicio.valor()
                            + "; reimprimir es volver a sacar lo emitido, no emitir");
        }
    }

    /** Dibujar los datos guardados ya no da los mismos bytes. */
    public static final class LaReimpresionNoCoincide extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LaReimpresionNoCoincide(DocumentoEmitido original, String resumenDeAhora) {
            super(
                    "El "
                            + original.tipo()
                            + " "
                            + original.numero()
                            + " ya no se dibuja igual que cuando se emitio: el resumen era "
                            + original.resumen().substring(0, 12)
                            + "… y ahora es "
                            + resumenDeAhora.substring(0, 12)
                            + "…. Entregar esto seria dar un papel distinto al original con el"
                            + " mismo numero");
        }
    }
}
