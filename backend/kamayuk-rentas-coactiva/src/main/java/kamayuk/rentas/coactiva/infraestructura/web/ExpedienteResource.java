package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import org.jspecify.annotations.Nullable;

/**
 * El expediente coactivo como lo ve la interfaz (#40, RF-100).
 *
 * <p><b>Cada cifra con su fecha</b> (regla 9, RNF-075). {@code deudaAlDia} dice a que dia estan las
 * cinco cifras del bloque de deuda, y no es la fecha de apertura del expediente: un expediente de
 * marzo consultado en agosto debe cinco meses mas de interes, y bajo una sola fecha pareceria
 * calculado el dia que se abrio.
 *
 * <p>Los importes salen como texto en su representacion decimal, como en el resto de la API: un
 * numero JSON pasa por coma flotante en cualquier cliente, y un centimo perdido en el transporte es
 * una liquidacion que no cuadra (regla 1, RNF-055).
 *
 * <p><b>{@code costas} viaja aunque sea cero.</b> Es #42: el sumando existe desde ahora para que la
 * pantalla no tenga que cambiar de forma cuando lo llene, y ningun importe se inventa aqui.
 *
 * @param numero el numero impreso, tal como esta en la caratula
 * @param ejercicio el «Año» de la pantalla
 * @param correlativo el «Número» de la pantalla, sin formato
 * @param codContribuyente el codigo del obligado
 * @param ejecutor el ejecutor coactivo
 * @param auxiliar el auxiliar coactivo, si consta
 * @param fechaDeApertura el dia en que se abrio
 * @param asunto el asunto de la caratula, si consta
 * @param direccionReferencial la vigente: la del ultimo cambio, o la de apertura
 * @param estado el estado derivado del historial
 * @param estadoCodigo su codigo del manual ({@code 011}, {@code 012}…)
 * @param valores cuantos valores agrupa
 * @param insoluto la parte insoluta de la deuda, a {@code deudaAlDia}
 * @param reajuste el reajuste, a {@code deudaAlDia}
 * @param interes el interes moratorio, a {@code deudaAlDia}
 * @param gastos los gastos, a {@code deudaAlDia}
 * @param deudaMateriaDeCobranza la suma de las cuatro, sin costas
 * @param costas las costas y gastos del procedimiento; cero hasta #42
 * @param totalExigible la deuda materia de cobranza mas las costas
 * @param deudaAlDia a que dia estan las siete cifras anteriores (regla 9, RNF-075)
 * @param valoresImportados los valores que agrupa, con el dia en que entraron
 * @param historial la traza del expediente, del primero al ultimo
 */
public record ExpedienteResource(
        String numero,
        int ejercicio,
        long correlativo,
        String codContribuyente,
        String ejecutor,
        @Nullable String auxiliar,
        LocalDate fechaDeApertura,
        @Nullable String asunto,
        @Nullable String direccionReferencial,
        String estado,
        String estadoCodigo,
        int valores,
        String insoluto,
        String reajuste,
        String interes,
        String gastos,
        String deudaMateriaDeCobranza,
        String costas,
        String totalExigible,
        LocalDate deudaAlDia,
        List<ValorImportadoResource> valoresImportados,
        List<MovimientoResource> historial) {

    /**
     * Una fila de la grilla: sin el detalle, que una pagina de veinte no puede costar veinte
     * lecturas de historial.
     */
    public static ExpedienteResource de(
            ConsultaDeExpedientes.ExpedienteConDeuda fila, String codContribuyente) {
        return construir(
                fila.fila().expediente(),
                codContribuyente,
                fila.fila().estado().etiqueta(),
                fila.fila().estado().codigo(),
                fila.fila().direccionReferencialVigente(),
                fila.fila().valores(),
                fila.deuda(),
                List.of(),
                List.of());
    }

    /** La ficha completa: el expediente, sus valores y todo su historial. */
    public static ExpedienteResource de(
            ConsultaDeExpedientes.FichaDelExpediente ficha, String codContribuyente) {
        return construir(
                ficha.expediente(),
                codContribuyente,
                ficha.estado().etiqueta(),
                ficha.estado().codigo(),
                ficha.direccionReferencialVigente(),
                ficha.valores().size(),
                ficha.deuda(),
                ficha.valores(),
                ficha.historial());
    }

    private static ExpedienteResource construir(
            ExpedienteCoactivo expediente,
            String codContribuyente,
            String estado,
            String estadoCodigo,
            @Nullable String direccion,
            int valores,
            DeudaDelExpediente deuda,
            List<ValorDelExpediente> importados,
            List<MovimientoDelExpediente> historial) {

        List<ValorImportadoResource> valoresImportados = new ArrayList<>();
        for (ValorDelExpediente valor : importados) {
            valoresImportados.add(ValorImportadoResource.de(valor));
        }
        List<MovimientoResource> traza = new ArrayList<>();
        for (int i = 0; i < historial.size(); i++) {
            // «Activo» de la pantalla: el ultimo movimiento que llevo estado es el que rige.
            // Se deriva aqui y no se guarda, por lo mismo que el estado.
            boolean activo = esElUltimoConEstado(historial, i);
            traza.add(MovimientoResource.de(historial.get(i), activo));
        }

        return new ExpedienteResource(
                expediente.numero(),
                expediente.ejercicio().valor(),
                expediente.correlativo(),
                codContribuyente,
                expediente.ejecutor(),
                expediente.auxiliar(),
                expediente.fechaApertura(),
                expediente.asunto(),
                direccion,
                estado,
                estadoCodigo,
                valores,
                deuda.insoluto().valor().toPlainString(),
                deuda.reajuste().valor().toPlainString(),
                deuda.interes().valor().toPlainString(),
                deuda.gasto().valor().toPlainString(),
                deuda.materiaDeCobranza().valor().toPlainString(),
                deuda.costas().valor().toPlainString(),
                deuda.total().valor().toPlainString(),
                deuda.actualizadaA(),
                List.copyOf(valoresImportados),
                List.copyOf(traza));
    }

    private static boolean esElUltimoConEstado(
            List<MovimientoDelExpediente> historial, int posicion) {
        if (historial.get(posicion).estado() == null) {
            return false;
        }
        for (int siguiente = posicion + 1; siguiente < historial.size(); siguiente++) {
            if (historial.get(siguiente).estado() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Un valor dentro del expediente.
     *
     * @param valorId el identificador del valor
     * @param fechaDeImportacion el dia en que entro
     */
    public record ValorImportadoResource(long valorId, LocalDate fechaDeImportacion) {

        static ValorImportadoResource de(ValorDelExpediente valor) {
            return new ValorImportadoResource(valor.valorId(), valor.fechaImportacion());
        }
    }

    /**
     * Una linea del historial, tal como la pinta {@code expediente_historial}.
     *
     * @param tipo APERTURA, ESTADO o DIRECCION
     * @param estado el estado al que paso, si el movimiento lo lleva
     * @param estadoCodigo su codigo del manual, si lo lleva
     * @param direccionReferencial la direccion nueva, si el movimiento la lleva
     * @param fecha el dia del acto
     * @param motivo la causal
     * @param fecDoc la fecha del documento de respaldo, si lo hay
     * @param numDoc el numero del documento de respaldo, si lo hay
     * @param activo si es el movimiento de estado que rige hoy; derivado, no guardado
     * @param usuario quien lo registro
     * @param observaciones por que se registro (regla 10)
     */
    public record MovimientoResource(
            String tipo,
            @Nullable String estado,
            @Nullable String estadoCodigo,
            @Nullable String direccionReferencial,
            LocalDate fecha,
            String motivo,
            @Nullable LocalDate fecDoc,
            @Nullable String numDoc,
            boolean activo,
            @Nullable String usuario,
            String observaciones) {

        static MovimientoResource de(MovimientoDelExpediente movimiento, boolean activo) {
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.estado() == null ? null : movimiento.estado().etiqueta(),
                    movimiento.estado() == null ? null : movimiento.estado().codigo(),
                    movimiento.direccionReferencial(),
                    movimiento.fecha(),
                    movimiento.motivo(),
                    movimiento.documentoFecha(),
                    movimiento.documentoNumero(),
                    activo,
                    movimiento.usuarioRegistro(),
                    movimiento.observacion().texto());
        }
    }
}
