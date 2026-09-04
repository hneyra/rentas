package kamayuk.rentas.documentos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Convierte un modelo en el documento del formato que se pida.
 *
 * <p>Un solo sitio elige el renderizador, para que ningun caso de uso conozca ninguno. Anadir un
 * formato es anadir un {@link Renderizador} al contexto de Spring; no hay que tocar esta clase ni
 * ninguna de las que generan documentos.
 *
 * <p><b>Ninguna dependencia externa.</b> RTF y SpreadsheetML son texto plano, y el PDF se escribe a
 * mano por una razon concreta: los bytes tienen que ser deterministas para que reimprimir de aqui a
 * diez anos devuelva <i>exactamente</i> el mismo archivo, y las bibliotecas de PDF escriben la
 * fecha de creacion dentro. Ver {@link RenderizadorPdf}.
 */
@Component
public class GeneradorDeDocumentos {

    private final Map<FormatoDeDocumento, Renderizador> renderizadores =
            new EnumMap<>(FormatoDeDocumento.class);
    private final PuntoDeFirma firma;
    private final RegimenDeLaInstalacion regimen;

    /**
     * El constructor que usa Spring.
     *
     * <p>{@code @Autowired} no es decorativo y su ausencia no fallaba al compilar: con <b>dos</b>
     * constructores y ninguno marcado, Spring no elige —busca el constructor sin argumentos, no lo
     * encuentra, y la aplicacion no arranca—. Lo descubrio el primer despliegue que levanto el
     * artefacto de verdad; ninguna prueba lo veia porque ninguna instanciaba el contexto completo.
     *
     * <p>La firma queda en {@link PuntoDeFirma#SIN_FIRMA} mientras D-05 este abierta: el regimen de
     * firma digital de valores y resoluciones no esta decidido, y elegir uno aqui seria decidirlo
     * por descuido.
     */
    @Autowired
    public GeneradorDeDocumentos(List<Renderizador> disponibles, RegimenDeLaInstalacion regimen) {
        this(disponibles, PuntoDeFirma.SIN_FIRMA, regimen);
    }

    public GeneradorDeDocumentos(
            List<Renderizador> disponibles, PuntoDeFirma firma, RegimenDeLaInstalacion regimen) {
        this.firma = firma;
        this.regimen = regimen;
        for (Renderizador renderizador : disponibles) {
            Renderizador anterior = renderizadores.put(renderizador.formato(), renderizador);
            if (anterior != null) {
                throw new IllegalStateException(
                        "Hay dos renderizadores para "
                                + renderizador.formato()
                                + ": el documento saldria de uno u otro segun el orden en que"
                                + " Spring los descubra, que no es estable");
            }
        }
        for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
            if (!renderizadores.containsKey(formato)) {
                throw new FormatoSinRenderizador(formato);
            }
        }
    }

    /**
     * El documento completo en memoria.
     *
     * <p>Para uno solo, que es lo que pide una pantalla. Para miles, {@link #escribir}.
     */
    public byte[] generar(ModeloDeDocumento modelo, FormatoDeDocumento formato) {
        ByteArrayOutputStream memoria = new ByteArrayOutputStream();
        escribir(modelo, formato, memoria);
        return memoria.toByteArray();
    }

    /**
     * El modelo con la marca de demostracion si esta instalacion lo es, y el mismo modelo si no.
     *
     * <p><b>Este es el unico sitio del sistema que decide si un documento sale marcado.</b> Esta
     * aqui y no en cada emisor porque un emisor que se olvide no es un olvido admisible (#122): las
     * 134 opciones acabarian teniendo que acordarse, y bastaria una que no para que saliera una HR
     * con cifras plausibles y sin marca.
     *
     * <p>Es publico para que {@link EmitirDocumento} pueda <b>guardar</b> el modelo ya marcado. Si
     * solo se marcara al dibujar, la marca de un documento emitido dependeria del regimen del dia
     * en que se reimprime: una instalacion que dejara de ser de demostracion reimprimiria sin marca
     * papeles que salieron con ella —y la comprobacion de que la reimpresion da los mismos bytes
     * saltaria, con razon—. Guardada en el modelo, el documento nace marcado y muere marcado.
     */
    public ModeloDeDocumento marcar(ModeloDeDocumento modelo) {
        return regimen.esDeDemostracion() ? modelo.comoDemostracion() : modelo;
    }

    /**
     * El documento directamente sobre un flujo.
     *
     * <p>Es el camino de la emision masiva: cada documento se escribe y se olvida. Con {@link
     * #generar} en un bucle, emitir el padron entero significaria tenerlo entero en memoria.
     *
     * <p>La firma no pasa por aqui, y es una consecuencia que conviene ver: firmar exige el
     * documento completo, asi que un documento firmado no se puede transmitir mientras se genera.
     * Mientras D-05 siga abierta no importa; cuando se cierre, sera una decision con nombre.
     */
    public void escribir(
            ModeloDeDocumento modelo, FormatoDeDocumento formato, OutputStream salida) {
        try {
            renderizador(formato).escribir(marcar(modelo), salida);
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    /** El documento firmado, cuando D-05 se cierre; hoy, el mismo documento. */
    public byte[] generarFirmado(ModeloDeDocumento modelo, FormatoDeDocumento formato) {
        return firma.firmar(generar(modelo, formato), formato);
    }

    /**
     * El resumen del documento generado.
     *
     * <p>Es lo que convierte «reimprimir devuelve lo mismo» en algo que se puede comprobar, en vez
     * de en algo que se afirma.
     */
    public String resumenDe(ModeloDeDocumento modelo, FormatoDeDocumento formato) {
        return resumenDe(generar(modelo, formato));
    }

    public static String resumenDe(byte[] documento) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(documento));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("Toda JVM trae SHA-256", imposible);
        }
    }

    private Renderizador renderizador(FormatoDeDocumento formato) {
        Renderizador renderizador = renderizadores.get(formato);
        if (renderizador == null) {
            throw new FormatoSinRenderizador(formato);
        }
        return renderizador;
    }

    /**
     * Falta el renderizador de un formato que el manual promete.
     *
     * <p>Se comprueba al arrancar y no al pedir el documento: el manual promete los tres en las 231
     * figuras, asi que descubrir que falta uno cuando un usuario lo pide es descubrirlo tarde.
     */
    public static final class FormatoSinRenderizador extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        FormatoSinRenderizador(FormatoDeDocumento formato) {
            super(
                    "No hay renderizador para "
                            + formato
                            + ", y el manual promete los tres formatos en todo reporte (RF-132)");
        }
    }
}
