package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.DeteccionDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDePrograma;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelProgramaRepository;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResultadoDelSorteo;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El sorteo de la muestra (#481, AC 2 de #431), sin base de datos.
 *
 * <p>La detección <b>no se reimplementa aquí</b>: el sorteo llama a {@link DeteccionDeOmisos}, que
 * es la única fuente de la condición en el sistema y la misma que dibuja {@code fisc_omisos}. Lo
 * que estas pruebas miden es lo que el sorteo añade — las dos exclusiones, la guarda de los
 * parámetros y la de sortear dos veces.
 *
 * <p><b>Y desde #586, el reparto entero.</b> El predio sin titular vigente entra —era el 34,5 % del
 * padrón de Catacaos, y apartarlo escondía al candidato de primer orden—, y lo que sí se excluye se
 * cuenta por motivo, de modo que {@code detectados = sorteados + excluidos}.
 */
@DisplayName("#481 — Generar la muestra de un programa")
class GenerarMuestraTest {

    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T09:00:00Z"), ZoneOffset.UTC);
    private static final Observacion OBSERVACION = Observacion.de("Se sortea la muestra anual");

    private static final long OMISO_UNO = 21L;
    private static final long OMISO_DOS = 22L;
    private static final long CONFORME = 23L;
    private static final long FICHA = 700L;

    private final ProgramasEnMemoria programas = new ProgramasEnMemoria();
    private final MuestrasEnMemoria muestras = new MuestrasEnMemoria();
    private final ActasEnMemoria actas = new ActasEnMemoria();
    private final List<RegistroDeAuditoria> auditados = new ArrayList<>();

    @Test
    @DisplayName("sortea solo los predios cuya condicion es la que el programa busca")
    void sorteaSoloLosDelCriterio() {
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado = servicio().generar(programaId, OBSERVACION);

        assertThat(resultado.sorteados()).isEqualTo(2);
        assertThat(muestras.predios()).containsExactlyInAnyOrder(OMISO_UNO, OMISO_DOS);
    }

    @Test
    @DisplayName("la fila guardada copia la condicion y las dos areas del dia del sorteo")
    void laFilaCopiaLoQueLaDeteccionConcluyo() {
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        servicio().generar(programaId, OBSERVACION);

        MuestraDelPrograma fila =
                muestras.guardadas.stream()
                        .filter(m -> m.predioId() == OMISO_UNO)
                        .findFirst()
                        .orElseThrow();
        assertThat(fila.condicion()).isEqualTo(CondicionFiscalizada.OMISO);
        assertThat(fila.areaCatastral()).isEqualTo(AreaM2.de("120.00"));
        assertThat(fila.fechaSorteo())
                .as("la foto lleva su fecha, y sale del reloj inyectado (regla 9)")
                .isEqualTo(HOY);
    }

    @Test
    @DisplayName("un predio que otro programa ABIERTO ya se llevo no se vuelve a sortear")
    void noSorteaUnPredioDeOtroProgramaAbierto() {
        long otro = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        muestras.sembrar(otro, OMISO_UNO);
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado = servicio().generar(programaId, OBSERVACION);

        assertThat(resultado.sorteados())
                .as("la muestra depende del orden: el primero que se genera se lleva los predios")
                .isEqualTo(1);
        assertThat(muestras.prediosDe(programaId)).containsExactly(OMISO_DOS);
        assertThat(resultado.excluidosPorOtroPrograma())
                .as("y el que se llevo el otro programa se cuenta, con SU motivo (#586)")
                .isEqualTo(1);
        assertThat(resultado.excluidosPorActaDelEjercicio()).isZero();
    }

    @Test
    @DisplayName("y uno ya fiscalizado en el ejercicio tampoco")
    void noSorteaUnPredioYaFiscalizadoEsteEjercicio() {
        actas.sembrar(
                ActaFiscalizacion.nuevaPredial(
                        99L,
                        1,
                        100L + OMISO_DOS,
                        OMISO_DOS,
                        FICHA,
                        LocalDate.of(2026, 2, 1),
                        "R. MENDOZA CRUZ",
                        Hallazgo.OMISO,
                        null,
                        null,
                        null,
                        OBSERVACION));
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado = servicio().generar(programaId, OBSERVACION);

        assertThat(muestras.prediosDe(programaId)).containsExactly(OMISO_UNO);
        assertThat(resultado.excluidosPorActaDelEjercicio())
                .as("y se cuenta con SU motivo, que no es el mismo que el de otro programa (#586)")
                .isEqualTo(1);
        assertThat(resultado.excluidosPorOtroPrograma()).isZero();
    }

    @Test
    @DisplayName("un predio SIN TITULAR se sortea, y su fila entra con la columna nula (#586)")
    void sorteaElPredioSinTitular() {
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        // Desde #545 la deteccion los enseña —son el predio que nadie reclama, el candidato de
        // primer orden—, y hasta #586 `GenerarMuestra` los apartaba EN SILENCIO porque
        // `programa_muestra.contribuyente_id` era NOT NULL (V60). V73 lo relajo.
        ResultadoDelSorteo resultado =
                servicio(new TitularesDeMentira().con(OMISO_DOS, 100L + OMISO_DOS))
                        .generar(programaId, OBSERVACION);

        assertThat(resultado.sorteados())
                .as("los DOS entran: el que tiene titular y el que no")
                .isEqualTo(2);
        assertThat(muestras.prediosDe(programaId)).containsExactlyInAnyOrder(OMISO_UNO, OMISO_DOS);

        MuestraDelPrograma sinTitular = muestras.fila(OMISO_UNO);
        assertThat(sinTitular.contribuyenteId())
                .as("nulo, no un titular inventado para poder imputar")
                .isNull();
        assertThat(sinTitular.sinTitular()).isTrue();
        assertThat(muestras.fila(OMISO_DOS).contribuyenteId()).isEqualTo(100L + OMISO_DOS);
    }

    @Test
    @DisplayName("y la respuesta dice cuantos de los sorteados no tienen titular")
    void diceCuantosEntraronSinTitular() {
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado =
                servicio(new TitularesDeMentira().con(OMISO_DOS, 100L + OMISO_DOS))
                        .generar(programaId, OBSERVACION);

        assertThat(resultado.sorteadosSinTitular())
                .as("quien visita va sabiendo que ahi tiene que averiguar quien ocupa")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el padron examinado se puede reconstruir: detectados = sorteados + excluidos")
    void elRepartoCuadra() {
        // Un padron con los tres candidatos OMISO en juego: uno se lo llevo otro programa, otro ya
        // tiene acta del ejercicio, y el tercero entra. Sin las tres cifras, una muestra de 1 sobre
        // un padron de 3 es indistinguible de una muestra de 1 sobre un padron de 1.
        long otro = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        muestras.sembrar(otro, OMISO_UNO);
        actas.sembrar(
                ActaFiscalizacion.nuevaPredial(
                        99L,
                        1,
                        100L + OMISO_DOS,
                        OMISO_DOS,
                        FICHA,
                        LocalDate.of(2026, 2, 1),
                        "R. MENDOZA CRUZ",
                        Hallazgo.OMISO,
                        null,
                        null,
                        null,
                        OBSERVACION));
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado = servicio().generar(programaId, OBSERVACION);

        assertThat(resultado.detectados())
                .as("los dos OMISO del padron; el CONFORME no lo detecta este criterio")
                .isEqualTo(2);
        assertThat(resultado.sorteados()).isZero();
        assertThat(resultado.excluidosPorOtroPrograma()).isEqualTo(1);
        assertThat(resultado.excluidosPorActaDelEjercicio()).isEqualTo(1);
        assertThat(resultado.excluidos()).isEqualTo(2);
        assertThat(resultado.sorteados() + resultado.excluidos())
                .as("y por eso quien lee la respuesta puede reconstruir el padron examinado")
                .isEqualTo(resultado.detectados());
    }

    @Test
    @DisplayName("un predio que cumple los DOS motivos se cuenta una sola vez")
    void unPredioConLosDosMotivosSeCuentaUnaVez() {
        long otro = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        muestras.sembrar(otro, OMISO_UNO);
        actas.sembrar(
                ActaFiscalizacion.nuevaPredial(
                        99L,
                        1,
                        100L + OMISO_UNO,
                        OMISO_UNO,
                        FICHA,
                        LocalDate.of(2026, 2, 1),
                        "R. MENDOZA CRUZ",
                        Hallazgo.OMISO,
                        null,
                        null,
                        null,
                        OBSERVACION));
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado = servicio().generar(programaId, OBSERVACION);

        assertThat(resultado.excluidos())
                .as("sumar los dos motivos por separado daria mas excluidos que detectados")
                .isEqualTo(1);
        assertThat(resultado.detectados()).isEqualTo(2);
        assertThat(resultado.sorteados()).isEqualTo(1);
    }

    @Test
    @DisplayName("el reparto se ACUMULA por el padron entero, no se lee de la ultima pagina")
    void elRepartoSeAcumulaPorElPadronEntero() {
        // El padron se recorre en paginas de 200, asi que con tres predios sembrados el bucle da
        // una sola vuelta y «acumular» y «quedarse con la ultima pagina» valen lo mismo. Aqui se
        // siembran 250 candidatos —dos vueltas— con 210 ya tomados por otro programa, repartidos
        // a proposito entre las DOS paginas.
        //
        // Leyendo la ultima pagina saldria «50 detectados, 40 sorteados, 10 excluidos»: cuadra
        // consigo mismo, o sea que ni siquiera el invariante lo delata, y describe un padron que
        // no existe. Es el modo de fallo de este issue un escalon mas arriba.
        long otro = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        DeteccionDeMentira padron = new DeteccionDeMentira();
        TitularesDeMentira titulares = new TitularesDeMentira();
        for (long predioId = 1000; predioId < 1250; predioId++) {
            padron.con(fila(predioId, CondicionFiscalizada.OMISO));
            titulares.con(predioId, 100L + predioId);
            if (predioId < 1210) {
                muestras.sembrar(otro, predioId);
            }
        }
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        ResultadoDelSorteo resultado =
                new GenerarMuestra(
                                programas,
                                muestras,
                                actas,
                                new DeteccionDeOmisos(padron, titulares),
                                auditados::add,
                                RELOJ)
                        .generar(programaId, OBSERVACION);

        assertThat(resultado.detectados()).isEqualTo(250);
        assertThat(resultado.sorteados()).isEqualTo(40);
        assertThat(resultado.excluidosPorOtroPrograma()).isEqualTo(210);
    }

    @Test
    @DisplayName("un programa sin criterio no puede sortear, y el 422 dice cual le falta")
    void unProgramaSinCriterioNoSortea() {
        long programaId =
                programas.sembrar(
                        ProgramaFiscalizacion.nuevo(
                                "PF-SIN",
                                "Programa anterior a V60",
                                TipoDePrograma.PREDIAL,
                                LocalDate.of(2026, 1, 1),
                                null));

        assertThatThrownBy(() -> servicio().generar(programaId, OBSERVACION))
                .isInstanceOf(GenerarMuestra.ProgramaSinParametros.class)
                .hasMessageContaining("ejercicio");
        assertThat(muestras.guardadas).isEmpty();
    }

    @Test
    @DisplayName("sortear dos veces no se permite: hay actas levantadas sobre la foto")
    void sortearDosVecesNoSePermite() {
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        servicio().generar(programaId, OBSERVACION);

        assertThatThrownBy(() -> servicio().generar(programaId, OBSERVACION))
                .isInstanceOf(GenerarMuestra.MuestraYaSorteada.class);
        assertThat(muestras.guardadas).hasSize(2);
    }

    @Test
    @DisplayName("un programa que no existe no sortea nada")
    void unProgramaQueNoExisteNoSortea() {
        assertThatThrownBy(() -> servicio().generar(404L, OBSERVACION))
                .isInstanceOf(GenerarMuestra.ProgramaInexistente.class);
    }

    @Test
    @DisplayName("el sorteo se audita con su observacion y con el REPARTO entero (#586)")
    void elSorteoSeAudita() {
        long otro = programas.sembrar(programa(CondicionFiscalizada.OMISO));
        muestras.sembrar(otro, OMISO_UNO);
        long programaId = programas.sembrar(programa(CondicionFiscalizada.OMISO));

        servicio(new TitularesDeMentira()).generar(programaId, OBSERVACION);

        assertThat(auditados).hasSize(1);
        assertThat(auditados.get(0).observacion()).isEqualTo(OBSERVACION);
        assertThat(auditados.get(0).datosNuevos())
                .as(
                        "con solo «predios» quien audita meses despues no sabe sobre que padron se"
                                + " sorteo esta muestra")
                .contains("\"detectados\":2")
                .contains("\"predios\":1")
                .contains("\"sinTitular\":1")
                .contains("\"excluidosPorOtroPrograma\":1")
                .contains("\"excluidosPorActaDelEjercicio\":0");
    }

    // ------------------------------------------------------------------

    private GenerarMuestra servicio() {
        return servicio(
                new TitularesDeMentira()
                        .con(OMISO_UNO, 100L + OMISO_UNO)
                        .con(OMISO_DOS, 100L + OMISO_DOS)
                        .con(CONFORME, 100L + CONFORME));
    }

    private GenerarMuestra servicio(TitularesDeMentira titulares) {
        DeteccionDeMentira deteccion =
                new DeteccionDeMentira()
                        .con(fila(OMISO_UNO, CondicionFiscalizada.OMISO))
                        .con(fila(OMISO_DOS, CondicionFiscalizada.OMISO))
                        .con(fila(CONFORME, CondicionFiscalizada.CONFORME));

        return new GenerarMuestra(
                programas,
                muestras,
                actas,
                new DeteccionDeOmisos(deteccion, titulares),
                auditados::add,
                RELOJ);
    }

    private static ProgramaFiscalizacion programa(CondicionFiscalizada criterio) {
        return ProgramaFiscalizacion.nuevo(
                "PF-" + criterio.name(),
                "Programa de prueba",
                TipoDePrograma.PREDIAL,
                LocalDate.of(2026, 1, 1),
                null,
                E2026,
                null,
                criterio,
                "R. MENDOZA CRUZ");
    }

    /** Una fila detectada, sin titulares: los pone {@code DeteccionDeOmisos} al leer la pagina. */
    private static FilaDeOmisos fila(long predioId, CondicionFiscalizada condicion) {
        return new FilaDeOmisos(
                predioId,
                String.format("%018d", predioId),
                "S-01",
                List.of(),
                E2026,
                condicion,
                false,
                AreaM2.de("120.00"),
                condicion == CondicionFiscalizada.OMISO ? null : AreaM2.de("120.00"),
                null,
                null,
                null);
    }

    /** Programas en memoria: sólo lo que el sorteo pide. */
    private static final class ProgramasEnMemoria implements ProgramaFiscalizacionRepository {
        private final List<ProgramaFiscalizacion> filas = new ArrayList<>();
        private long siguiente = 1;

        long sembrar(ProgramaFiscalizacion programa) {
            return insertar(programa).id();
        }

        @Override
        public ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa) {
            ProgramaFiscalizacion guardado =
                    new ProgramaFiscalizacion(
                            siguiente++,
                            programa.codigo() + "-" + siguiente,
                            programa.descripcion(),
                            programa.tipo(),
                            programa.fechaInicio(),
                            programa.fechaFin(),
                            EstadoDePrograma.ABIERTO,
                            programa.ejercicio(),
                            programa.sectorCodigo(),
                            programa.criterio(),
                            programa.fiscalizador());
            filas.add(guardado);
            return guardado;
        }

        @Override
        public Optional<ProgramaFiscalizacion> findById(long id) {
            return filas.stream().filter(p -> p.id() != null && p.id() == id).findFirst();
        }

        @Override
        public Pagina<ProgramaFiscalizacion> consultar(
                CriterioDeProgramas criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("el sorteo no consulta la grilla");
        }
    }

    /** La muestra en memoria, con la misma exclusión que la consulta real. */
    private static final class MuestrasEnMemoria implements MuestraDelProgramaRepository {
        private final List<MuestraDelPrograma> guardadas = new ArrayList<>();

        void sembrar(long programaId, long predioId) {
            guardadas.add(
                    new MuestraDelPrograma(
                            (long) guardadas.size() + 1,
                            programaId,
                            predioId,
                            String.format("%018d", predioId),
                            100L + predioId,
                            CondicionFiscalizada.OMISO,
                            null,
                            null,
                            null,
                            HOY));
        }

        Set<Long> predios() {
            Set<Long> ids = new HashSet<>();
            guardadas.forEach(m -> ids.add(m.predioId()));
            return ids;
        }

        List<Long> prediosDe(long programaId) {
            return guardadas.stream()
                    .filter(m -> m.programaId() == programaId)
                    .map(MuestraDelPrograma::predioId)
                    .toList();
        }

        /** La fila sorteada de un predio: hace falta para mirar su titular, no su recuento. */
        MuestraDelPrograma fila(long predioId) {
            return guardadas.stream()
                    .filter(m -> m.predioId() == predioId)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public int insertar(
                List<MuestraDelPrograma> filas, Observacion observacion, Instant fechaRegistro) {
            guardadas.addAll(filas);
            return filas.size();
        }

        @Override
        public boolean tieneMuestra(long programaId) {
            return guardadas.stream().anyMatch(m -> m.programaId() == programaId);
        }

        @Override
        public Pagina<MuestraDelPrograma> delPrograma(
                long programaId, @Nullable Long predioId, Paginacion paginacion) {
            throw new UnsupportedOperationException("el sorteo no lee la grilla");
        }

        @Override
        public Set<Long> prediosEnProgramasAbiertos(long programaPropio, Set<Long> predios) {
            // El doble sólo siembra programas abiertos: lo que el CERRADO cambia lo mide
            // `MuestraDelProgramaRepositoryJdbcTest` contra PostgreSQL de verdad.
            return guardadas.stream()
                    .filter(m -> m.programaId() != programaPropio)
                    .map(MuestraDelPrograma::predioId)
                    .filter(predios::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
