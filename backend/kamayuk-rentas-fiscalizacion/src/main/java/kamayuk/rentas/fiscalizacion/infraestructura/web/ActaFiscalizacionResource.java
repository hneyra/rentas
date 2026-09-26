package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
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
 * <h2>De quién es el acta: {@code contribuyente} y {@code codContribuyente} (#216)</h2>
 *
 * <p>Hasta este issue viajaba {@code contribuyenteId} —el identificador interno— y <b>nunca el
 * nombre</b>, de modo que quien leyera esta operación tenía el acta y no sabía de quién era: para
 * averiguarlo hacía falta una segunda consulta al padrón por cada fila, que es exactamente lo que
 * {@link kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes#porIds} existe para evitar.
 *
 * <p><b>#191 lo dejó fuera a propósito y con su motivo, y lo que cambió es el motivo.</b> Entonces
 * no dejaba ningún hueco: la fila de la muestra desde la que el acta se abre ya publica {@code
 * titular} y {@code codContribuyente}. Pero la hoja que dibuja un acta <b>toma «la primera de la
 * relación» y no dice cuál es</b>, y eso sólo se puede arreglar publicando de quién es: una
 * pantalla que enseña un contraste de áreas sin nombrar al obligado es un contraste que no se puede
 * comprobar contra nada.
 *
 * <p><b>Y la respuesta del {@code POST} los lleva llenos</b>, que era la otra mitad de la decisión.
 * Las dos escrituras del acta devuelven este mismo {@code record}, así que publicarlos y dejarlos
 * nulos ahí sería el defecto de #194 —un campo declarado que en la mitad de sus rutas nunca se
 * llena—. Resolverlos cuesta <b>una</b> lectura por acta escrita, y el identificador ya viene
 * exigido en el cuerpo de las dos peticiones. La alternativa era partir el {@code record} en dos
 * —uno de lectura y otro de escritura—, y eso son dos formas del mismo acta para ahorrar una
 * consulta indexada por clave primaria.
 *
 * <p>Los dos son <b>anulables</b>, y nulo significa una cosa concreta: el contribuyente <b>ya no
 * está en el padrón</b>. El acta sigue saliendo, como la fila del omiso cuyo titular se dio de baja
 * — ocultarla escondería justamente el caso que hay que revisar.
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
 * @param contribuyente el nombre del obligado; {@code null} si ya no está en el padrón
 * @param codContribuyente el código con el que se le identifica en ventanilla; {@code null} por lo
 *     mismo
 * @param areaDeclarada la superficie que consigna la declaración jurada del ejercicio del programa
 *     —la versión de ficha que esa declaración referencia, no la inscrita el día de la visita
 *     (#344)—; {@code null} si no declaró o no hay de dónde saberlo
 * @param usoDeclarado el uso que consigna esa misma versión
 * @param diferenciaDeArea hallada menos declarada, nunca negativa; nula si falta un lado
 */
public record ActaFiscalizacionResource(
        long id,
        long programaId,
        int version,
        long contribuyenteId,
        @Nullable String contribuyente,
        @Nullable String codContribuyente,
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

    /**
     * El acta con su obligado ya resuelto.
     *
     * @param enElPadron lo que el directorio devolvió para {@code acta.contribuyenteId()}, o {@code
     *     null} si ese identificador no está en el padrón
     */
    public static ActaFiscalizacionResource de(
            ActaConLoDeclarado contraste, @Nullable ResumenDeContribuyente enElPadron) {
        ActaFiscalizacion acta = contraste.acta();
        return new ActaFiscalizacionResource(
                acta.id() == null ? 0L : acta.id(),
                acta.programaId(),
                acta.version(),
                acta.contribuyenteId(),
                enElPadron == null ? null : enElPadron.nombre(),
                enElPadron == null ? null : enElPadron.codigo(),
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
