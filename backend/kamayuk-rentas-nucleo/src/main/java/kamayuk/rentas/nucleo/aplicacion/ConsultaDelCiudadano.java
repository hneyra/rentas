package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import org.springframework.stereotype.Service;

/**
 * {@code portal_mi_situacion}: lo que el ciudadano debe y tiene <b>en todas las municipalidades del
 * sistema donde figure</b> (RF-131, #57, ADR-0020).
 *
 * <h2>La frase que hay que poder repetir</h2>
 *
 * <blockquote>
 * No es una consulta multi-municipalidad; son <i>N</i> consultas de una municipalidad cuya union se
 * filtra a un documento firmado.
 * </blockquote>
 *
 * <p>El sujeto no viaja en ningun parametro: sale de {@link CiudadanoContext}, que lo fijo el borde
 * de la aplicacion desde un claim del token del realm del ciudadano. Por eso este metodo no recibe
 * el documento —recibirlo abriria la puerta a que un dia se lo pasara alguien que lo tecleo—, y por
 * eso la operacion del contrato <b>no tiene ni un parametro</b>.
 *
 * <h2>Sin transaccion, y ese es el punto</h2>
 *
 * <p>Este anfitrion <b>no</b> abre transaccion; cada rama abre la suya dentro de {@link
 * RecorridoPorMunicipalidades}. Es la leccion de #54 leida al derecho y la de #72 repetida: una
 * rama que lance —{@code EjercicioSinSellar} es lo que ocurre <b>hoy</b> en todas las
 * municipalidades— marcaria la transaccion del anfitrion como <i>rollback-only</i> y la pantalla
 * entera reventaria con {@code UnexpectedRollbackException} por culpa de una municipalidad.
 *
 * <h2>Una sola fecha, y sin total si falta una rama</h2>
 *
 * <p>La fecha de corte se resuelve <b>una vez</b>, aqui, y se pasa igual a todas las ramas (regla
 * 9, RNF-075). Es lo que hace legitimo el total consolidado.
 *
 * <p>Y si alguna rama fallo, <b>no hay total</b>: se dice cuales faltan y por que no se puede
 * totalizar. Un total al que le falta una municipalidad es un importe plausible y equivocado, que
 * es la clase de error que este proyecto trata como el peor.
 *
 * <h2>Por que vive en {@code rentas}</h2>
 *
 * <p>Por lo mismo que {@code consulta_unificada} (#25) y que la conciliacion (#344, #366): es el
 * unico contexto que puede depender de {@code contribuyentes}, {@code cuentacorriente} y {@code
 * catastro} a la vez sin cerrar ningun ciclo, y consume a cada uno <b>solo por su API publica</b>.
 * Spring Modulith lo verifica.
 */
@Service
public class ConsultaDelCiudadano {

    private final RecorridoPorMunicipalidades recorrido;
    private final RamaDelCiudadano rama;
    private final Clock reloj;

    public ConsultaDelCiudadano(
            RecorridoPorMunicipalidades recorrido, RamaDelCiudadano rama, Clock reloj) {
        this.recorrido = recorrido;
        this.rama = rama;
        this.reloj = reloj;
    }

    /** La fecha de hoy, del reloj inyectado y no de {@code LocalDate.now()} (regla 6). */
    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /**
     * Recorre el registro y compone la situacion del ciudadano en curso.
     *
     * @param aLaFecha la fecha de corte, la misma para todas las ramas
     */
    public Situacion situacion(LocalDate aLaFecha) {
        Objects.requireNonNull(
                aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
        DocumentoIdentidad documento = CiudadanoContext.actual();

        // La rama no sabe en que municipalidad esta: aqui se le empareja el resultado con la
        // municipalidad de la que salio, que es lo unico que hace falta para etiquetarlo.
        RecorridoPorMunicipalidades.Resultado<EnMunicipalidad> recorrida =
                recorrido.recorrer(
                        municipalidad ->
                                rama.leer(aLaFecha)
                                        .map(
                                                situacion ->
                                                        new EnMunicipalidad(
                                                                municipalidad.ubigeo(),
                                                                municipalidad.nombre(),
                                                                situacion)));

        List<String> noLeidas = new ArrayList<>();
        for (RecorridoPorMunicipalidades.Fallo fallo : recorrida.fallidas()) {
            noLeidas.add(fallo.municipalidad().nombre());
        }

        return new Situacion(
                documento,
                aLaFecha,
                recorrida.leidas(),
                List.copyOf(noLeidas),
                recorrida.recorridas());
    }

    /**
     * La situacion del ciudadano, ya compuesta.
     *
     * @param documento con que documento se pregunto; sale del token, no de la peticion
     * @param aLaFecha la fecha de corte de <b>todo</b> lo que hay aqui dentro
     * @param municipalidades una entrada por municipalidad donde figura, en el orden del recorrido
     * @param noLeidas las municipalidades que no se pudieron leer. Mientras no este vacia, no hay
     *     total consolidado
     * @param recorridas cuantas municipalidades activas se visitaron
     */
    public record Situacion(
            DocumentoIdentidad documento,
            LocalDate aLaFecha,
            List<EnMunicipalidad> municipalidades,
            List<String> noLeidas,
            int recorridas) {

        public Situacion {
            Objects.requireNonNull(documento, "La situacion es de un documento acreditado");
            Objects.requireNonNull(
                    aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
            municipalidades = List.copyOf(municipalidades);
            noLeidas = List.copyOf(noLeidas);
        }

        /**
         * El total consolidado, <b>o nada</b>.
         *
         * <p>Nada cuando alguna rama fallo: no es que valga cero, es que no se puede decir. Quien
         * dibuja tiene que distinguir las dos cosas, y por eso esto es un {@link Optional} y no un
         * {@link Dinero} que a veces esta incompleto.
         *
         * <p>Se suma sobre los totales que cada rama ya calculo con la misma fecha de corte, asi
         * que no mezcla instantes. Y se suma <b>aqui</b>, en el servidor: la interfaz no compone
         * ninguna cifra (RNF-083).
         */
        public Optional<Dinero> totalConsolidado() {
            if (!noLeidas.isEmpty()) {
                return Optional.empty();
            }
            Dinero total = Dinero.CERO;
            for (EnMunicipalidad municipalidad : municipalidades) {
                total = total.mas(municipalidad.situacion().resumen().total());
            }
            return Optional.of(total);
        }

        /** Si esta persona no figura en ninguna municipalidad activa del sistema. */
        public boolean sinRegistros() {
            return municipalidades.isEmpty();
        }
    }

    /**
     * Lo que la rama leyo, con la municipalidad de la que salio.
     *
     * <p>La municipalidad se nombra por su <b>ubigeo y su nombre</b>, nunca por su identificador
     * interno: ese es la clave del aislamiento, y publicarselo al ciudadano seria darle el numero
     * que ninguna peticion suya debe poder nombrar.
     */
    public record EnMunicipalidad(
            String ubigeo, String nombre, RamaDelCiudadano.Situacion situacion) {

        public EnMunicipalidad {
            Objects.requireNonNull(ubigeo, "La municipalidad se identifica por su ubigeo");
            Objects.requireNonNull(nombre, "La municipalidad se identifica por su nombre");
            Objects.requireNonNull(
                    situacion, "Una municipalidad sin datos no entra en la respuesta");
        }
    }
}
