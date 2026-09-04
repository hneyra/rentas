package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;

/**
 * Convierte la constancia de no adeudo (RF-049, RNF-084) en el modelo neutral de documento, para
 * que salga en los tres formatos de RF-132 y quede cumplido RNF-081 (#72).
 *
 * <h2>Dice exactamente lo que dice la vista previa</h2>
 *
 * <p>El titulo, el subtitulo del TUPA, las cuatro columnas y las dos frases del resultado son
 * <b>las mismas palabras</b> que la pantalla {@code constancia} dibuja hoy —el catalogo portado
 * ({@code catalogo/pantallas/consultas.generado.ts}) y su conexion ({@code
 * pantallas/consultas/index.ts})—. Un documento exportado que dijera otra cosa que la hoja de la
 * que salio seria dos documentos con un solo nombre, y quien discute en ventanilla trae el papel.
 *
 * <h2>Ninguna cifra se recompone aqui</h2>
 *
 * <p>El saldo de cada fila es el total que {@code ObligacionConDeuda} ya trae calculado, formateado
 * y nada mas (RNF-083). Y la situacion —«Pendiente» o «Cancelado»— se lee de {@code esPositivo()}
 * sobre ese mismo total, que es de donde la lee {@link ConstanciaDeNoAdeudo#seNiega()}: si se
 * derivara por otro camino, una fila podria decir «Cancelado» en un papel que se niega.
 *
 * <h2>Dos lineas de firma, y ninguna digital</h2>
 *
 * <p>RNF-084 pide A4 vertical, una hoja y dos lineas de firma; las dos van en el pie. La firma
 * digital no: D-05 sigue abierta y el pie lo dice, como en el resto de documentos del sistema.
 */
public final class ModeloDeLaConstanciaDeNoAdeudo {

    private ModeloDeLaConstanciaDeNoAdeudo() {}

    /** El nombre base del archivo que se descarga: {@code constancia-C-000900.pdf}. */
    public static String nombreDeArchivo(ConstanciaDeNoAdeudo constancia) {
        return "constancia-" + constancia.codigoContribuyente();
    }

    public static ModeloDeDocumento de(ConstanciaDeNoAdeudo constancia) {
        List<Campo> cabecera =
                List.of(
                        Campo.de("Contribuyente", constancia.codigoContribuyente()),
                        Campo.de("Resultado", resultado(constancia)),
                        Campo.de("Fecha de corte", constancia.fecha().toString()));

        List<List<String>> filas = new ArrayList<>();
        for (ObligacionConDeuda obligacion : constancia.obligaciones()) {
            boolean pendiente = obligacion.deuda().total().esPositivo();
            filas.add(
                    List.of(
                            obligacion.tributo(),
                            String.valueOf(obligacion.ejercicio().valor()),
                            pendiente ? "Pendiente" : "Cancelado",
                            obligacion.deuda().total().toString()));
        }

        Tabla obligaciones =
                Tabla.de(
                        "Obligaciones",
                        List.of("Tributo", "Ejercicios", "Situación", "Saldo S/"),
                        filas);

        return new ModeloDeDocumento(
                TITULO,
                SUBTITULO,
                constancia.fecha(),
                cabecera,
                List.of(obligaciones),
                pie(constancia),
                // Duplicado y marca de demostracion, los dos nulos y los dos a proposito: ninguno
                // lo pone quien construye el modelo. La marca la pone GeneradorDeDocumentos
                // leyendo el regimen de la instalacion (#122).
                null,
                null);
    }

    /** El titulo del catalogo, letra por letra. */
    public static final String TITULO = "Constancia de no adeudo";

    /** El subtitulo del catalogo, letra por letra. */
    public static final String SUBTITULO =
            "Emitida conforme al Texto Único de Procedimientos Administrativos vigente";

    /** Lo que la pantalla escribe en «Resultado» cuando hay saldo pendiente. */
    public static final String SE_NIEGA = "SE NIEGA — hay deuda pendiente";

    /** Lo que la pantalla escribe en «Resultado» cuando no lo hay. */
    public static final String SE_EMITE = "SE EMITE — no se registra deuda pendiente";

    private static String resultado(ConstanciaDeNoAdeudo constancia) {
        return constancia.seNiega() ? SE_NIEGA : SE_EMITE;
    }

    private static List<String> pie(ConstanciaDeNoAdeudo constancia) {
        String cierre =
                constancia.seNiega()
                        ? "No se otorga la constancia: el contribuyente registra saldo pendiente a"
                                + " la fecha de corte indicada."
                        : "Documento emitido por el Sistema de Gestión Tributaria Municipal. La"
                                + " información corresponde al registro a la fecha de emisión.";

        return List.of(
                cierre,
                "Cifras al "
                        + constancia.fecha()
                        + ". Una cifra sin su fecha es una cifra que mañana es otra (RNF-075).",
                "",
                "_______________________________",
                "      Cajero / Responsable",
                "",
                "_______________________________",
                "          Contribuyente",
                "",
                "Documento sin firma digital: el regimen de firma es la decision D-05, abierta.");
    }
}
