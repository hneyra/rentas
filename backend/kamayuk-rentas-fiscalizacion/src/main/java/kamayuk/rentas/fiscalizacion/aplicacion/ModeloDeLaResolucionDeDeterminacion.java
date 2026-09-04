package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una resolucion de determinacion de fiscalizacion, sin decir en que formato
 * (#52, RF-057, RF-132).
 *
 * <p>Las columnas son las que la pantalla {@code resolucion_determinacion_fisc} declara —Ejercicio,
 * Determinado, Declarado, Diferencia, Interes, Total—: el prototipo manda.
 *
 * <h2>Sin cifra no se dibuja un cero</h2>
 *
 * <p>Mientras D-02a no entregue la UIT, el cuadro de valores unitarios y la tabla de depreciacion
 * —y D-02c la multa del art. 176—, las lineas de la liquidacion salen sin importes (#198). Aqui eso
 * se imprime como {@code —} y <b>no como 0,00</b>: un contribuyente lee un cero como «no debo
 * nada», y el papel notificado es lo que se discute en ventanilla. Es el mismo criterio que {@code
 * LineaDeLiquidacion.esperaSusCifras} publica para la pantalla.
 *
 * <p>Lo que si sale siempre con valor es la comparacion <b>estructural</b>: que constaba inscrito y
 * que queda inscrito. No depende de ninguna norma —es lo que el fiscalizador midio frente a lo que
 * la ficha decia— y es lo que justifica la version nueva del padron.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>La resolucion sale <b>sin firma digital</b> y es imprimible igual, como la de gerencia (#50).
 * El regimen de firma sigue siendo D-05, abierta; donde entra ya esta resuelto y es {@link
 * PuntoDeFirma}, entre generar los bytes y entregarlos.
 */
final class ModeloDeLaResolucionDeDeterminacion {

    /** Lo que se imprime donde no hay cifra. Nunca un cero. */
    private static final String SIN_CIFRA = "—";

    private ModeloDeLaResolucionDeDeterminacion() {}

    /**
     * El modelo de la resolucion.
     *
     * @param liquidacion el resultado que se transfiere
     * @param lineas el contraste, una linea por ejercicio
     * @param contribuyente el nombre del obligado, ya resuelto contra el padron
     * @param codigoDelContribuyente su codigo
     * @param documentoDelContribuyente su documento de identidad
     * @param domicilio donde se le notifica
     * @param referenciaDeLaUnidad el codigo del predio o la placa del vehiculo
     * @param version lo que la transferencia dejo en el padron; nulo en una vehicular
     * @param aLaFecha el dia de la resolucion, al que estan las cifras (regla 9, RNF-075)
     * @param documentoSustento el papel que sustenta el acto
     * @param sustento el fundamento
     * @param baseLegal la norma que la ampara
     */
    static ModeloDeDocumento de(
            Liquidacion liquidacion,
            List<LineaDeLiquidacion> lineas,
            String contribuyente,
            String codigoDelContribuyente,
            String documentoDelContribuyente,
            @Nullable String domicilio,
            String referenciaDeLaUnidad,
            @Nullable VersionTransferida version,
            LocalDate aLaFecha,
            String documentoSustento,
            String sustento,
            String baseLegal) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Liquidacion", liquidacion.numero()));
        cabecera.add(Campo.de("Version de la liquidacion", String.valueOf(liquidacion.version())));
        cabecera.add(
                Campo.de(
                        "Periodo fiscalizado",
                        liquidacion.ejercicioDesde() + " - " + liquidacion.ejercicioHasta()));
        cabecera.add(Campo.de("Tipo de fiscalizacion", liquidacion.tipo().name()));
        cabecera.add(Campo.de("Motivo determinante", liquidacion.motivoDeterminante()));
        cabecera.add(Campo.de("Contribuyente", contribuyente));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelContribuyente));
        cabecera.add(Campo.de("Documento", documentoDelContribuyente));
        cabecera.add(Campo.de("Domicilio", domicilio == null ? "" : domicilio));
        cabecera.add(Campo.de("Unidad fiscalizada", referenciaDeLaUnidad));
        cabecera.add(Campo.de("Sustento documental", documentoSustento));
        cabecera.add(Campo.de("Sustento", sustento));
        cabecera.add(Campo.de("Base legal", baseLegal));

        List<Tabla> tablas = new ArrayList<>();
        tablas.add(tablaDeLaDeterminacion(lineas, aLaFecha));
        if (version != null) {
            tablas.add(tablaDelPadron(version));
        }

        List<String> pie = new ArrayList<>();
        pie.add(
                "Determinacion de oficio: TUO del Codigo Tributario, D.S. 133-2013-EF, arts. 76 y"
                        + " 77.");
        pie.add("TUO de la Ley 27444 — Ley del Procedimiento Administrativo General.");
        if (lineas.stream().anyMatch(LineaDeLiquidacion::esperaSusCifras)) {
            // Se dice en el papel, no solo en el codigo: quien recibe la resolucion tiene que
            // saber que la columna vacia es una determinacion pendiente y no una deuda de cero.
            pie.add("");
            pie.add(
                    "Los importes marcados «"
                            + SIN_CIFRA
                            + "» estan pendientes de determinacion: no significan deuda cero.");
        }
        pie.add("");
        pie.add("_______________________________        _______________________________");
        pie.add("        Auditor fiscalizador                       Gerente de Rentas");
        pie.add("");
        pie.add(
                "Documento sin firma digital: el regimen de firma de resoluciones es la decision"
                        + " D-05, abierta.");

        return new ModeloDeDocumento(
                "Resolucion de determinacion",
                "Procedimiento de fiscalizacion tributaria — " + liquidacion.numero(),
                aLaFecha,
                cabecera,
                tablas,
                pie,
                null,
                null);
    }

    /**
     * El cuadro de la pantalla, ejercicio por ejercicio.
     *
     * <p>La fecha va <b>dentro</b> del titulo ademas de en {@code aLaFecha} del modelo: quien
     * recorta el cuadro de un PDF para pegarlo en un informe se lleva la fecha con el (regla 9).
     */
    private static Tabla tablaDeLaDeterminacion(List<LineaDeLiquidacion> lineas, LocalDate fecha) {
        List<List<String>> filas = new ArrayList<>();
        for (LineaDeLiquidacion linea : lineas) {
            filas.add(
                    List.of(
                            String.valueOf(linea.ejercicio().valor()),
                            importe(linea.baseHallada()),
                            importe(linea.baseDeclarada()),
                            importe(linea.insolutoOmitido()),
                            importe(linea.multaTributaria()),
                            total(linea)));
        }
        return Tabla.de(
                "Determinacion al " + fecha,
                List.of(
                        "Ejercicio",
                        "Determinado S/",
                        "Declarado S/",
                        "Diferencia S/",
                        "Multa S/",
                        "Total S/"),
                filas);
    }

    /** Que constaba inscrito y que queda inscrito: la parte que no depende de D-02a. */
    private static Tabla tablaDelPadron(VersionTransferida version) {
        return Tabla.de(
                "Inscripcion en el padron catastral",
                List.of("Concepto", "Antes", "Despues"),
                List.of(
                        List.of(
                                "Version de la ficha",
                                String.valueOf(version.version() - 1),
                                String.valueOf(version.version())),
                        // La cifra desnuda, sin unidad: la lleva el titulo de la fila. Con
                        // «120.00 m2» dentro de la celda, quien exporte la resolucion a hoja de
                        // calculo se encuentra con texto donde esperaba un numero.
                        List.of(
                                "Area de terreno (m2)",
                                version.areaAnterior().valor().toPlainString(),
                                version.areaNueva().valor().toPlainString()),
                        List.of("Uso", version.usoAnterior(), version.usoNuevo())));
    }

    /**
     * El total de una linea: la diferencia mas la multa, y solo si las dos se conocen.
     *
     * <p>Sumar una cifra con una ausencia daria la cifra, y el papel diria un total que no incluye
     * lo que falta. Mientras falte cualquiera de las dos, el total tambien esta pendiente.
     */
    private static String total(LineaDeLiquidacion linea) {
        Dinero diferencia = linea.insolutoOmitido();
        Dinero multa = linea.multaTributaria();
        if (diferencia == null || multa == null) {
            return SIN_CIFRA;
        }
        return diferencia.mas(multa).valor().toPlainString();
    }

    private static String importe(@Nullable Dinero cifra) {
        return cifra == null ? SIN_CIFRA : cifra.valor().toPlainString();
    }
}
