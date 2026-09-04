package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas.PredioDelResumen;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de «Predios encontrados» de {@code consulta_resumen_predial}, tal como sale por HTTP.
 * Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>Las cuatro columnas que la pantalla dibuja —codigo catastral, codigo y nombre del propietario,
 * y direccion del predio— mas lo que hace falta para abrir las otras pestañas: {@code predioId} y
 * {@code tipo}, con los que la interfaz pide el historico de la ficha ({@code GET
 * /catastro/fichas/{tipo}/{cod}?historico=true}), que es la pestaña «Movimientos del Predio».
 *
 * <p><b>No lleva ningun importe</b>, y no por olvido: el impuesto predial se determina por
 * contribuyente y no por predio, y el valuo depende de tablas que todavia no estan firmadas. El
 * porque completo esta en el javadoc de {@link ResumenPredialController}. Un campo ausente es
 * honesto; un cero, o una cifra repartida entre los predios, no lo seria.
 *
 * <p>{@code codPropietario} y {@code nombreDelPropietario} son nulos cuando el predio no tiene
 * titular vigente a la fecha consultada. La pantalla pinta un guion.
 */
public record PredioDelResumenResource(
        long fichaId,
        long predioId,
        String codCatastral,
        @Nullable String codPropietario,
        @Nullable String nombreDelPropietario,
        String direccionDelPredio,
        String uso,
        String tipo,
        int version,
        String vigenciaDesde) {

    public static PredioDelResumenResource de(PredioDelResumen fila) {
        FichaEncontrada ficha = fila.ficha();
        return new PredioDelResumenResource(
                ficha.fichaId(),
                ficha.predioId(),
                ficha.codigo().valor(),
                fila.codigoTitular(),
                ficha.titular(),
                ficha.direccion(),
                ficha.uso(),
                ficha.tipo().name(),
                ficha.version(),
                ficha.vigenciaDesde().toString());
    }
}
