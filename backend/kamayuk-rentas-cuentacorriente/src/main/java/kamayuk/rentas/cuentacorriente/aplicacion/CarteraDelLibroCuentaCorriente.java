package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CargoDeUnTributo;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.PendienteDeUnTributo;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CargoAgregado;
import kamayuk.rentas.cuentacorriente.dominio.PendienteAgregado;
import kamayuk.rentas.dominio.Ejercicio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link CarteraDelLibro} sobre el libro (#56, #639, RF-130).
 *
 * <h2>Las dos mitades leen del libro, y desde #639 tambien la segunda</h2>
 *
 * <p>Lo cargado son los cargos de insoluto del ejercicio; lo pendiente es lo mismo neteado contra
 * sus abonos <b>hasta la fecha de corte</b>. Hasta #639 lo pendiente salia de {@code
 * saldo_proyectado} —el cache de #23—, y se cambio porque esa tabla <b>no puede aplicar una fecha
 * de corte</b>: netea el insoluto de la obligacion entera y no tiene ninguna columna con la fecha
 * valor de sus asientos. El sintoma era el de #639: el panel decia 13 783,75 de cartera donde la
 * suma de {@code GET /consultas/deuda} sobre los mismos contribuyentes daba 11 342,20, y los 2
 * 441,55 de diferencia eran <b>la cuota que aun no vence</b>.
 *
 * <h2>Y lo que costo se midio antes de cambiarlo</h2>
 *
 * <p>El motivo escrito en #56 para no leer el libro era el coste: «recorrer el libro entero de un
 * ejercicio en cada carga de la pantalla de inicio». Se midio antes de derogarlo, y la medida
 * completa —con su plan, sus paginas y sus mutaciones— vive en {@code CarteraEnElPlanJdbcTest}:
 * sobre <b>dos</b> padrones del tamano de Catacaos en la misma instalacion (210 210 asientos en la
 * particion de 2026), como {@code sgtm_app} y con RLS activa, la cartera sobre {@code
 * saldo_proyectado} tardaba <b>76,4 ms</b> y tocaba <b>3 944</b> paginas; sobre el libro tarda
 * <b>178,1 ms</b> y toca <b>4 166</b>. Y {@link #cargadoPorTributo}, que el panel <b>ya</b> paga en
 * cada carga sobre esa misma particion, tarda 127,9 ms y toca <b>4 210</b>: la consulta nueva toca
 * <b>menos</b> paginas que la que ya se pagaba, asi que no estrena una clase de coste.
 *
 * <p><b>Y el aislamiento efectivo de la lectura mejora, que no se esperaba.</b> {@code
 * saldo_proyectado} no esta particionada y no tiene indice por {@code municipalidad_id}, asi que la
 * consulta vieja recorria la tabla entera y descartaba <b>63 310</b> filas de la municipalidad
 * vecina y de otros ejercicios; la nueva lee <b>cero</b> filas ajenas, porque el mapa de bits sobre
 * {@code municipalidad_id} las excluye antes de tocar el heap y la particion poda el ejercicio.
 *
 * <p>Lo unico que sale mas caro es CPU y no E/S —el agregado por obligacion desborda a disco con el
 * {@code work_mem} por omision, y con 32 MB baja a 157,8 ms—, y <b>ningun indice lo arregla</b>: se
 * midio uno de cobertura, ocupa 18 MB sobre una tabla de 51 y el planificador no lo usa ni una vez.
 * Lo que se gana a cambio es que no hay dos definiciones de «lo pendiente» que puedan divergir.
 *
 * <p>{@code readOnly = true} y ni un bloqueo, por lo mismo que en {@link
 * RecaudacionDelLibroCuentaCorriente}: un panel se mira mientras la ventanilla cobra. Sin
 * transaccion no hay {@code SET LOCAL}, y sin el la politica RLS no puede evaluar {@code
 * current_setting('app.municipalidad_id')} —la consulta <b>falla</b>—.
 *
 * <p><b>No interpreta nada.</b> No sabe que {@code PREDIAL} sea el impuesto predial ni que {@code
 * MULTA_TRANSITO} sea una papeleta: devuelve lo que el libro dice de cada nombre de tributo.
 */
@Service
public class CarteraDelLibroCuentaCorriente implements CarteraDelLibro {

    private final AsientoRepository asientos;

    public CarteraDelLibroCuentaCorriente(AsientoRepository asientos) {
        this.asientos = asientos;
    }

    @Override
    @Transactional(readOnly = true)
    public CargadoEnElLibro cargadoPorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(ejercicio, "Lo cargado siempre es de un ejercicio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        List<CargoDeUnTributo> lineas =
                asientos.cargadoPorTributo(ejercicio).stream()
                        .map(CarteraDelLibroCuentaCorriente::aPublico)
                        .toList();
        return new CargadoEnElLibro(lineas, ejercicio, aLaFecha);
    }

    @Override
    @Transactional(readOnly = true)
    public CarteraPendiente pendientePorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(ejercicio, "La cartera siempre es de un ejercicio");
        Objects.requireNonNull(aLaFecha, "La cartera es a una fecha de corte (regla 9, #639)");
        List<PendienteDeUnTributo> lineas =
                asientos.pendientePorTributo(ejercicio, aLaFecha).stream()
                        .map(CarteraDelLibroCuentaCorriente::aPublico)
                        .toList();
        return new CarteraPendiente(lineas, ejercicio, aLaFecha);
    }

    private static CargoDeUnTributo aPublico(CargoAgregado agregado) {
        return new CargoDeUnTributo(agregado.tributo(), agregado.cargado(), agregado.cargos());
    }

    private static PendienteDeUnTributo aPublico(PendienteAgregado agregado) {
        return new PendienteDeUnTributo(
                agregado.tributo(), agregado.pendiente(), agregado.obligaciones());
    }
}
