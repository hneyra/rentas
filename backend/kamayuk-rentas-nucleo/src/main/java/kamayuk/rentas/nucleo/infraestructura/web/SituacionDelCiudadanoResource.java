package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.contribuyentes.ContribuyenteAcreditado;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDelCiudadano;
import kamayuk.rentas.nucleo.aplicacion.RamaDelCiudadano;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * {@code portal_mi_situacion}, tal como sale por HTTP (RF-131, #57, ADR-0020). Campos en español
 * {@code camelCase} (ARQ-04 §3).
 *
 * <h2>Lo que NO lleva, y es lo primero que hay que mirar</h2>
 *
 * <p><b>Ni el identificador de la municipalidad ni el del contribuyente.</b> El primero es la clave
 * del aislamiento; el segundo, el de la persona. La municipalidad se nombra por ubigeo y nombre; la
 * persona, por el codigo con el que esa municipalidad la identifica, que es el que figura en su
 * recibo.
 *
 * <p>El del <b>predio</b> si viaja, y desde #199 en las <b>dos</b> listas que hablan de el: {@code
 * obligaciones[].predioId} lo publicaba ya, y {@code predios[].predioId} es lo que permite cruzar
 * las dos. El motivo entero esta en {@link PredioDelPortal}.
 *
 * <p><b>Ningun copropietario.</b> Cada predio publica el porcentaje del <b>consultado</b> y nada
 * mas; la porcion que no le corresponde no se menciona (ADR-0019).
 *
 * <p><b>Nadie mas que el.</b> Ni el conyuge, ni la sucesion indivisa, ni la sociedad conyugal, ni
 * el RUC de la empresa que representa, ni las obligaciones donde figura como responsable solidario.
 *
 * <h2>Una sola fecha arriba, y la de cada cifra al lado de la cifra</h2>
 *
 * <p>{@code aLaFecha} de nivel superior es la fecha de corte del recorrido entero: la <b>misma</b>
 * para todas las municipalidades, que es lo que hace legitimo el total. Y cada importe viaja ademas
 * como {@link ImporteActualizado}, nunca como {@code Dinero} suelto (regla 9, RNF-075).
 *
 * <h2>El total, o el motivo de que no lo haya</h2>
 *
 * <p>{@code totalConsolidado} es {@code null} cuando alguna municipalidad no se pudo leer, y
 * entonces {@code notaDelTotal} dice cuales faltan. No es cero, y no se puede confundir con cero:
 * un total al que le falta una municipalidad es un importe plausible y equivocado.
 *
 * @param tipoDocumento el tipo del documento con que se pregunto, tal como lo trae el token
 * @param numeroDocumento su numero. Viaja <b>aparte</b> del tipo y no formateado junto a el porque
 *     la interfaz lo compara letra a letra con el claim de su propio token: es la guarda de vuelta
 *     —el proxy de datos no filtra (ADR-0010), y un fallo del servidor que compusiera la situacion
 *     de otra persona no se distinguiria de una correcta—
 * @param aLaFecha la fecha de corte de todo lo que hay aqui dentro
 * @param municipalidadesRecorridas cuantas municipalidades activas se visitaron
 * @param totalConsolidado la suma de los totales de todas, o {@code null} si falto alguna
 * @param notaDelTotal por que no hay total, redactado por el servidor; {@code null} si lo hay
 * @param sinRegistros si esta persona no figura en ninguna municipalidad del sistema
 * @param municipalidades una por cada municipalidad donde figura
 */
public record SituacionDelCiudadanoResource(
        String tipoDocumento,
        String numeroDocumento,
        String aLaFecha,
        int municipalidadesRecorridas,
        @Nullable ImporteActualizado totalConsolidado,
        @Nullable String notaDelTotal,
        boolean sinRegistros,
        List<EnLaMunicipalidad> municipalidades) {

    public static SituacionDelCiudadanoResource de(ConsultaDelCiudadano.Situacion situacion) {
        Optional<Dinero> total = situacion.totalConsolidado();
        return new SituacionDelCiudadanoResource(
                situacion.documento().tipo().name(),
                situacion.documento().numero(),
                situacion.aLaFecha().toString(),
                situacion.recorridas(),
                total.map(importe -> new ImporteActualizado(importe, situacion.aLaFecha()))
                        .orElse(null),
                total.isPresent() ? null : notaDe(situacion.noLeidas()),
                situacion.sinRegistros(),
                situacion.municipalidades().stream().map(EnLaMunicipalidad::de).toList());
    }

    /**
     * La frase que explica por que no hay total, redactada <b>aqui</b> y no en la interfaz.
     *
     * <p>Mismo criterio que {@code estadoDeLaConsulta} del resumen de saldos (RNF-083): dos
     * pantallas que la compongan por su cuenta acaban escribiendo dos frases distintas, y una de
     * las dos olvida decir cual falta.
     */
    private static String notaDe(List<String> noLeidas) {
        String cuales = String.join(", ", noLeidas);
        return noLeidas.size() == 1
                ? "No se pudo consultar " + cuales + ", asi que no se puede dar un total de todo."
                : "No se pudieron consultar estas municipalidades: "
                        + cuales
                        + ". Sin ellas no se puede dar un total de todo.";
    }

    /**
     * La situacion en una municipalidad.
     *
     * @param ubigeo el codigo de seis digitos con el que se la nombra fuera del sistema
     * @param nombre como se llama
     * @param codigoContribuyente el codigo con el que <b>esta</b> municipalidad identifica a la
     *     persona: el que figura en su recibo, y con el que preguntaria en ventanilla
     * @param nombreContribuyente su nombre o razon social tal como esta en ese padron
     * @param activo si sigue de alta en ese padron. Cuando es {@code false} la deuda se muestra
     *     igual: sobrevive a la baja, y ocultarla seria decirle que no debe nada
     * @param resumenDeSaldos las cinco cifras del resumen, sumadas por el servidor
     * @param obligaciones las obligaciones con saldo, sin paginar
     * @param predios los predios de los que es titular, con su porcentaje
     */
    public record EnLaMunicipalidad(
            String ubigeo,
            String nombre,
            String codigoContribuyente,
            String nombreContribuyente,
            boolean activo,
            ConsultaUnificadaResource.ResumenDeSaldos resumenDeSaldos,
            List<ConsultaUnificadaResource.ObligacionDeLaFicha> obligaciones,
            List<PredioDelPortal> predios) {

        static EnLaMunicipalidad de(ConsultaDelCiudadano.EnMunicipalidad enMunicipalidad) {
            RamaDelCiudadano.Situacion situacion = enMunicipalidad.situacion();
            ContribuyenteAcreditado contribuyente = situacion.contribuyente();
            return new EnLaMunicipalidad(
                    enMunicipalidad.ubigeo(),
                    enMunicipalidad.nombre(),
                    contribuyente.codigo(),
                    contribuyente.nombre(),
                    contribuyente.activo(),
                    // El MISMO tipo y el mismo calculo que la ficha 360° del back-office: es lo
                    // que impide que el portal y la ventanilla digan cifras distintas de la misma
                    // persona el mismo dia (#297, #298).
                    ConsultaUnificadaResource.ResumenDeSaldos.de(situacion.resumen()),
                    situacion.obligaciones().stream()
                            .map(ConsultaUnificadaResource.ObligacionDeLaFicha::de)
                            .toList(),
                    situacion.predios().stream().map(PredioDelPortal::de).toList());
        }
    }

    /**
     * Un predio del ciudadano.
     *
     * <h2>Con {@code predioId}, y la decision esta al reves que antes de #199</h2>
     *
     * <p>Hasta #199 este recurso no lo publicaba, y el motivo escrito era bueno: «el identificador
     * interno no le sirve de nada a quien mira su propia ficha, y publicarlo invitaria a usarlo
     * como parametro de otra llamada». Lo que no era cierto es que la respuesta lo cumpliera:
     * {@link ConsultaUnificadaResource.ObligacionDeLaFicha#predioId()} publica <b>ese mismo
     * identificador interno</b>, en el mismo cuerpo y para el mismo predio. Lo que habia no era «el
     * id no se publica», era <b>se publica en una lista y no en la otra</b>, que es justo la
     * combinacion que impide cruzarlas.
     *
     * <p>Y sin cruce, el ciudadano con <b>dos</b> predios —lo normal en cuanto hay una cochera o un
     * segundo piso independizado— ve dos listas y nada dice cual es de cual. Emparejar por orden de
     * aparicion o por tributo daria una respuesta plausible y equivocada: «S/ 412.00 del predio de
     * la Av. Grau» escrito sobre el predio de al lado. Eso no es una columna vacia, es <b>una cifra
     * de deuda puesta en el predio equivocado</b>, indistinguible de una correcta.
     *
     * <p><b>Se corrige por el lado del predio y no por el de la obligacion</b>, y de las dos
     * salidas que #199 admitia esta es la unica que no rompe nada: {@code ObligacionDeLaFicha} es
     * el <b>mismo tipo</b> que sirve la ficha 360° del back-office —{@code GET
     * /consultas/unificada}—, donde {@code predioId} es la llave con que el funcionario abre el
     * predio; quitarlo alli para arreglar el portal seria cambiar de sitio el hueco. Y el camino
     * contrario —que la obligacion publicara el codigo de referencia catastral— exigiria que {@code
     * cuentacorriente} conociera el catastro, que es la arista que ARQ-01 §4 prohibe.
     *
     * <p>{@code predioId} es el <b>mismo nombre y la misma llave</b> con que la obligacion
     * referencia el predio; el tipo es {@code long} y no {@code Long} porque aqui siempre hay
     * predio, mientras que alli es nulo en cuanto la obligacion es vehicular.
     *
     * <p>Lo que <b>sigue sin publicarse</b> es el identificador de la municipalidad y el del
     * contribuyente: esos si son la clave del aislamiento, y ninguna lista de esta respuesta los
     * nombra. Publicar el del predio no abre ninguna puerta que {@code obligaciones[]} no tuviera
     * abierta: bajo {@code /portal/**} el sujeto sale del token y no hay ni un parametro que
     * teclear (ver {@code PortalController}).
     *
     * <p>{@code porcentajeTitularidad} viaja como texto y no como numero (regla 1), igual que en
     * {@link PredioEncontradoResource}.
     *
     * @param predioId el identificador con el que {@code obligaciones[].predioId} lo referencia
     */
    public record PredioDelPortal(
            long predioId,
            String codigoReferenciaCatastral,
            String tipo,
            String direccion,
            String porcentajeTitularidad) {

        static PredioDelPortal de(PredioDelContribuyente predio) {
            return new PredioDelPortal(
                    predio.predioId(),
                    predio.codigoReferenciaCatastral(),
                    predio.tipo(),
                    predio.direccion(),
                    predio.porcentajeTitularidad().valor().toPlainString());
        }
    }
}
