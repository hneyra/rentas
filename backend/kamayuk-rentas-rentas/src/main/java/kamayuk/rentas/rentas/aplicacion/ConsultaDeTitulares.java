package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El titular de un predio, resuelto al clic: quien es, con su codigo del padron (#366, ADR-0015
 * §2.4).
 *
 * <h2>Que resuelve, y por que no lo resuelve un listado</h2>
 *
 * <p>La fila de la consulta de fichas muestra el <b>nombre</b> del titular y nada mas, asi que no
 * se puede enlazar con su ficha de contribuyente y el operador salta al padron a buscar por nombre,
 * con la homonimia que eso invita (#322). Anadir el codigo a la grilla lo arreglaria y ademas
 * convertiria «quien puede listar fichas» en «quien puede cosechar la correlacion predio→persona de
 * toda la municipalidad», paginada y ordenable. Por eso la resolucion es <b>puntual</b>: un predio
 * cada vez, con el permiso del padron y dejando rastro. Es la opcion (b) de #366.
 *
 * <h2>Por que vive en {@code rentas}</h2>
 *
 * <p>Porque el dato esta repartido y ninguno de sus dos duenos puede juntarlo: la titularidad es de
 * {@code catastro} (#19) y el codigo del contribuyente es de {@code contribuyentes}. Alojarlo en
 * {@code contribuyentes} —que es donde el issue lo pedia— cerraria un ciclo, porque {@code
 * catastro} ya depende de {@code contribuyentes} para resolver el nombre de sus titulares y {@code
 * contribuyentes} no depende de nadie (ARQ-01 §2, §3.1). {@code rentas} es el unico que puede
 * depender de los dos, que es el mismo motivo por el que aloja {@code ConsultaPrediosController} y
 * {@code ConsultaDeConciliacion}.
 *
 * <p>Y alojarlo en {@code catastro} —que si podria, porque ya depende del padron— publicaria el
 * codigo del contribuyente en una respuesta de catastro, que es exactamente lo que ADR-0015 §2.4
 * separa como decision aparte. Los dos lados entran por puerto publico y solo por ahi: {@link
 * TitularesDelPredio} y {@link DirectorioDeContribuyentes}. Este caso de uso no lee ni una tabla
 * ajena (ARQ-01 §4).
 *
 * <h2>El acceso y el rastro</h2>
 *
 * <p>El acceso lo comprueba el controlador —{@code contribuyentes}, el del padron, no el de la
 * pantalla desde la que se hace clic— y la fila de {@code ACCESO} se escribe aqui, dentro de <b>la
 * misma transaccion</b> que la lectura: si la resolucion falla no queda constancia de algo que no
 * paso, y si la constancia no se puede escribir la resolucion no se responde. Es el precedente
 * exacto de {@code ConsultaDeConciliacion.noConciliadas} (ADR-0015 §2.3, #344).
 */
@Service
public class ConsultaDeTitulares {

    /**
     * La tabla que se anota en la bitacora.
     *
     * <p>Es {@code titularidad} y no {@code contribuyente}: lo que esta consulta atraviesa es la
     * correlacion predio→persona, y quien audite «quien ha estado cruzando el padron de predios con
     * el de personas» tiene que encontrarlo aqui. El codigo que sale es la consecuencia; la
     * relacion es el dato.
     */
    private static final String TABLA_AUDITADA = "titularidad";

    private final TitularesDelPredio titulares;
    private final DirectorioDeContribuyentes padron;
    private final Auditoria auditoria;
    private final Clock reloj;

    public ConsultaDeTitulares(
            TitularesDelPredio titulares,
            DirectorioDeContribuyentes padron,
            Auditoria auditoria,
            Clock reloj) {
        this.titulares = titulares;
        this.padron = padron;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Los titulares del predio vigentes a esa fecha, con su codigo del padron, y su fila de {@code
     * ACCESO} en la bitacora.
     *
     * <p><b>La fecha entra y sale</b> (regla 9, RNF-075): no existe «el titular», existe el titular
     * vigente a una fecha, y la respuesta dice a cual contesta. Resolver «el ultimo» en vez del
     * vigente es el defecto que la ficha del contribuyente (#24) ya pago con los domicilios: una
     * notificacion de marzo saldria con la direccion de setiembre.
     *
     * <p><b>Son varios.</b> Dos conyuges al 50 %, una sucesion, un condominio: la respuesta es la
     * lista de cuotas vigentes, no «el» titular.
     *
     * <p>Un titular cuyo contribuyente no esta en el padron sale igual, con el codigo y el nombre
     * vacios. Ocultarlo escondería justamente el caso que catastro tiene que revisar, que es el
     * mismo criterio que {@link DirectorioDeContribuyentes#porIds} documenta para la grilla.
     *
     * <p>La observacion la compone el sistema y no el usuario, porque aqui no hay usuario que
     * observe: nadie escribe un motivo para mirar quien es el titular de un predio. Es la excepcion
     * que {@code ConObservacionEnLasEscrituras.SIN_USUARIO_QUE_OBSERVE} nombra con este metodo y su
     * porque.
     */
    @Transactional
    public TitularesResueltos resolver(long predioId, LocalDate vigenteA) {
        Objects.requireNonNull(vigenteA, "De quien es el predio se pregunta a una fecha (regla 9)");

        List<TitularDelPredio> cuotas = titulares.de(predioId, vigenteA);

        Set<Long> ids = new LinkedHashSet<>();
        for (TitularDelPredio cuota : cuotas) {
            ids.add(cuota.contribuyenteId());
        }
        // Una sola lectura del padron, no una por cuota.
        Map<Long, ResumenDeContribuyente> resumenes = padron.porIds(ids);

        List<TitularResuelto> resueltos = new ArrayList<>();
        for (TitularDelPredio cuota : cuotas) {
            ResumenDeContribuyente resumen = resumenes.get(cuota.contribuyenteId());
            resueltos.add(
                    new TitularResuelto(
                            resumen == null ? null : resumen.codigo(),
                            resumen == null ? null : resumen.nombre(),
                            cuota.condicion(),
                            cuota.porcentaje()));
        }

        registrarElAcceso(predioId, vigenteA, resueltos.size());
        return new TitularesResueltos(predioId, vigenteA, List.copyOf(resueltos));
    }

    // ------------------------------------------------------------------

    /**
     * La fila de la bitacora, en la misma transaccion que la lectura.
     *
     * <p>Se escribe <b>tambien cuando no sale ningun titular</b>: quien va probando identificadores
     * de predio para levantar el mapa del padron deja su nombre en cada intento, y los que no
     * devuelven nada son precisamente los que un auditor querria contar.
     */
    private void registrarElAcceso(long predioId, LocalDate vigenteA, int cuantos) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                // Del reloj inyectado, no de vigenteA: la particion de la bitacora
                                // es el ejercicio del ACTO, y preguntar en 2026 por el titular de
                                // 2024 es un acto de 2026.
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                "predio=" + predioId + ";vigenteA=" + vigenteA,
                                Operacion.ACCESO,
                                Observacion.de(
                                        "Resolucion del titular del predio "
                                                + predioId
                                                + " vigente al "
                                                + vigenteA
                                                + " contra el padron de contribuyentes (#366,"
                                                + " ADR-0015 §2.4)"))
                        // Solo cifras y la fecha: aqui no entra texto del usuario, asi que no hay
                        // comilla que pueda romper el cast a jsonb.
                        .con(
                                null,
                                "{\"predioId\":"
                                        + predioId
                                        + ",\"vigenteA\":\""
                                        + vigenteA
                                        + "\",\"titulares\":"
                                        + cuantos
                                        + "}"));
    }

    /**
     * Los titulares de un predio a una fecha.
     *
     * @param vigenteA a que fecha contesta, siempre presente: la titularidad de marzo no es la de
     *     setiembre (regla 9, RNF-075)
     */
    public record TitularesResueltos(
            long predioId, LocalDate vigenteA, List<TitularResuelto> titulares) {

        public TitularesResueltos {
            Objects.requireNonNull(
                    vigenteA, "No hay «el titular»: hay el titular vigente a una fecha");
            Objects.requireNonNull(titulares, "La resolucion devuelve la lista, aunque este vacia");
        }
    }

    /**
     * Un titular con lo justo para poder ir a su ficha y saber cuanto le corresponde.
     *
     * <p><b>El codigo, no el identificador interno.</b> Es con lo que se entra a la ficha del
     * contribuyente y es lo que el operador puede leer en voz alta en ventanilla; el identificador
     * de fila no le sirve a nadie fuera de la base.
     *
     * <p><b>Y no viaja el documento.</b> El problema que este endpoint resuelve es la homonimia, y
     * con el codigo se llega a la persona exacta sin necesidad de compararla por DNI. El documento
     * esta a un clic, detras del mismo permiso, en su ficha.
     *
     * @param codigo el codigo del padron; nulo si el titular ya no esta en el
     * @param nombre nombre o razon social; nulo por el mismo motivo
     */
    public record TitularResuelto(
            @Nullable String codigo,
            @Nullable String nombre,
            String condicion,
            Porcentaje porcentaje) {

        public TitularResuelto {
            Objects.requireNonNull(condicion, "La cuota necesita la condicion del titular");
            Objects.requireNonNull(porcentaje, "La cuota necesita su porcentaje");
        }
    }
}
