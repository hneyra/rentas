package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de «Omisos y subvaluadores» tal como sale por HTTP ({@code fisc_omisos}, RF-055).
 *
 * <h2>El titular es el nombre, y su código va aparte (#545)</h2>
 *
 * <p>La columna de la pantalla se llama «Titular» y hasta #545 enseñaba {@code C-000001}. Resolver
 * el nombre desde el cliente cuesta una petición por fila, y esta lectura ya cruza catastro con
 * rentas: el nombre lo tiene delante. Se publican los dos, como hace {@code
 * TitularesDelPredioResource} y por el mismo motivo.
 *
 * <p><b>Y son varios, no uno.</b> La fila es el predio (#545), así que un predio con dos cónyuges
 * al 50 % es <b>una</b> fila con dos titulares y no dos filas iguales. {@code titulares} los lleva
 * todos; {@code titular} es lo que la columna dibuja —los nombres unidos— y {@code
 * codigoDelTitular} sale sólo cuando hay exactamente uno, porque con dos no hay <b>un</b> código y
 * elegir el de uno de los dos sería decir que el predio es suyo.
 *
 * <p>Los tres van en {@code null} —y {@code titulares} vacía— cuando el predio no tiene titular
 * vigente a la fecha de corte. Sale así y sale en la lista: es el predio que nadie reclama, el
 * primero que hay que fiscalizar, y ocultarlo escondía justo el caso que se busca.
 *
 * <h2>Las cuatro columnas de importe salen con nombre y sin cifra</h2>
 *
 * <p>«Valor catastral S/», «Valor declarado S/», «Diferencia S/» e «Impuesto omitido S/» dependen
 * del cuadro de valores unitarios, la tabla de depreciación y el arancel: <b>D-02a</b>, sin firmar
 * (#198). Viajan en {@code null} y la interfaz escribe «sin cifra». Ponerles un número supuesto
 * produciría una esquela de cobranza sobre un valor inventado.
 *
 * <p>Lo que sí viaja con valor es la comparación de superficies, que es estructura, y viaja como
 * {@link AreaM2}: el serializador que {@code ConfiguracionDeJson} registra la escribe {@code
 * "180.50"}, sin unidad. Hasta #546 estos tres campos eran {@code String} compuestos con {@code
 * toString()}, así que salían {@code "180.50 m2"} aquí y en la muestra, y {@code "180.50"} en la
 * liquidación y en la resolución — cuatro proyecciones del mismo módulo con dos formas del mismo
 * dato, y el serializador registrado sin intervenir en ninguna porque los cuatro campos ya eran
 * texto. <b>La unidad la pone la cabecera de la columna, no el dato</b>: metida dentro obliga a
 * cada consumidor a recortarla antes de poder comparar.
 *
 * <p>{@code declaroFueraDePlazo} viaja aparte de {@code condicion} <b>a propósito</b> (AC 3): quien
 * declaró tarde no es omiso, y la pantalla tiene que poder decir las dos cosas sin mezclarlas.
 *
 * @param codRefCatastral el código con el que se identifica el predio en ventanilla
 * @param titular el nombre del titular, o los nombres unidos si son varios; {@code null} si el
 *     predio no tiene ninguno vigente
 * @param codigoDelTitular el código del titular cuando hay exactamente uno; {@code null} si hay
 *     varios o ninguno
 * @param titulares todos los titulares vigentes, de mayor a menor porcentaje
 * @param sector el sector del predio
 * @param condicion CONFORME, OMISO, SUBVALUADOR, USO_DISTINTO o NO_UBICADO
 * @param declaroFueraDePlazo si presentó su declaración vencido el plazo
 * @param areaCatastral el área de la ficha vigente
 * @param areaDeclarada el área de la ficha que la declaración referencia
 * @param diferenciaDeArea la diferencia, nunca negativa
 * @param valorCatastralS siempre {@code null} hasta D-02a
 * @param valorDeclaradoS siempre {@code null} hasta D-02a
 * @param diferenciaS siempre {@code null} hasta D-02a
 * @param impuestoOmitidoS siempre {@code null} hasta D-02a
 */
public record OmisoResource(
        String codRefCatastral,
        @Nullable String titular,
        @Nullable String codigoDelTitular,
        List<TitularDelOmisoResource> titulares,
        @Nullable String sector,
        String condicion,
        boolean declaroFueraDePlazo,
        @Nullable AreaM2 areaCatastral,
        @Nullable AreaM2 areaDeclarada,
        @Nullable AreaM2 diferenciaDeArea,
        @Nullable String valorCatastralS,
        @Nullable String valorDeclaradoS,
        @Nullable String diferenciaS,
        @Nullable String impuestoOmitidoS) {

    /** Cómo se unen los nombres cuando el predio tiene más de un titular. */
    private static final String UNION = " y ";

    /**
     * Un titular de la fila: su código y su nombre.
     *
     * <p>Ni el identificador interno, ni el porcentaje, ni en qué calidad lo es. El porcentaje no
     * viaja porque esta pantalla no lo dibuja y publicarlo invitaría a sumarlo: los vigentes no
     * exceden 100 pero tampoco tienen que sumarlo (DAT-01 §4.2).
     *
     * <p>{@code codigo} y {@code nombre} nulos significan que el titular <b>ya no está en el
     * padrón</b>. Sale así, y sale en la lista, igual que en {@code TitularesDelPredioResource}: es
     * el predio que catastro tiene que revisar, y ocultarlo escondería el defecto en vez de
     * enseñarlo.
     */
    public record TitularDelOmisoResource(@Nullable String codigo, @Nullable String nombre) {}

    public static OmisoResource de(FilaDeOmisos fila, Map<Long, ResumenDeContribuyente> padron) {
        List<TitularDelOmisoResource> resueltos = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        for (Long contribuyenteId : fila.titulares()) {
            ResumenDeContribuyente enElPadron = padron.get(contribuyenteId);
            resueltos.add(
                    new TitularDelOmisoResource(
                            enElPadron == null ? null : enElPadron.codigo(),
                            enElPadron == null ? null : enElPadron.nombre()));
            if (enElPadron != null) {
                nombres.add(enElPadron.nombre());
            }
        }

        return new OmisoResource(
                fila.codigoReferenciaCatastral(),
                nombres.isEmpty() ? null : String.join(UNION, nombres),
                resueltos.size() == 1 ? resueltos.get(0).codigo() : null,
                List.copyOf(resueltos),
                fila.sectorCodigo(),
                fila.condicion().name(),
                fila.declaroFueraDePlazo(),
                fila.areaCatastral(),
                fila.areaDeclarada(),
                fila.diferenciaDeArea(),
                texto(fila.valorCatastral()),
                texto(fila.valorDeclarado()),
                null,
                texto(fila.impuestoOmitido()));
    }

    private static @Nullable String texto(@Nullable Object valor) {
        return valor == null ? null : valor.toString();
    }
}
