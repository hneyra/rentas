package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la muestra tal como sale por HTTP. Campos en español {@code camelCase}.
 *
 * <p>Lleva los <b>tres identificadores</b> que el acta exige —{@code programaId}, {@code
 * contribuyenteId} y {@code predioId}—, que es lo que permite abrir {@code fisc_predial} desde una
 * fila real y no sobre identificadores inventados (AC 2 de #431).
 *
 * <p><b>Ninguna cifra se compone</b> (RNF-083): la diferencia de superficie la calcula el dominio y
 * viaja hecha, o no viaja. Y el «Uso declarado» que la grilla dibuja <b>no está aquí</b>: {@code
 * DeteccionDeOmisos} no resuelve el uso declarado —pasa {@code null} en los dos lados de la
 * comparación—, y el que sí publica el padrón es el que el <b>catastro</b> tiene inscrito, que bajo
 * una columna que dice «Uso declarado» sería otro rótulo con otro significado (RNF-080).
 *
 * <p>Las tres superficies viajan como {@link AreaM2} y no como texto (#546): el serializador de
 * {@code ConfiguracionDeJson} las escribe {@code "180.50"}, la misma forma que la liquidación y la
 * resolución. Con {@code toString()} salían {@code "180.50 m2"} — dos formas del mismo dato en el
 * mismo módulo, y la unidad la pone la cabecera de la columna.
 *
 * <h2>Los tres campos del titular van en {@code null} cuando el predio no tiene ninguno (#586)</h2>
 *
 * <p>Desde {@code V73} la muestra admite el predio <b>sin titularidad vigente</b> —el que nadie
 * reclama, el candidato de primer orden—, así que {@code contribuyenteId}, {@code codContribuyente}
 * y {@code titular} salen nulos los tres. Es la misma convención que {@code OmisoResource} usa
 * desde #545 para la misma situación y por el mismo motivo: sale así, y sale en la lista.
 *
 * <p>Nulo aquí significa <b>que el padrón no tiene a nadie</b>. Es distinto de que el titular no
 * esté: cuando la muestra guardó un identificador y el padrón ya no lo resuelve, {@code
 * contribuyenteId} viaja con valor y los otros dos se caen al identificador, porque un predio cuyo
 * titular se dio de baja es justamente el que hay que revisar.
 *
 * @param visitado si ese predio ya tiene acta en este programa; se DERIVA, no se guarda
 */
public record MuestraResource(
        long programaId,
        long predioId,
        String codRefCatastral,
        @Nullable Long contribuyenteId,
        @Nullable String codContribuyente,
        @Nullable String titular,
        @Nullable String sector,
        String condicion,
        @Nullable AreaM2 areaCatastral,
        @Nullable AreaM2 areaDeclarada,
        @Nullable AreaM2 diferenciaDeArea,
        boolean visitado,
        String fechaSorteo) {

    public static MuestraResource de(
            MuestraDelPrograma fila,
            @Nullable String codContribuyente,
            @Nullable String titular,
            boolean visitado) {
        return new MuestraResource(
                fila.programaId(),
                fila.predioId(),
                fila.codigoReferenciaCatastral(),
                fila.contribuyenteId(),
                codContribuyente,
                titular,
                fila.sectorCodigo(),
                fila.condicion().name(),
                fila.areaCatastral(),
                fila.areaDeclarada(),
                fila.diferenciaDeArea(),
                visitado,
                fila.fechaSorteo().toString());
    }
}
