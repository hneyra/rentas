package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.dominio.Arancel;
import kamayuk.rentas.catastro.dominio.Depreciacion;
import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import kamayuk.rentas.catastro.dominio.ValuacionRepository;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga y consulta de aranceles, valores unitarios de edificacion y depreciacion (RF-009, #17).
 *
 * <h2>Consultar es traducir el ejercicio, una sola vez</h2>
 *
 * <p>Las tres consultas resuelven el ejercicio a un conjunto sellado a traves de {@link
 * LectorDeParametros#conjuntoVigenteEn}, y leen ese conjunto —nunca el ejercicio directamente—. Es
 * el mismo patron de dos pasos que {@code ValoresReferenciales} de {@code rentas}: si esta clase
 * consultara la tabla por ejercicio, un ejercicio con dos versiones selladas devolveria la vigente
 * hoy en vez de la que uso una determinacion concreta.
 *
 * <h2>Cargar es escribir sobre un conjunto que alguien mas resolvio, y solo el arancel</h2>
 *
 * <p>{@code cargarArancel} recibe el {@link IdentificadorDeConjunto} ya resuelto, no un ejercicio:
 * crear un conjunto de parametros, y sellarlo, es responsabilidad de {@code parametros} (ARQ-01
 * §3.2 —estas tablas describen el predio, no la obligacion, y no le corresponde a {@code catastro}
 * decidir cuando cierra un ejercicio—). Que la carga contra un conjunto ya sellado falle no lo
 * comprueba este servicio: lo hace el disparador de {@code V18} en la base, que no se puede rodear
 * con una carga concurrente entre la comprobacion y la escritura.
 *
 * <p><b>Los otros dos {@code cargar*} se retiraron con D-13</b> (ADR-0017, V55). No se movieron de
 * sitio ni se renombraron: dejaron de tener sentido. Cargaban una copia del cuadro nacional <b>para
 * una municipalidad</b>, y eso es exactamente lo que la decision prohibe —era el hallazgo H-5—. El
 * cuadro de valores unitarios y la tabla de depreciacion los publica ahora {@code
 * PublicarTablasDeValuacion}, un proceso de perfil {@code batch} que corre como {@code
 * rol_carga_parametros}, sin contexto de municipalidad porque no tiene ninguna que fijar. Las dos
 * consultas de aqui no cambiaron ni de firma ni de semantica.
 *
 * <h2>Corregir es cargar de nuevo, contra un conjunto nuevo</h2>
 *
 * <p>No hay un metodo {@code corregir}: corregir una cifra ya usada en una emision es exactamente
 * cargarla otra vez, contra un conjunto con una version mayor. El conjunto anterior queda intacto
 * —sus filas siguen siendo las que uso la determinacion que las leyo— y el nuevo, una vez sellado,
 * pasa a ser el que {@link LectorDeParametros#conjuntoVigenteEn} devuelve. Para los dos cuadros
 * nacionales la frase se lee igual una linea mas arriba: se publica otra edicion y el conjunto
 * nuevo compone esa.
 */
@Service
public class TablasDeValuacion {

    private final ValuacionRepository repositorio;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public TablasDeValuacion(
            ValuacionRepository repositorio,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.repositorio = repositorio;
        this.parametros = parametros;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    // ---------- Consulta: siempre por el conjunto vigente del ejercicio ----------

    @Transactional(readOnly = true)
    public List<Arancel> aranceles(Ejercicio ejercicio) {
        return repositorio.arancelesDe(parametros.conjuntoVigenteEn(ejercicio));
    }

    @Transactional(readOnly = true)
    public List<ValorUnitarioEdificacion> valoresUnitarios(Ejercicio ejercicio) {
        return repositorio.valoresUnitariosDe(parametros.conjuntoVigenteEn(ejercicio));
    }

    @Transactional(readOnly = true)
    public List<Depreciacion> depreciaciones(Ejercicio ejercicio) {
        return repositorio.depreciacionesDe(parametros.conjuntoVigenteEn(ejercicio));
    }

    // ---------- Carga: contra un conjunto que el llamador ya resolvio ----------

    @Transactional
    public Arancel cargarArancel(
            Arancel arancel, IdentificadorDeConjunto conjunto, Observacion observacion) {
        Arancel guardado = repositorio.guardarArancel(arancel, conjunto);
        auditar("arancel", guardado.id(), conjunto, observacion);
        return guardado;
    }

    private void auditar(
            String tabla,
            @Nullable Long id,
            IdentificadorDeConjunto conjunto,
            Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                tabla,
                                String.valueOf(id),
                                Operacion.ALTA,
                                observacion)
                        .con(null, "{\"conjuntoId\":" + conjunto.valor() + "}"));
    }
}
