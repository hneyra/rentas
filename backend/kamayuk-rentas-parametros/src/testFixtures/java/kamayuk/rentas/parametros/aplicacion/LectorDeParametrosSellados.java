package kamayuk.rentas.parametros.aplicacion;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.ConjuntoVigente;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.CacheDelEscenario;
import kamayuk.rentas.parametros.infraestructura.NormativaDePrueba;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>FIXTURE DE PRUEBA</b>: el lector <b>de produccion</b>, sobre el escenario de la prueba.
 *
 * <p>No es un doble del lector: lo que hay debajo es {@link LectorDeParametrosCacheados}, la clase
 * que corre en ventanilla, con su resolucion de vigencias (#659) y su reparto entre «lo vigente» y
 * «el conjunto que la determinacion guardo». Lo unico sustituido es el <b>almacen</b>: las tablas
 * {@code _de_prueba} en vez de la cache de `V3`, porque veinte clases de prueba construyen su
 * lector a mano y ahi no hay gestor de transacciones que pueda abrir la que la descarga necesita.
 *
 * <p>Conserva el nombre que tenia el lector antes de P5B para no reescribir esas veinte clases, que
 * siguen diciendo lo mismo que decian: «dado un conjunto sellado con estos valores, calcula».
 *
 * <h2>Por que ESTE si abre transaccion, y el de produccion ya no (#450)</h2>
 *
 * <p>Desde #450 {@link LectorDeParametrosCacheados} no abre ninguna: pregunta a {@code normativa}
 * fuera de ella y deja que {@link CopiaLocalDeNormativa} abra la suya para leer la copia. Aqui la
 * «normativa» es {@link NormativaDePrueba}, que <b>tambien es una tabla</b> bajo el contexto de
 * municipalidad, y la copia no la envuelve ningun proxy —se construye a mano, como el resto—. Asi
 * que el fixture recupera en sus lecturas la transaccion que el lector tenia: sin red de por medio
 * no hay conexion que retener mientras se espera, y quien lo envuelve en un proxy obtiene lo mismo
 * que obtenia antes de #450.
 *
 * <p><b>Y la quinta, {@link #vigenteConSuConjunto} (#361), tambien se sobrescribe aunque solo llame
 * al metodo por omision del puerto</b>: el proxy solo ve el metodo que se invoca, y las dos
 * lecturas que el metodo por omision hace por dentro —{@code conjuntoVigenteEn} y {@code
 * porConjunto}— van al objeto y no al proxy. Sin la anotacion aqui corren sin transaccion, sin
 * {@code SET LOCAL}, y RLS no rechaza nada: <b>esconde</b> las filas, y la lectura contesta {@code
 * EjercicioSinSellar} sobre un ejercicio que si esta sellado. Medido al quitarla: 6 de las 8 de
 * {@code RegistrarDeterminacionPredialTest} en rojo con ese mensaje.
 */
public class LectorDeParametrosSellados extends LectorDeParametrosCacheados {

    public LectorDeParametrosSellados(ParametrosRepositoryJdbc repositorio) {
        this(
                new CopiaLocalDeNormativa(new CacheDelEscenario(repositorio.jdbc())),
                new NormativaDePrueba(repositorio.jdbc()));
    }

    private LectorDeParametrosSellados(CopiaLocalDeNormativa copia, NormativaDePrueba normativa) {
        super(copia, normativa, new DescargaDeNormativa(copia, normativa));
    }

    @Override
    @Transactional(readOnly = true)
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        return super.vigenteEn(ejercicio);
    }

    @Override
    @Transactional(readOnly = true)
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        return super.conjuntoVigenteEn(ejercicio);
    }

    @Override
    @Transactional(readOnly = true)
    public ConjuntoVigente vigenteConSuConjunto(Ejercicio ejercicio) {
        return super.vigenteConSuConjunto(ejercicio);
    }

    @Override
    @Transactional(readOnly = true)
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        return super.porConjunto(identificador);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConjuntoYaDescargado> loQueYaEstaDescargado(Ejercicio ejercicio) {
        return super.loQueYaEstaDescargado(ejercicio);
    }
}
