package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecibos;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import org.jspecify.annotations.Nullable;

/** Los recibos, en memoria. Solo agrega: no hay forma de editar uno, igual que en la base. */
public final class RecibosEnMemoria implements ReciboRepository {

    private final List<Recibo> emitidos = new ArrayList<>();
    private final Map<String, Recibo> porClave = new LinkedHashMap<>();
    private final Map<String, Long> correlativos = new LinkedHashMap<>();
    private long siguienteId = 1;

    private @Nullable CriterioDeRecibos ultimoCriterio;
    private @Nullable Paginacion ultimaPaginacion;

    public List<Recibo> emitidos() {
        return List.copyOf(emitidos);
    }

    /** El criterio con que se pidio el ultimo listado: es lo que la capa web compone. */
    public @Nullable CriterioDeRecibos ultimoCriterio() {
        return ultimoCriterio;
    }

    /** Y con que paginacion. */
    public @Nullable Paginacion ultimaPaginacion() {
        return ultimaPaginacion;
    }

    @Override
    public NumeroDeRecibo siguienteNumero(Caja caja) {
        long ultimo = correlativos.merge(caja.serie(), 1L, Long::sum);
        return caja.numero(ultimo);
    }

    @Override
    public Recibo emitir(Recibo recibo, @Nullable String claveDeIdempotencia) {
        Recibo guardado =
                new Recibo(
                        siguienteId++,
                        recibo.numero(),
                        recibo.cajaId(),
                        recibo.turnoId(),
                        recibo.cajero(),
                        recibo.contribuyenteId(),
                        recibo.emitidoEn(),
                        recibo.formaDePago(),
                        recibo.tipoDePago(),
                        recibo.campaniaBeneficio(),
                        recibo.actualizadoA(),
                        recibo.observacion(),
                        recibo.lineas());
        emitidos.add(guardado);
        if (claveDeIdempotencia != null) {
            porClave.put(claveDeIdempotencia, guardado);
        }
        return guardado;
    }

    @Override
    public Optional<Recibo> porClaveDeIdempotencia(String clave) {
        return Optional.ofNullable(porClave.get(clave));
    }

    @Override
    public Optional<Recibo> porNumero(NumeroDeRecibo numero) {
        return emitidos.stream().filter(r -> r.numero().equals(numero)).findFirst();
    }

    /**
     * Guarda lo que se pidio y devuelve una pagina vacia, igual que {@code ConveniosEnMemoria} y
     * por lo mismo: el filtrado —y el estado, que se DERIVA de {@code recibo_movimiento} (V30)— se
     * prueba contra PostgreSQL, y filtrar aqui en Java compararia dos derivaciones distintas sin
     * probar ninguna.
     *
     * <p>Lo que si conserva es el criterio, que es lo unico que la capa web decide: que los seis
     * filtros de la consulta lleguen al dominio, y que la pagina vacia salga como pagina vacia y no
     * como un 404.
     */
    @Override
    public Pagina<ReciboEnConsulta> buscar(CriterioDeRecibos criterio, Paginacion paginacion) {
        ultimoCriterio = criterio;
        ultimaPaginacion = paginacion;
        return Pagina.vacia(paginacion);
    }
}
