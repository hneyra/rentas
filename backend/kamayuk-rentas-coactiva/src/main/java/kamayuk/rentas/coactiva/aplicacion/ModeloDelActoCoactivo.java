package kamayuk.rentas.coactiva.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.valores.ValorParaCoactiva;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una resolucion coactiva, sin decir en que formato (#41, RF-101, RF-132).
 *
 * <h2>Por que esto y no una plantilla de texto</h2>
 *
 * <p>{@link ModeloDeDocumento} es lo que hace que el PDF, la hoja de calculo y el texto enriquecido
 * digan lo mismo, y —lo que aqui importa mas— es lo que {@code EmitirDocumento} <b>guarda</b>.
 * Reimprimir la REC de 2027 en 2037 vuelve a dibujar estos datos, no vuelve a calcularlos: la deuda
 * recalculada diez anios despues daria otra cifra, y el obligado tiene el papel de entonces en la
 * mano.
 *
 * <p>Por eso todo lo que entra aqui es <b>texto ya formateado</b>, con su fecha pegada: {@code
 * aLaFecha} del modelo es el dia al que esta la deuda impresa (regla 9, RNF-075). Un papel de
 * cobranza sin decir de que dia es una cifra es un papel que no sirve para discutir nada.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>Esta resolucion sale <b>sin firma digital</b>, y es imprimible igual. El regimen de firma —que
 * certificado, quien custodia la clave, si va incrustada o en sobre aparte— es la decision D-05 y
 * sigue abierta; lo que ya esta resuelto es <b>donde</b> entra, y es {@link PuntoDeFirma}, entre
 * generar los bytes y entregarlos. {@code GeneradorDeDocumentos.generarFirmado} pasa por ahi en
 * cada emision, asi que cerrar D-05 sera dar una implementacion de esa interfaz y no repasar los
 * sitios que emiten. El pie del documento lleva el bloque de firmas manuscritas del ejecutor y del
 * auxiliar, que es lo que la resolucion necesita mientras tanto.
 */
final class ModeloDelActoCoactivo {

    private ModeloDelActoCoactivo() {}

    /**
     * El modelo de la resolucion.
     *
     * @param expediente la carpeta sobre la que se actua
     * @param tipo que acto es; de el sale el titulo
     * @param medida la forma de la medida cautelar, solo en la REC-2
     * @param descripcion la glosa del acto
     * @param obligado el nombre del obligado, ya resuelto contra el padron
     * @param codigoDelObligado su codigo de contribuyente
     * @param direccion donde se le notifica: la direccion referencial vigente del expediente
     * @param plazo el plazo que la REC-1 concede, leido del conjunto sellado; nulo en los demas
     * @param deuda cuanto se debe, con el dia al que esta (regla 9)
     * @param valores los valores que el expediente agrupa
     */
    static ModeloDeDocumento de(
            ExpedienteCoactivo expediente,
            TipoDeActoCoactivo tipo,
            @Nullable TipoDeMedidaCautelar medida,
            String descripcion,
            String obligado,
            String codigoDelObligado,
            @Nullable String direccion,
            @Nullable Plazo plazo,
            DeudaDelExpediente deuda,
            List<ValorParaCoactiva> valores) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Expediente coactivo", expediente.numero()));
        cabecera.add(Campo.de("Ejercicio", String.valueOf(expediente.ejercicio().valor())));
        cabecera.add(Campo.de("Obligado", obligado));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelObligado));
        cabecera.add(Campo.de("Domicilio donde se notifica", direccion == null ? "" : direccion));
        cabecera.add(Campo.de("Ejecutor coactivo", expediente.ejecutor()));
        cabecera.add(
                Campo.de(
                        "Auxiliar coactivo",
                        expediente.auxiliar() == null ? "" : expediente.auxiliar()));
        cabecera.add(Campo.de("Asunto", expediente.asunto() == null ? "" : expediente.asunto()));
        if (medida != null) {
            cabecera.add(Campo.de("Medida cautelar", medida.etiqueta()));
        }
        if (plazo != null) {
            // El plazo se IMPRIME tal como el parametro sellado lo dice ("7 DIAS_HABILES"), no
            // como una frase compuesta aqui: si manana la norma lo cambia, cambia el parametro y
            // el papel sale con la cifra nueva sin tocar una linea (regla 5).
            cabecera.add(Campo.de("Plazo para cumplir (art. 14.1, Ley 26979)", plazo.toString()));
        }
        cabecera.add(Campo.de("Glosa", descripcion));

        List<Tabla> tablas = new ArrayList<>();
        tablas.add(tablaDeValores(valores));
        tablas.add(tablaDeLaDeuda(deuda));

        List<String> pie =
                List.of(
                        "Ley 26979 — Ley de Procedimiento de Ejecucion Coactiva.",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF.",
                        "",
                        "_______________________________        _______________________________",
                        "        Ejecutor coactivo                      Auxiliar coactivo",
                        "",
                        "Documento sin firma digital: el regimen de firma de resoluciones es la"
                                + " decision D-05, abierta.");

        return new ModeloDeDocumento(
                tipo.titulo(),
                expediente.numero(),
                deuda.actualizadaA(),
                cabecera,
                tablas,
                pie,
                null,
                null);
    }

    private static Tabla tablaDeValores(List<ValorParaCoactiva> valores) {
        List<List<String>> filas = new ArrayList<>();
        for (ValorParaCoactiva valor : valores) {
            filas.add(
                    List.of(
                            valor.numero(),
                            valor.tipo(),
                            String.valueOf(valor.ejercicio().valor()),
                            valor.fechaEmision().toString(),
                            valor.exigibleDesde() == null ? "" : valor.exigibleDesde().toString()));
        }
        return Tabla.de(
                "Valores materia de cobranza",
                List.of("Numero", "Tipo", "Ejercicio", "Emitido el", "Exigible desde"),
                filas);
    }

    /**
     * El desglose, con su fecha en el titulo.
     *
     * <p>La fecha va <b>dentro</b> del titulo de la tabla ademas de en {@code aLaFecha} del modelo:
     * quien recorta el cuadro de deuda de un PDF para pegarlo en un informe se lleva la fecha con
     * el (regla 9).
     */
    private static Tabla tablaDeLaDeuda(DeudaDelExpediente deuda) {
        List<List<String>> filas =
                List.of(
                        fila("Insoluto", deuda.insoluto()),
                        fila("Reajuste", deuda.reajuste()),
                        fila("Interes moratorio", deuda.interes()),
                        fila("Gastos", deuda.gasto()),
                        fila("Deuda materia de cobranza", deuda.materiaDeCobranza()),
                        fila("Costas y gastos del procedimiento", deuda.costas()),
                        fila("TOTAL EXIGIBLE", deuda.total()));
        return Tabla.de(
                "Deuda actualizada al " + deuda.actualizadaA(),
                List.of("Concepto", "Importe (S/)"),
                filas);
    }

    private static List<String> fila(String concepto, Dinero importe) {
        return List.of(concepto, importe.valor().toPlainString());
    }
}
