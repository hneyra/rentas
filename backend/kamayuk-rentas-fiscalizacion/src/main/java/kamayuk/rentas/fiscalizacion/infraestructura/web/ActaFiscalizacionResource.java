package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.ActaConLoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acta de fiscalización tal como sale por HTTP. Campos en español {@code camelCase}.
 *
 * <h2>Las dos mitades del contraste, y no una (#191)</h2>
 *
 * <p>La tabla que esta operación llena se titula «Lo que el verificador midió frente a lo que el
 * titular declaró», y hasta #191 el recurso publicaba <b>sólo lo hallado</b>: {@code areaHallada} y
 * {@code usoHallado}. Las columnas «Declarado» y «Diferencia» salían en raya en todas sus filas,
 * dos de cinco. Ahora viajan {@code areaDeclarada}, {@code usoDeclarado} y {@code diferenciaDeArea}
 * — los tres resueltos por {@link ActaConLoDeclarado} a partir de la versión de ficha que el acta
 * referencia, no guardados en la fila.
 *
 * <p><b>La diferencia viaja hecha</b>, y eso es el punto: restar dos magnitudes servidas para
 * llenar una celda es publicar una cifra que ninguna operación afirma, y esa columna es «lo que
 * sostiene la determinación». La resta es la misma función pura que usan la liquidación y la
 * detección de omisos: nunca negativa, y nula si falta un lado.
 *
 * <p>Los tres salen nulos en un acta <b>vehicular</b> —un vehículo no tiene área ni uso declarados
 * contra los que contrastar— y en un acta predial cuyo predio no tenía ficha registrada a la fecha
 * de la visita, que es justamente el predio que no consta en el catastro.
 *
 * <h2>Lo que sigue sin publicarse: el NOMBRE del contribuyente</h2>
 *
 * <p>Viaja {@code contribuyenteId} y no el nombre. Hoy no deja ningún hueco —el campo
 * «Contribuyente» de las dos pantallas del acta es un <b>mando</b>, no una celda de sólo lectura, y
 * la fila de la muestra de la que el acta se abre ya publica {@code titular} y {@code
 * codContribuyente}—, así que resolverlo aquí publicaría una promesa que ninguna pantalla hace: es
 * lo que #431, #432 y #544 tuvieron que retirar. Y en la respuesta del {@code POST} saldría nulo,
 * que es el modo de fallo que #194 midió. El día que la hoja tenga que decir de quién es el acta
 * que dibuja, es #216.
 *
 * <p>{@code areaHallada} no es {@code Dinero}: no le aplica {@code
 * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (esa regla mira el tipo {@code Dinero}), así que viaja
 * tipada, sin fecha de actualización, y la escribe el serializador de {@code ConfiguracionDeJson}
 * —{@code "180.50"}, sin unidad— igual que las de la muestra, la liquidación y la resolución
 * (#546). Las tres del lado declarado viajan igual, por lo mismo.
 *
 * <p>{@code usoHallado} sale nulo mientras la inspección no lo consigne, y eso es un dato: nulo es
 * «no se anotó», que no es lo mismo que «coincide con el declarado». Sólo un acta predial lo lleva
 * (#599, V76).
 *
 * @param areaDeclarada la superficie que consigna la versión de ficha que el acta referencia
 * @param usoDeclarado el uso que consigna esa misma versión
 * @param diferenciaDeArea hallada menos declarada, nunca negativa; nula si falta un lado
 */
public record ActaFiscalizacionResource(
        long id,
        long programaId,
        int version,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaId,
        String fechaVisita,
        String fiscalizador,
        @Nullable String hallazgo,
        @Nullable AreaM2 areaDeclarada,
        @Nullable AreaM2 areaHallada,
        @Nullable AreaM2 diferenciaDeArea,
        @Nullable String usoDeclarado,
        @Nullable String usoHallado,
        @Nullable String detalle,
        String estado) {

    public static ActaFiscalizacionResource de(ActaConLoDeclarado contraste) {
        ActaFiscalizacion acta = contraste.acta();
        return new ActaFiscalizacionResource(
                acta.id() == null ? 0L : acta.id(),
                acta.programaId(),
                acta.version(),
                acta.contribuyenteId(),
                acta.predioId(),
                acta.vehiculoId(),
                acta.fichaId(),
                acta.fechaVisita().toString(),
                acta.fiscalizador(),
                acta.hallazgo() == null ? null : acta.hallazgo().name(),
                contraste.areaDeclarada(),
                acta.areaHallada(),
                contraste.diferenciaDeArea(),
                contraste.usoDeclarado(),
                acta.usoHallado(),
                acta.detalle(),
                acta.estado().name());
    }
}
