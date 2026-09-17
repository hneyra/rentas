import type { ClaveDeHoja } from '../arbol.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../tablas.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

/**
 * Las cuatro pantallas de **Fiscalización** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'fis-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const FISCALIZACION = {
  'fis-panel': {
    instruccion: 'elija un programa y siga su embudo, de lo detectado a lo que sostiene una determinación.',
    bloques: [
      {
        titulo: 'Estado de la fiscalización',
        nota: 'Lo detectado, lo inspeccionado y lo que sostiene una determinación.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Programa', tipo: 's', opciones: ['Todos', 'PF-2026-014', 'PF-2026-008'] },
          { etiqueta: 'Detectados por cruce', tipo: 'r' },
          { etiqueta: 'Programados', tipo: 'r' },
          { etiqueta: 'Con acta levantada', tipo: 'r' },
          { etiqueta: 'Con diferencia', tipo: 'r' },
        ],
      },
    ],
  },
  'fis-actas': {
    instruccion: 'registre lo que midió en campo. La diferencia contra lo declarado es lo que sostiene la liquidación.',
    bloques: [
      {
        titulo: 'Acta de inspección',
        nota: 'Lo que el verificador midió frente a lo que el titular declaró.',
        campos: [
          { etiqueta: 'Nº de acta', tipo: '' },
          { etiqueta: 'Programa', tipo: 's', opciones: ['PF-2026-014', 'PF-2026-008'] },
          { etiqueta: 'Tipo de acta', tipo: 's', opciones: ['Predial', 'Vehicular'] },
          { etiqueta: 'Código predial', tipo: '' },
          { etiqueta: 'Contribuyente', tipo: '1' },
          {
            etiqueta: 'Verificador',
            tipo: 's',
            opciones: ['Reto Santos, Víctor', 'Peña Sandoval, Luis'],
          },
          { etiqueta: 'Fecha de inspección', tipo: 'd' },
          { etiqueta: 'Con presencia del titular', tipo: 'c', casilla: 'El titular firmó el acta' },
          {
            etiqueta: 'Observaciones',
            tipo: 'a1',
            ayuda: 'Lo que hay que saber antes de volver al predio',
          },
        ],
        tabla: {
          // «Declarado» y «Diferencia» dicen su motivo, que no es el mismo (#195).
          clave: 'declarado-contra-verificado',
          sinDato: { texto: '—', nota: 'Ninguna operacion del contrato publica este dato.' },
          titulo: 'Declarado contra verificado',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Declarado', alineadoDerecha: true },
            { rotulo: 'Verificado', alineadoDerecha: true },
            { rotulo: 'Diferencia', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'La diferencia es lo que sostiene la determinación. Sin acta levantada no se puede liquidar.',
        },
      },
    ],
  },
  'fis-prog': {
    instruccion: 'defina el criterio del cruce y el tamaño de la muestra. De ahí salen las inspecciones del programa.',
    bloques: [
      {
        titulo: 'Programa de fiscalización',
        nota: 'La muestra sale de un cruce: catastro contra rentas.',
        campos: [
          { etiqueta: 'Nº de programa', tipo: '' },
          { etiqueta: 'Denominación', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Criterio del cruce',
            tipo: 's',
            opciones: [
              'Predio sin declarar',
              'Área subvaluada',
              'Uso distinto al declarado',
              'Omiso a la declaración',
            ],
          },
          { etiqueta: 'Sector', tipo: 's', opciones: ['Todos', '01', '02', '03', '04', '05'] },
          { etiqueta: 'Tamaño de la muestra', tipo: '' },
          { etiqueta: 'Fecha de inicio', tipo: 'd' },
          { etiqueta: 'Fecha de cierre', tipo: 'd' },
        ],
        tabla: {
          clave: 'muestra-del-programa',
          sinDato: { texto: '—', nota: 'Ninguna operacion del contrato publica este dato.' },
          /*
           * **La unica de las seis tablas de #228 que SI es una relacion paginada** (#228).
           *
           * Desde #172 su encabezado dice «2 de 84» —`totalElementos` es cuantos predios sorteo el
           * programa— y hasta este issue **no habia forma de ver los otros 82**: la tabla nombraba
           * lo que faltaba y no lo daba, que es honesto y peor que incompleto.
           *
           * Las otras cinco de aquel issue —`coa-exp`, `coa-cost`, `tra-pap`, `fis-actas` y
           * `fis-res`— **no entran, y su motivo no es la paginacion**: su tabla no es una relacion
           * —son los actos de UN expediente, las lineas de UNA liquidacion, los actos de UNA
           * papeleta, las magnitudes de UN acta y los ejercicios de UNA resolucion— y su `?tamano=1`
           * sirve para elegir CUAL se dibuja. Lo que les falta es el **selector**, que es un filtro,
           * o sea `kamayuk-lib`#94 y #172; paginar esa lectura no paginaria su tabla. Esta escrito
           * ademas en el javadoc de cada ruta en `datos/lecturas.ts`, para que no haya que volver a
           * medirlo.
           */
          paginacion: {
            en: 'servidor',
            enLaRuta: EN_LA_RUTA.pagina,
            tamano: 20,
            tamanos: [20, 50, 100],
            tamanoEnLaRuta: EN_LA_RUTA.tamano,
            hayMas: hayMasDe('muestra-del-programa'),
            paginas: paginasDe('muestra-del-programa'),
          },
          /*
           * **Dos campos de los tres de la lista blanca, y el tercero se deja fuera a proposito.**
           *
           * `MuestraDelProgramaRepositoryJdbc.ORDEN` declara `cod_ref_catastral`, `condicion` y
           * `sector_codigo`. Los dos primeros son columnas de ESTA tabla —«Codigo predial» y «Causa
           * del cruce»—; el sector **no se dibuja en ninguna**, y ofrecer ordenar por algo que no
           * se ve deja la barra anunciando un orden que nadie puede comprobar.
           *
           * Y hay un segundo motivo, que es un hallazgo de este issue: ese tercero **se llama
           * `sector`** y no `sectorCodigo`. `OrdenSeguro.publicandoComo("sector", "sector_codigo")`
           * RETIRA el `camelCase` automatico, asi que `?ordenarPor=sectorCodigo` contesta **422
           * ORDEN_NO_ADMITIDO** — y hasta este issue la guarda que cruza el orden ofrecido contra el
           * backend no sabia leer esa llamada y lo habria dado por bueno. Ahora si.
           *
           * El PRIMERO es el `ORDEN_POR_OMISION` de `MuestraController` (`codRefCatastral`): sin
           * campo en la ruta el conector no manda ninguno, y ordena el backend por el suyo.
           */
          orden: {
            campos: [
              { valor: 'codRefCatastral', rotulo: 'Código predial' },
              { valor: 'condicion', rotulo: 'Causa del cruce' },
            ],
            enLaRuta: EN_LA_RUTA.ordenarPor,
            sentidoEnLaRuta: EN_LA_RUTA.sentido,
            ascendente: 'ASCENDENTE',
            descendente: 'DESCENDENTE',
          },
          titulo: 'Muestra del programa',
          accion: 'Regenerar muestra',
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Contribuyente', alineadoDerecha: false },
            { rotulo: 'Causa del cruce', alineadoDerecha: false },
            { rotulo: 'Diferencia estimada S/', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'fis-res': {
    instruccion: 'compruebe la liquidación por ejercicio. Emitir la resolución es lo que la vuelve deuda exigible.',
    bloques: [
      {
        titulo: 'Liquidación de la diferencia',
        nota: 'La deuda omitida se determina aquí y entra en la cuenta corriente al emitir.',
        campos: [
          { etiqueta: 'Nº de acta', tipo: 'r' },
          { etiqueta: 'Contribuyente', tipo: 'r' },
          {
            etiqueta: 'Ejercicios alcanzados',
            tipo: 's',
            opciones: ['2024 — 2026', '2022 — 2026', 'Sólo 2026'],
          },
          { etiqueta: 'Insoluto omitido', tipo: 'r' },
          { etiqueta: 'Interés', tipo: 'r' },
          { etiqueta: 'Multa tributaria', tipo: 'r' },
          {
            etiqueta: 'Artículo del Código Tributario',
            tipo: 's',
            opciones: [
              '176º — No presentar declaración',
              '178º — Declarar cifras falsas',
              'No aplica',
            ],
          },
          { etiqueta: 'Total liquidado', tipo: 'r' },
        ],
        tabla: {
          clave: 'detalle-por-ejercicio',
          sinDato: { texto: '—', nota: 'Ninguna operacion del contrato publica este dato.' },
          titulo: 'Detalle por ejercicio',
          columnas: [
            { rotulo: 'Ejercicio', alineadoDerecha: false },
            { rotulo: 'Base omitida S/', alineadoDerecha: true },
            { rotulo: 'Insoluto S/', alineadoDerecha: true },
            { rotulo: 'Interés S/', alineadoDerecha: true },
            { rotulo: 'Total S/', alineadoDerecha: true },
          ],
          nota: 'Emitir la resolución de determinación es lo que convierte esto en deuda exigible.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
