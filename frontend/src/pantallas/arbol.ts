import type { Modulo } from './tipos.ts';

/**
 * **El arbol de V8: diez modulos y cuarenta hojas** (UI-5, #85, AC3).
 *
 * <h2>De donde sale, literalmente</h2>
 *
 * De `const ARBOL` de `frontend/diseno/RentasV8.dc.html` (linea 424), entrada a entrada. No se
 * reescribio ni un trazo de icono ni una ruta: `verificaciones/pantallas-del-artboard.test.ts`
 * compara este archivo contra el artboard vendorizado y no contra una copia suya, que es lo
 * unico que impide que las dos cosas se «arreglen» de memoria por separado.
 *
 * La forma del artboard es posicional —`[rotulo, nota, clave, codigo, trazos, submodulos]`— y
 * aqui es un objeto. Es la unica libertad que se toma la transcripcion: las **cadenas** van
 * literales (AC9), y la guarda las compara una a una.
 *
 * <h2>Esto NO es `src/marco/arbol.ts`, y los dos tienen que existir</h2>
 *
 * `src/marco/arbol.ts` es el catalogo de **V6**: es lo que la interfaz que hoy se sirve compone
 * con `GET /seguridad/modulos`. Este es el de **V8**, el artboard contra el que se reimplanta.
 * Sustituir aquel por este ahora dejaria el repositorio sin interfaz durante varios issues, que
 * es justo lo que el issue deja fuera. Coinciden en los diez modulos y en las cuarenta claves
 * —por eso la reimplantacion es posible—, y difieren en lo que V8 anade: el codigo de modulo en
 * el propio arbol, las operaciones que cada hoja declara y sus piezas.
 *
 * <h2>Por que `as const`</h2>
 *
 * Porque de aqui sale `ClaveDeHoja`, y de `ClaveDeHoja` sale que **no pueda haber una hoja sin
 * pantalla ni una pantalla sin hoja** (AC2) sin que el compilador lo diga. Con el tipo ancho
 * —`readonly Modulo[]`— `clave` seria `string` y las cuarenta definiciones podrian ser treinta
 * y nueve, o cuarenta y una, sin que nada se pusiera rojo hasta correr la guarda.
 *
 * El `satisfies` de al lado es lo que impide que `as const` se lo trague todo: comprueba la
 * forma —que un verbo sea uno de los cinco, que una hoja traiga sus cuatro campos— sin ensanchar
 * los literales.
 */
export const ARBOL = [
  {
    rotulo: 'Inicio',
    nota: 'Panel de recaudación',
    slug: 'inicio',
    codigo: 'INICIO',
    trazos: ['M3 10.6 12 3.5l9 7.1', 'M5.6 9.6V20.5h12.8V9.6', 'M10 20.5v-5.4h4v5.4'],
    hojas: [
      {
        clave: 'ini-panel',
        rotulo: 'Panel',
        operaciones: [
          { verbo: 'GET', ruta: '/indicadores/recaudacion', nota: 'IndicadoresController' },
          { verbo: 'BASE', ruta: '/portal/situacion', nota: 'PortalController' },
        ],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'La barra de avance por tributo' }],
      },
      {
        clave: 'ini-flujo',
        rotulo: 'Recaudación',
        operaciones: [
          { verbo: 'GET', ruta: '/indicadores/recaudacion', nota: 'IndicadoresController' },
        ],
        piezasDeclaradas: [
          { pieza: 'Chart', uso: 'Barras horizontales; recharts, que shadcn envuelve' },
        ],
      },
      {
        clave: 'ini-parado',
        rotulo: 'Trabajo parado',
        operaciones: [
          { verbo: 'GET', ruta: '/indicadores/trabajo-parado', nota: 'IndicadoresController' },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'ini-cierre',
        rotulo: 'Cierre del día',
        operaciones: [{ verbo: 'BASE', ruta: '/consultas/pagos', nota: 'ConsultaPagosController' }],
        piezasDeclaradas: [],
      },
    ],
  },
  {
    rotulo: 'Rentas · Registro',
    nota: 'Predial y contribuyentes',
    slug: 'rentas',
    codigo: 'RENTAS_REGISTRO',
    trazos: ['M6.5 3.5h7.5l4 4v13h-11.5z', 'M14 3.5v4h4', 'M9.5 12.5h5', 'M9.5 16.5h3.5'],
    hojas: [
      {
        clave: 'panel',
        rotulo: 'Panel',
        operaciones: [
          { verbo: 'GET', ruta: '/rentas/predial/corridas/ultima', nota: 'PredialController' },
          {
            verbo: 'GET',
            ruta: '/rentas/predial/corridas/{corridaId}/observados',
            nota: 'Los que quedan sin emisión',
          },
        ],
        piezasDeclaradas: [
          { pieza: 'Alert', uso: 'Los observados sin emisión, que es lo que hay que corregir' },
        ],
      },
      {
        clave: 'predios',
        rotulo: 'Contribuyentes',
        operaciones: [
          { verbo: 'BASE', ruta: '/rentas/contribuyentes', nota: 'ContribuyenteController.RUTA' },
          { verbo: 'PUT', ruta: '/rentas/contribuyentes/{id}', nota: 'Actualiza el contribuyente' },
          {
            verbo: 'GET',
            ruta: '/rentas/contribuyentes/{id}/ficha',
            nota: 'FichaDelContribuyenteController',
          },
          {
            verbo: 'POST',
            ruta: '/rentas/contribuyentes/{id}/domicilios',
            nota: 'Añade un domicilio',
          },
          {
            verbo: 'POST',
            ruta: '/rentas/contribuyentes/{id}/contactos',
            nota: 'Añade un contacto',
          },
        ],
        piezasDeclaradas: [
          {
            pieza: 'DataTable',
            uso: 'El padrón pide orden y filtro por columna; TanStack Table, que shadcn envuelve',
          },
        ],
      },
      {
        clave: 'territorio',
        rotulo: 'Determinación',
        operaciones: [
          {
            verbo: 'POST',
            ruta: '/rentas/predial/calculo-individual',
            nota: 'Determina un contribuyente',
          },
          {
            verbo: 'POST',
            ruta: '/rentas/predial/calculo-masivo',
            nota: 'Emisión anual del padrón',
          },
          { verbo: 'POST', ruta: '/rentas/deuda/altas', nota: 'MovimientosDeDeudaController' },
          { verbo: 'POST', ruta: '/rentas/deuda/bajas', nota: 'Extingue deuda, con resolución' },
          { verbo: 'BASE', ruta: '/rentas/vehicular/calculo', nota: 'VehicularController' },
          { verbo: 'BASE', ruta: '/rentas/alcabala', nota: 'AlcabalaController' },
          { verbo: 'BASE', ruta: '/rentas/espectaculos', nota: 'EspectaculoController' },
        ],
        piezasDeclaradas: [
          {
            pieza: 'AlertDialog',
            uso: 'Confirmar antes de asentar: la determinación entra en la cuenta corriente',
          },
        ],
      },
      {
        clave: 'valores',
        rotulo: 'Valores',
        operaciones: [
          {
            verbo: 'GET',
            ruta: '/seguridad/parametros/ejercicios/{ejercicio}',
            nota: 'EjercicioParametrizadoController',
          },
          { verbo: 'BASE', ruta: '/rentas/arbitrios', nota: 'ArbitriosController' },
          { verbo: 'BASE', ruta: '/rentas/beneficios', nota: 'BeneficioController' },
        ],
        piezasDeclaradas: [],
      },
    ],
  },
  {
    rotulo: 'Fiscalización',
    nota: 'Detección y actas',
    slug: 'fisc',
    codigo: 'FISCALIZACION',
    trazos: [
      'M9.5 4.5H8A1.5 1.5 0 0 0 6.5 6v13A1.5 1.5 0 0 0 8 20.5h8a1.5 1.5 0 0 0 1.5-1.5V6A1.5 1.5 0 0 0 16 4.5h-1.5',
      'M9.5 3.2h5v2.8h-5z',
      'M9.6 13.2l2 2 3.4-4',
    ],
    hojas: [
      {
        clave: 'fis-panel',
        rotulo: 'Panel',
        operaciones: [
          { verbo: 'GET', ruta: '/fiscalizacion/estado-cuenta', nota: 'OmisosController' },
        ],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'El avance del programa por etapa' }],
      },
      {
        clave: 'fis-actas',
        rotulo: 'Actas',
        operaciones: [
          { verbo: 'BASE', ruta: '/fiscalizacion/actas', nota: 'ActasController' },
          { verbo: 'BASE', ruta: '/fiscalizacion/predial/actas', nota: 'ActaPredialController' },
          { verbo: 'BASE', ruta: '/fiscalizacion/vehicular', nota: 'ActaVehicularController' },
        ],
        piezasDeclaradas: [
          { pieza: 'DataTable', uso: 'Actas por estado, con filtro por programa' },
        ],
      },
      {
        clave: 'fis-prog',
        rotulo: 'Programas y cruces',
        operaciones: [
          {
            verbo: 'GET',
            ruta: '/fiscalizacion/programas/{id}/muestra',
            nota: 'MuestraController',
          },
          {
            verbo: 'POST',
            ruta: '/fiscalizacion/programas/{id}/muestra',
            nota: 'Genera la muestra',
          },
          { verbo: 'GET', ruta: '/fiscalizacion/omisos', nota: 'OmisosController' },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'fis-res',
        rotulo: 'Resultados',
        operaciones: [
          { verbo: 'GET', ruta: '/fiscalizacion/resultados', nota: 'LiquidacionController' },
          { verbo: 'POST', ruta: '/fiscalizacion/liquidaciones', nota: 'Liquida la diferencia' },
          {
            verbo: 'PATCH',
            ruta: '/fiscalizacion/liquidaciones/{numero}/estados',
            nota: 'Cambia el estado',
          },
          {
            verbo: 'GET',
            ruta: '/fiscalizacion/resoluciones/{numero}',
            nota: 'ResolucionController',
          },
        ],
        piezasDeclaradas: [
          {
            pieza: 'AlertDialog',
            uso: 'Emitir la resolución: es lo que vuelve la diferencia deuda exigible',
          },
        ],
      },
    ],
  },
  {
    rotulo: 'Tránsito',
    nota: 'Papeletas y vehículos',
    slug: 'transito',
    codigo: 'TRANSITO',
    trazos: [
      'M5 15.8v-3.2l1.9-4.4h10.2l1.9 4.4v3.2',
      'M3.6 15.8h16.8',
      'M8.4 18.4a1.6 1.6 0 1 1-3.2 0 1.6 1.6 0 0 1 3.2 0',
      'M18.8 18.4a1.6 1.6 0 1 1-3.2 0 1.6 1.6 0 0 1 3.2 0',
    ],
    hojas: [
      {
        clave: 'tra-panel',
        rotulo: 'Panel',
        operaciones: [
          {
            verbo: 'BASE',
            ruta: '/transito/estado-cuenta',
            nota: 'EstadoDeCuentaTransitoController',
          },
        ],
        piezasDeclaradas: [
          { pieza: 'Alert', uso: 'Las caducadas sin notificar, que ya no se pueden cobrar' },
        ],
      },
      {
        clave: 'tra-pap',
        rotulo: 'Papeletas',
        operaciones: [
          { verbo: 'BASE', ruta: '/transito/papeletas', nota: 'PapeletasController' },
          {
            verbo: 'BASE',
            ruta: '/transito/papeletas/busqueda',
            nota: 'BusquedaDePapeletasController',
          },
          {
            verbo: 'BASE',
            ruta: '/transito/papeletas/{numero}/actos',
            nota: 'ActosDeLaPapeletaController',
          },
          { verbo: 'BASE', ruta: '/transito/descargos', nota: 'DescargosController' },
        ],
        piezasDeclaradas: [
          {
            pieza: 'Command',
            uso: 'La búsqueda por placa o número, que es como se entra a esta pantalla',
          },
        ],
      },
      {
        clave: 'tra-veh',
        rotulo: 'Vehículos y depósito',
        operaciones: [
          { verbo: 'BASE', ruta: '/transito/internamientos', nota: 'InternamientosController' },
          {
            verbo: 'BASE',
            ruta: '/transito/constancias-libres',
            nota: 'ConstanciasLibresController',
          },
          { verbo: 'GET', ruta: '/rentas/vehiculos/{placa}', nota: 'VehiculoController' },
        ],
        piezasDeclaradas: [{ pieza: 'DataTable', uso: 'Internamientos con sus días de custodia' }],
      },
      {
        clave: 'tra-cua',
        rotulo: 'Cuadros y plazos',
        operaciones: [
          { verbo: 'BASE', ruta: '/transito/codigos', nota: 'CodigosTransitoController' },
        ],
        piezasDeclaradas: [{ pieza: 'HoverCard', uso: 'La norma que respalda cada código' }],
      },
    ],
  },
  {
    rotulo: 'Infracciones administrativas',
    nota: 'Sanciones administrativas',
    slug: 'infra',
    codigo: 'INFRACCIONES_ADMINISTRATIVAS',
    trazos: ['M12 4.2 20.8 19.6H3.2z', 'M12 9.8v4.4', 'M12 17.1h.02'],
    hojas: [
      {
        clave: 'inf-panel',
        rotulo: 'Panel',
        operaciones: [
          {
            verbo: 'BASE',
            ruta: '/infracciones/administrativas/estado-cuenta',
            nota: 'EstadoDeCuentaAdministrativoController',
          },
        ],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'Los que vencen esta semana' }],
      },
      {
        clave: 'inf-exp',
        rotulo: 'Expedientes',
        operaciones: [
          {
            verbo: 'BASE',
            ruta: '/infracciones/actas',
            nota: 'InfraccionesAdministrativasController',
          },
          {
            verbo: 'BASE',
            ruta: '/infracciones/administrativas/notificaciones',
            nota: 'NotificacionAdministrativaController',
          },
        ],
        piezasDeclaradas: [
          {
            pieza: 'Stepper',
            uso: 'Acta, resolución y notificación, en orden legal; composición de Card',
          },
        ],
      },
      {
        clave: 'inf-cuis',
        rotulo: 'CUIS y reincidencia',
        operaciones: [{ verbo: 'BASE', ruta: '/infracciones/cuis', nota: 'CodigosCuisController' }],
        piezasDeclaradas: [],
      },
      {
        clave: 'inf-esc',
        rotulo: 'Escalas y plazos',
        operaciones: [
          {
            verbo: 'BASE',
            ruta: '/infracciones/administrativas/codigos/reporte',
            nota: 'ReporteCodigosAdministrativosController',
          },
          {
            verbo: 'BASE',
            ruta: '/infracciones/administrativas/reportes/vencidas',
            nota: 'NotificacionesVencidasController',
          },
          {
            verbo: 'BASE',
            ruta: '/infracciones/administrativas/reportes/por-contribuyente',
            nota: 'NotificacionesPorContribuyenteController',
          },
        ],
        piezasDeclaradas: [{ pieza: 'Alert', uso: 'Lo vencido, que ya no se puede cobrar' }],
      },
    ],
  },
  {
    rotulo: 'Consultas',
    nota: 'Ventanilla y constancias',
    slug: 'consultas',
    codigo: 'CONSULTAS',
    trazos: ['M17.4 11a6.4 6.4 0 1 1-12.8 0 6.4 6.4 0 0 1 12.8 0', 'M15.8 15.8 20.6 20.6'],
    hojas: [
      {
        clave: 'con-panel',
        rotulo: 'Panel',
        operaciones: [
          {
            verbo: 'GET',
            ruta: '/consultas/cuenta-corriente/{codigo}',
            nota: 'CuentaCorrienteController',
          },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'con-contrib',
        rotulo: 'Contribuyentes',
        operaciones: [
          { verbo: 'BASE', ruta: '/consultas/deuda', nota: 'ConsultaDeudaController' },
          { verbo: 'BASE', ruta: '/consultas/pagos', nota: 'ConsultaPagosController' },
          { verbo: 'BASE', ruta: '/consultas/unificada', nota: 'ConsultaUnificadaController' },
        ],
        piezasDeclaradas: [
          { pieza: 'Command', uso: 'Un solo campo que reconoce DNI, RUC, placa, código o nombre' },
        ],
      },
      {
        clave: 'con-obj',
        rotulo: 'Consultas por objeto',
        operaciones: [
          { verbo: 'BASE', ruta: '/consultas/predios', nota: 'ConsultaPrediosController' },
          { verbo: 'BASE', ruta: '/consultas/vehiculos', nota: 'ConsultaVehiculosController' },
          { verbo: 'BASE', ruta: '/consultas/valores', nota: 'ConsultaValoresController' },
          { verbo: 'BASE', ruta: '/consultas/altas-bajas', nota: 'AltasBajasController' },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'con-doc',
        rotulo: 'Documentos y beneficios',
        operaciones: [
          {
            verbo: 'GET',
            ruta: '/consultas/constancias/no-adeudo?formato',
            nota: 'ConstanciaController',
          },
          {
            verbo: 'BASE',
            ruta: '/consultas/deudas-con-beneficio',
            nota: 'DeudasConBeneficioController',
          },
        ],
        piezasDeclaradas: [
          { pieza: 'Alert', uso: 'Con deuda pendiente sale constancia de deuda, no de no adeudo' },
        ],
      },
    ],
  },
  {
    rotulo: 'Coactiva',
    nota: 'Expedientes y medidas',
    slug: 'coactiva',
    codigo: 'COACTIVA',
    trazos: [
      'M12 4.4v3.2',
      'M5 8.6h14',
      'M5 8.6 2.8 14.4h4.4z',
      'M19 8.6 16.8 14.4h4.4z',
      'M8.4 20h7.2',
    ],
    hojas: [
      {
        clave: 'coa-panel',
        rotulo: 'Panel',
        operaciones: [
          { verbo: 'GET', ruta: '/coactiva/deudas', nota: 'DeudaCoactivaController' },
          { verbo: 'GET', ruta: '/coactiva/deudas-en-beneficio', nota: 'La que está en convenio' },
        ],
        piezasDeclaradas: [
          {
            pieza: 'Alert',
            uso: 'Los expedientes sin REC: están abiertos y el procedimiento no ha empezado',
          },
        ],
      },
      {
        clave: 'coa-exp',
        rotulo: 'Expedientes',
        operaciones: [
          { verbo: 'GET', ruta: '/coactiva/expedientes', nota: 'ExpedienteController' },
          {
            verbo: 'GET',
            ruta: '/coactiva/expedientes/{numero}/proceso',
            nota: 'La línea de vida del expediente',
          },
          {
            verbo: 'POST',
            ruta: '/coactiva/expedientes/{numero}/actos',
            nota: 'Dicta un acto coactivo',
          },
          {
            verbo: 'PATCH',
            ruta: '/coactiva/expedientes/{numero}/estados',
            nota: 'Cambia el estado',
          },
          { verbo: 'POST', ruta: '/coactiva/rec/impresion', nota: 'Imprime la REC' },
        ],
        piezasDeclaradas: [
          { pieza: 'Stepper', uso: 'REC, notificación, medida y ejecución' },
          { pieza: 'AlertDialog', uso: 'El coste tasado del acto, antes de dictarlo' },
        ],
      },
      {
        clave: 'coa-cart',
        rotulo: 'Cartera y medidas',
        operaciones: [
          { verbo: 'POST', ruta: '/coactiva/convenios', nota: 'ConvenioCoactivoController' },
          { verbo: 'POST', ruta: '/coactiva/expedientes/importacion', nota: 'Importa cartera' },
          {
            verbo: 'GET',
            ruta: '/coactiva/expedientes/{numero}/deuda',
            nota: 'La deuda del expediente',
          },
          { verbo: 'POST', ruta: '/coactiva/notificaciones', nota: 'Notifica el acto' },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'coa-cost',
        rotulo: 'Costas y plazos',
        operaciones: [
          { verbo: 'GET', ruta: '/coactiva/liquidaciones-costas', nota: 'CostasController' },
          { verbo: 'POST', ruta: '/coactiva/liquidaciones-costas', nota: 'Liquida las costas' },
          {
            verbo: 'GET',
            ruta: '/coactiva/prescripcion',
            nota: 'PrescripcionController, en el módulo de valores',
          },
        ],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'Lo que queda para que prescriba' }],
      },
    ],
  },
  {
    rotulo: 'Autorizaciones y licencias',
    nota: 'Licencias y anuncios',
    slug: 'autoriz',
    codigo: 'AUTORIZACIONES_Y_LICENCIAS',
    trazos: ['M4.4 9.6V20h15.2V9.6', 'M3.2 9.6 5.2 4.6h13.6l2 5z', 'M9.6 20v-5.4h4.8V20'],
    hojas: [
      {
        clave: 'aut-panel',
        rotulo: 'Panel',
        operaciones: [
          { verbo: 'GET', ruta: '/licencias/funcionamiento', nota: 'LicenciaController' },
        ],
        piezasDeclaradas: [
          { pieza: 'Alert', uso: 'Las de plazo agotado, que ya se entienden otorgadas' },
        ],
      },
      {
        clave: 'aut-sol',
        rotulo: 'Solicitudes',
        operaciones: [
          { verbo: 'POST', ruta: '/licencias/funcionamiento', nota: 'Licencia de funcionamiento' },
          {
            verbo: 'POST',
            ruta: '/licencias/funcionamiento/{id}/cancelacion',
            nota: 'Cese de actividades',
          },
          {
            verbo: 'POST',
            ruta: '/licencias/edificacion/{expediente}/licencia',
            nota: 'EdificacionController',
          },
          {
            verbo: 'POST',
            ruta: '/autorizaciones/anuncios/{id}/renovacion',
            nota: 'AnuncioController',
          },
        ],
        piezasDeclaradas: [
          {
            pieza: 'Progress',
            uso: 'El plazo del TUPA, que al agotarse otorga por silencio positivo',
          },
        ],
      },
      {
        clave: 'aut-cat',
        rotulo: 'Catálogos y padrones',
        operaciones: [
          { verbo: 'GET', ruta: '/licencias/ciiu', nota: 'CiiuController' },
          { verbo: 'POST', ruta: '/licencias/ciiu', nota: 'Alta de giro' },
          {
            verbo: 'POST',
            ruta: '/licencias/certificados/{numero}/impresion',
            nota: 'CertificadoController',
          },
        ],
        piezasDeclaradas: [
          { pieza: 'Combobox', uso: 'El giro CIIU: son 1,842 y no caben en un Select' },
        ],
      },
      {
        clave: 'aut-tram',
        rotulo: 'Trámites y plazos',
        operaciones: [
          {
            verbo: 'POST',
            ruta: '/licencias/funcionamiento/reportes/padron',
            nota: 'Padrón de licencias',
          },
          {
            verbo: 'GET',
            ruta: '/licencias/funcionamiento/reportes/resumen-anual',
            nota: 'Resumen por ejercicio',
          },
          {
            verbo: 'GET',
            ruta: '/licencias/edificacion/reportes/general',
            nota: 'Reporte de edificación',
          },
        ],
        piezasDeclaradas: [],
      },
    ],
  },
  {
    rotulo: 'Seguridad',
    nota: 'Usuarios y permisos',
    slug: 'seguridad',
    codigo: 'SEGURIDAD',
    trazos: [
      'M12 3.4 19 5.9v5.6c0 4.1-3 7.2-7 9.1-4-1.9-7-5-7-9.1V5.9z',
      'M9.4 12.1l1.9 1.9 3.5-3.6',
    ],
    hojas: [
      {
        clave: 'seg-panel',
        rotulo: 'Panel',
        operaciones: [
          {
            verbo: 'GET',
            ruta: '/seguridad/modulos',
            nota: 'Con lo que se compone este mismo árbol',
          },
          { verbo: 'GET', ruta: '/seguridad/sesion', nota: 'SesionController' },
          { verbo: 'GET', ruta: '/seguridad/sesion/municipalidad', nota: 'La entidad de la barra' },
        ],
        piezasDeclaradas: [],
      },
      {
        clave: 'seg-acc',
        rotulo: 'Accesos',
        operaciones: [
          { verbo: 'GET', ruta: '/seguridad/accesos', nota: 'SeguridadController' },
          {
            verbo: 'GET',
            ruta: '/seguridad/sesion/permisos',
            nota: 'Permisos efectivos de la sesión',
          },
          { verbo: 'PUT', ruta: '/seguridad/usuarios/{id}/clave', nota: 'Cambia la contraseña' },
        ],
        piezasDeclaradas: [{ pieza: 'Tooltip', uso: 'De qué grupo viene un permiso heredado' }],
      },
      {
        clave: 'seg-aud',
        rotulo: 'Auditoría',
        operaciones: [
          { verbo: 'GET', ruta: '/seguridad/auditoria', nota: 'Quién hizo qué y cuándo' },
        ],
        piezasDeclaradas: [
          { pieza: 'DataTable', uso: 'Bitácora con filtro por usuario, módulo y riesgo' },
        ],
      },
      {
        clave: 'seg-sis',
        rotulo: 'Sistema',
        operaciones: [
          {
            verbo: 'PUT',
            ruta: '/seguridad/sesion/ejercicio',
            nota: 'Cambia el ejercicio de trabajo',
          },
          {
            verbo: 'GET',
            ruta: '/seguridad/parametros/ejercicios/{ejercicio}',
            nota: 'EjercicioParametrizadoController',
          },
          { verbo: 'POST', ruta: '/seguridad/respaldos', nota: 'Copias de seguridad' },
        ],
        piezasDeclaradas: [
          {
            pieza: 'AlertDialog',
            uso: 'Cambiar el ejercicio con una caja abierta pide confirmación',
          },
        ],
      },
    ],
  },
  {
    rotulo: 'Valores',
    nota: 'Emisión y notificación',
    slug: 'valores-mod',
    codigo: 'VALORES',
    trazos: [
      'M6.5 3.5h7.5l4 4v13h-11.5z',
      'M14 3.5v4h4',
      'M9.5 11.5h5',
      'M15.6 16.4a2.3 2.3 0 1 1-4.6 0 2.3 2.3 0 0 1 4.6 0',
    ],
    hojas: [
      {
        clave: 'val-panel',
        rotulo: 'Panel',
        operaciones: [{ verbo: 'BASE', ruta: '/valores', nota: 'ValoresController' }],
        piezasDeclaradas: [
          { pieza: 'Alert', uso: 'Emitidos y sin notificar: no cobran y el plazo les corre igual' },
        ],
      },
      {
        clave: 'val-val',
        rotulo: 'Valores',
        operaciones: [
          { verbo: 'POST', ruta: '/valores/{nro}/notificacion', nota: 'Notifica el valor' },
          { verbo: 'POST', ruta: '/valores/{numero}/movimientos', nota: 'Movimientos del valor' },
          { verbo: 'BASE', ruta: '/consultas/valores', nota: 'ConsultaValoresController' },
        ],
        piezasDeclaradas: [{ pieza: 'Sheet', uso: 'El valor y su hoja, sin salir de la lista' }],
      },
      {
        clave: 'val-cart',
        rotulo: 'Cartera y lotes',
        operaciones: [{ verbo: 'POST', ruta: '/valores/masivo', nota: 'Emisión por lote' }],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'La corrida, con sus observados' }],
      },
      {
        clave: 'val-tip',
        rotulo: 'Tipos y prescripción',
        operaciones: [
          { verbo: 'GET', ruta: '/coactiva/prescripcion', nota: 'PrescripcionController' },
          { verbo: 'POST', ruta: '/coactiva/prescripcion', nota: 'Declara la prescripción' },
        ],
        piezasDeclaradas: [{ pieza: 'Progress', uso: 'Cuánto queda para que prescriba' }],
      },
    ],
  },
] as const satisfies readonly Modulo[];

/**
 * Las cuarenta claves de hoja, como tipo.
 *
 * Es lo que ata las definiciones al arbol **en tiempo de compilacion**: `PANTALLAS` se declara
 * `Record<ClaveDeHoja, Pantalla>`, de modo que una hoja sin pantalla no compila y una pantalla
 * con una clave que no es de ninguna hoja, tampoco.
 */
export type ClaveDeHoja = (typeof ARBOL)[number]['hojas'][number]['clave'];

/** El slug de cada uno de los diez modulos, como tipo. */
export type SlugDeModulo = (typeof ARBOL)[number]['slug'];

/**
 * Las cuarenta claves en el orden del arbol.
 *
 * En ese orden y no ordenadas alfabeticamente: el orden del arbol es el que se dibuja, y es el
 * que la guarda compara contra el artboard.
 */
export const CLAVES_DE_HOJA: readonly ClaveDeHoja[] = ARBOL.flatMap((modulo) =>
  modulo.hojas.map((hoja) => hoja.clave),
);
