package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Optional;

/**
 * La version de la ficha catastral vigente en una fecha, publicada para otros contextos acotados
 * (ARQ-01 §2 —{@code catastro ──► rentas}—, #28).
 *
 * <p>Es la API publica de este modulo: vive en el paquete raiz, no en {@code .dominio}, porque
 * Spring Modulith trata como interno todo lo que esta en un subpaquete (mismo patron que {@code
 * kamayuk.rentas.parametros.LectorDeParametros}). Quien consume esto no necesita saber que es una
 * {@code FichaCatastral} completa —construcciones, instalaciones, detalle por tipo—: le basta el
 * identificador, para guardarlo como referencia.
 *
 * <p>Devuelve el identificador y no la ficha entera a proposito: una declaracion jurada no necesita
 * el area ni las categorias constructivas, y traerlas aqui obligaria a este modulo a exponer todo
 * {@link kamayuk.rentas.catastro.dominio.FichaCatastral} como API publica.
 */
public interface LectorDeFichas {

    /**
     * La version de la ficha {@code UNICA} de un predio que regia en esa fecha, si el predio tiene
     * alguna. Es la lectura de la reproducibilidad: una declaracion jurada de 2024 reimpresa en
     * 2030 tiene que seguir enlazada a la ficha que regia en 2024, no a la actual.
     */
    Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha);

    /**
     * El area de terreno de <b>esa version</b> de ficha, por su identificador (#49, RF-055).
     *
     * <p>No «el area del predio»: la de la version concreta que otro contexto guardo. Es lo que
     * permite comparar lo que el contribuyente declaro —su declaracion jurada referencia la ficha
     * que la sustentaba, desde #28— contra lo que el catastro tiene inscrito hoy, que es
     * exactamente la subvaluacion por ampliacion no declarada.
     *
     * <p>Devuelve el area y no la ficha entera por lo mismo que {@link #fichaVigenteEn} devuelve el
     * identificador: quien compara superficies no necesita las construcciones ni las instalaciones,
     * y traerlas obligaria a este modulo a exponer {@code FichaCatastral} completa.
     *
     * <p>Vacio si esa version no existe o es de otra municipalidad.
     */
    Optional<kamayuk.rentas.dominio.AreaM2> areaDeLaVersion(long fichaId);
}
