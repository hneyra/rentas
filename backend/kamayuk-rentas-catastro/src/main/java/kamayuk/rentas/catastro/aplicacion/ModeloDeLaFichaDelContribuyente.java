package kamayuk.rentas.catastro.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.Reporte;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.UnidadAfecta;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;

/**
 * Convierte la ficha del contribuyente (RF-010) en el modelo neutral de documento.
 *
 * <p>Se escribe <b>una vez</b> y salen los tres formatos. Ese es el trato de RF-132: un reporte
 * nuevo cuesta esta clase, no tres renderizadores.
 *
 * <p><b>Aqui se formatea, y solo aqui.</b> El paquete de documentos recibe texto ya hecho porque no
 * tiene por que saber cuantos decimales lleva un porcentaje —decidirlo alli seria decidir D-03 por
 * la puerta de atras—.
 *
 * <p><b>La unidad va en la cabecera de la columna, nunca dentro de la celda</b> (#607). Es la misma
 * regla que ya seguian {@code ModeloDelFue} —«Area del terreno (m2)»—, {@code ModeloDeLaLicencia} y
 * {@code ModeloDeLaResolucionDeDeterminacion}; esta hoja era la unica de las cuatro que rotulaba
 * «Área de terreno» a secas y metia los «m2» en el dato, de modo que la celda no era un numero para
 * nadie y la misma superficie del mismo predio salia de dos formas segun el papel. Por eso esta
 * clase esta en la lista de excepciones del escaner: compone la cifra a mano porque un documento no
 * tiene serializador, pero la compone <b>sin</b> la unidad.
 */
public final class ModeloDeLaFichaDelContribuyente {

    private ModeloDeLaFichaDelContribuyente() {}

    public static ModeloDeDocumento de(Reporte reporte) {
        List<Campo> cabecera =
                List.of(
                        Campo.de("Código de contribuyente", reporte.contribuyente().codigo()),
                        Campo.de("Nombre o razón social", reporte.contribuyente().nombre()),
                        Campo.de("Documento", reporte.contribuyente().documento()),
                        Campo.de(
                                "Domicilio fiscal",
                                reporte.domicilioFiscal() == null
                                        ? "No registrado"
                                        : reporte.domicilioFiscal()));

        List<List<String>> filas = new ArrayList<>();
        for (UnidadAfecta unidad : reporte.unidades()) {
            filas.add(
                    List.of(
                            unidad.codigo(),
                            unidad.direccion(),
                            unidad.condicion(),
                            unidad.porcentaje().toString(),
                            // Un predio registrado y todavia sin ficha sale asi, no en cero: un
                            // cero se leeria como un terreno de cero metros, que es una cifra.
                            //
                            // La cifra desnuda, sin unidad: la lleva la cabecera de la columna
                            // (#607). Con «360.00 m2» dentro de la celda, quien exporte la hoja
                            // a un calculo se encuentra texto donde esperaba un numero, y la
                            // misma superficie del mismo predio se lee de dos formas segun de
                            // que papel salga.
                            unidad.area() == null
                                    ? "Sin ficha"
                                    : unidad.area().valor().toPlainString(),
                            unidad.uso() == null ? "—" : unidad.uso()));
        }

        Tabla unidades =
                Tabla.de(
                        "Unidades afectas",
                        List.of(
                                "Cód. referencia catastral",
                                "Dirección",
                                "Condición",
                                "% propiedad",
                                "Área de terreno (m2)",
                                "Uso"),
                        filas);

        return new ModeloDeDocumento(
                "Ficha del contribuyente",
                null,
                reporte.aLaFecha(),
                cabecera,
                List.of(unidades),
                // El pie dice lo que el documento NO trae, porque quien lo recibe en ventanilla
                // pregunta justamente por eso.
                List.of(
                        "Este documento no consigna importes: el autovalúo y la deuda se emiten"
                                + " en sus propios valores.",
                        "Los datos corresponden a la fecha indicada arriba."),
                // Duplicado y marca de demostracion, los dos nulos y los dos a proposito:
                // ninguno lo pone quien construye el modelo. El duplicado lo pone
                // EmitirDocumento al reimprimir, y la marca la pone GeneradorDeDocumentos
                // leyendo el regimen de la instalacion (#122).
                null,
                null);
    }
}
