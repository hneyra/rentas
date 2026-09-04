package kamayuk.rentas.catastro;

import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Lo que otro contexto necesita saber de un predio para determinar arbitrios (#31) y para
 * contrastar lo hallado con lo declarado (#49), publicado desde este contexto (ARQ-01 §4).
 *
 * <p>No es {@link kamayuk.rentas.catastro.dominio.Predio} ni {@link
 * kamayuk.rentas.catastro.dominio.FichaCatastral}: quien consulta no necesita las construcciones,
 * las instalaciones ni el estado del padron, solo estas tres claves.
 *
 * <p><b>{@code areaTerreno} entra con #49.</b> La fiscalizacion compara la superficie que consta
 * declarada con la que el fiscalizador midio, y esa comparacion no depende de ninguna norma: es
 * estructura. La alternativa era que {@code fiscalizacion} leyera {@code ficha_catastral}
 * directamente, cruzando el limite del contexto.
 *
 * <p><b>Ni un importe.</b> Cuanto vale el terreno sale del arancel y cuanto la construccion del
 * cuadro de valores unitarios; los dos son D-02a y no salen de aqui.
 *
 * @param uso el de la ficha {@code UNICA} vigente a la fecha consultada; {@code null} si el predio
 *     no tiene ficha vigente esa fecha
 * @param sectorCodigo el codigo del sector del predio; {@code null} si el predio no tiene sector
 *     asignado
 * @param areaTerreno el area de esa misma ficha; {@code null} si el predio no tiene ficha vigente
 *     esa fecha
 */
public record CaracteristicasDelPredio(
        @Nullable String uso, @Nullable String sectorCodigo, @Nullable AreaM2 areaTerreno) {}
