package kamayuk.rentas.valores.dobles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.valores.dominio.ValorRepository;

/**
 * Un {@link ValorRepository} en memoria, para las pruebas de los casos de uso de #39.
 *
 * <p>Existe como clase aparte y no como doble anonimo dentro de cada prueba porque los tres casos
 * de uso de #39 lo necesitan igual, y porque {@link #cobrablesDe} tiene reglas propias -filtra por
 * estado- que conviene escribir una vez.
 *
 * <p>Lo que este doble <b>no</b> puede verificar es lo que solo hace la base: la unicidad del
 * intento, el {@code ON CONFLICT} del pase o la ausencia del privilegio de {@code UPDATE}. Eso vive
 * en {@code NotificacionYPaseJdbcTest}, contra PostgreSQL de verdad.
 */
public final class ValoresEnMemoria implements ValorRepository {

    private final Map<Long, Valor> porId = new HashMap<>();
    private final Map<Long, List<ValorDetalle>> detalles = new HashMap<>();
    private long siguienteId = 1;

    /** Guarda un valor y devuelve el que quedo, con su identificador. */
    public Valor con(Valor valor, ValorDetalle... detalle) {
        long id = valor.id() == null ? siguienteId++ : valor.id();
        Valor conId =
                new Valor(
                        id,
                        valor.tipo(),
                        valor.numero(),
                        valor.ejercicio(),
                        valor.contribuyenteId(),
                        valor.baseLegal(),
                        valor.montoInsoluto(),
                        valor.montoReajuste(),
                        valor.montoInteres(),
                        valor.montoGasto(),
                        valor.proyectadoA(),
                        valor.estado(),
                        valor.fechaEmision(),
                        "prueba",
                        valor.observacion());
        porId.put(id, conId);
        detalles.put(id, List.of(detalle));
        return conId;
    }

    @Override
    public Valor insertar(Valor valor, List<ValorDetalle> detalle) {
        return con(valor, detalle.toArray(new ValorDetalle[0]));
    }

    @Override
    public Optional<Valor> porNumero(TipoValor tipo, Ejercicio ejercicio, String numero) {
        return porId.values().stream()
                .filter(v -> v.tipo() == tipo && v.numero().equals(numero))
                .findFirst();
    }

    @Override
    public Optional<Valor> porNumero(String numero) {
        return porId.values().stream().filter(v -> v.numero().equals(numero)).findFirst();
    }

    @Override
    public Optional<Valor> porId(long id) {
        return Optional.ofNullable(porId.get(id));
    }

    @Override
    public List<ValorDetalle> detalleDe(long valorId) {
        return detalles.getOrDefault(valorId, List.of());
    }

    @Override
    public Pagina<Valor> buscar(CriterioDeValor criterio, Paginacion paginacion) {
        List<Valor> todos = new ArrayList<>(porId.values());
        return Pagina.de(todos, paginacion, todos.size());
    }

    /**
     * La grilla de {@code consulta_valores}, sin cruzar notificaciones ni pases: este doble no los
     * tiene.
     *
     * <p>Lo que devuelve, por tanto, es la situacion de un valor <b>que nadie ha notificado</b>. Es
     * bastante para las pruebas del transporte —que la fila salga con su fecha, que el filtro por
     * codigo inexistente devuelva vacio—, y deliberadamente insuficiente para lo demas: que el
     * filtro por situacion coincida con la columna que se pinta solo lo puede decir la base, y lo
     * dice {@code NotificacionYPaseJdbcTest}.
     */
    @Override
    public Pagina<ValorEnConsulta> consultar(
            CriterioDeConsultaDeValores criterio, Paginacion paginacion) {
        List<ValorEnConsulta> filas = new ArrayList<>();
        for (Valor valor : porId.values()) {
            if (criterio.contribuyenteId() != null
                    && valor.contribuyenteId() != criterio.contribuyenteId()) {
                continue;
            }
            if (criterio.numero() != null && !valor.numero().equals(criterio.numero())) {
                continue;
            }
            if (criterio.tipo() != null && valor.tipo() != criterio.tipo()) {
                continue;
            }
            List<ValorDetalle> detalle = detalleDe(valor.id() == null ? 0 : valor.id());
            ValorEnConsulta fila =
                    new ValorEnConsulta(
                            valor,
                            detalle.isEmpty() ? null : detalle.get(0).tributo(),
                            detalle.isEmpty() ? null : detalle.get(0).ejercicio().valor(),
                            detalle.isEmpty() ? null : detalle.get(0).ejercicio().valor(),
                            null,
                            null,
                            false,
                            criterio.fecha());
            if (criterio.situacion() != null && fila.situacion() != criterio.situacion()) {
                continue;
            }
            filas.add(fila);
        }
        return Pagina.de(filas, paginacion, filas.size());
    }

    /** Cuenta lo mismo que {@link #consultar} filtra, reusandolo: aqui no hay dos criterios. */
    @Override
    public long contar(CriterioDeConsultaDeValores criterio) {
        return consultar(
                        criterio,
                        new Paginacion(
                                0, Integer.MAX_VALUE, "numero", Paginacion.Direccion.ASCENDENTE))
                .totalElementos();
    }

    @Override
    public List<Valor> cobrablesDe(long contribuyenteId, String tributo, Ejercicio ejercicio) {
        List<Valor> encontrados = new ArrayList<>();
        for (Valor valor : porId.values()) {
            if (valor.contribuyenteId() != contribuyenteId || !esCobrable(valor.estado())) {
                continue;
            }
            boolean coincide =
                    detalleDe(valor.id() == null ? 0 : valor.id()).stream()
                            .anyMatch(
                                    d ->
                                            d.tributo().equalsIgnoreCase(tributo)
                                                    && d.ejercicio().equals(ejercicio));
            if (coincide) {
                encontrados.add(valor);
            }
        }
        return encontrados;
    }

    @Override
    public Valor cambiarEstado(long valorId, EstadoDeValor nuevo) {
        Valor actual = porId.get(valorId);
        if (actual == null) {
            throw new IllegalArgumentException("No existe el valor " + valorId);
        }
        Valor cambiado =
                new Valor(
                        actual.id(),
                        actual.tipo(),
                        actual.numero(),
                        actual.ejercicio(),
                        actual.contribuyenteId(),
                        actual.baseLegal(),
                        actual.montoInsoluto(),
                        actual.montoReajuste(),
                        actual.montoInteres(),
                        actual.montoGasto(),
                        actual.proyectadoA(),
                        nuevo,
                        actual.fechaEmision(),
                        actual.usuarioRegistro(),
                        actual.observacion());
        porId.put(valorId, cambiado);
        return cambiado;
    }

    @Override
    public long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio) {
        return siguienteId++;
    }

    private static boolean esCobrable(EstadoDeValor estado) {
        return estado == EstadoDeValor.EMITIDO
                || estado == EstadoDeValor.NOTIFICADO
                || estado == EstadoDeValor.COACTIVA;
    }
}
