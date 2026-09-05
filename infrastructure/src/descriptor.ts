/**
 * El descriptor de infraestructura de `rentas` (`ADR-0031` §2).
 *
 * Contribuyentes, declaraciones juradas, determinacion, cuenta corriente, valores,
 * fiscalizacion, coactiva, sanciones y licencias.
 *
 * ## Que es esto, y por que son funciones puras
 *
 * `infrastructure` lo importa, **fija su version**, lo compone y **lo audita con las mismas
 * reglas que audita los suyos**. Eso solo es posible porque lo que hay aqui son **funciones
 * puras que devuelven objetos planos de Kubernetes**: `infrastructure` recibe datos, puede
 * leerlos y puede negarse a aplicarlos. Si este archivo creara recursos —un `pulumi.Input`, una
 * conexion, una lectura de `process.env`—, la auditoria no tendria nada que leer y la unica
 * garantia seria la confianza en quien lo escribio.
 *
 * ## Lo que este archivo NO puede hacer
 *
 * Cinco cosas, y `infrastructure` las rechaza: una ruta fuera de su prefijo, **la etiqueta de la
 * imagen** —la pone `infrastructure`, o cada liberacion vuelve a ser un `pulumi up`—, privilegios
 * sobre la base de otro sistema, un `Deployment` sin limites ni sondas, y un `Secret` en claro.
 *
 * ## Dos perfiles, un artefacto
 *
 * `ADR-0003` sigue siendo cierto DENTRO de este sistema: el monolito modular con sus contextos
 * dentro, en un solo artefacto que arranca con dos perfiles —`web`, que atiende, y `batch`, que
 * corre las emisiones masivas—. Lo que `ADR-0029` reemplaza es el monolito de los DOCE contextos,
 * no la forma de este.
 *
 * El perfil `batch` **no declara puertos ni sondas HTTP**: no atiende peticiones
 * (`web-application-type: none`), y un puerto ahi es una superficie que nadie pidio. Lo exige la
 * auditoria heredada de `infrastructure`, no una regla de este archivo.
 *
 * ## Todavia no hay codigo de negocio
 *
 * Los `Deployment` apuntan a imagenes que **aun no existen**. Es correcto en esta etapa: describe
 * como se desplegaria este sistema, y no se despliega nada.
 */

import type {
  BaseDeDatosDeclarada,
  ClaveDeclarada,
  Contenedor,
  CronJob,
  DescriptorDeSistema,
  EntornoDelDescriptor,
  Manifiesto,
  NetworkPolicy,
  PanelDeclarado,
  ReglaDeAlerta,
  VariableDeEntorno,
} from "@sgtm/infra-contrato";

const SISTEMA = "rentas";

/** La imagen del migrador: el otro objetivo del mismo `Dockerfile` (C-14, punto 1). */
const MIGRADOR = `${SISTEMA}-migrador`;

/** Su base, en el motor de la plataforma. Una por sistema (ADR-0029, ADR-0032). */
const URL_DE_LA_BASE = `jdbc:postgresql://postgres:5432/${SISTEMA}`;

/**
 * Lo que piden los Jobs de un solo uso —migrar e implantar— y los procesos por lotes.
 *
 * Mismos `limits` que el perfil web y `requests` mas bajos, que es el reparto que
 * `RECURSOS.arranque` del monolito documenta desde el 2026-08-26: el `request` es lo que el
 * planificador **reserva y bloquea**, y estos Jobs corren a la vez que todos los `Deployment`
 * durante un `pulumi up`. Con el nodo justo, un `request` alto no es lentitud: es que no entran,
 * y como llevan la clase `lote` —la mas baja del cluster— no pueden desalojar a nadie para
 * hacerlo. Nadie cede y el despliegue se cuelga (`capacidad.ts`, issue #252).
 */
/**
 * La ventana del perfil `batch`: 02:00 hora de Peru (UTC-5), o sea 07:00 UTC.
 *
 * La MISMA que `Aplicacion.ts` le da al lote del monolito, y por lo mismo: con un solo nodo, lo
 * que corre de madrugada no compite con la ventanilla (INF-01 §2).
 */
const VENTANA_DE_LOTE = "0 7 * * *";

const RECURSOS_DE_ARRANQUE = {
  requests: { cpu: "50m", memory: "256Mi" },
  limits: { cpu: "1", memory: "1Gi" },
};

/** La conexion de la aplicacion: `sgtm_app` y solo `sgtm_app` (ARQ-03 §4). */
function credencialesDeLaAplicacion(e: EntornoDelDescriptor): VariableDeEntorno[] {
  return [
    { name: "SGTM_DB_URL", value: URL_DE_LA_BASE },
    { name: "SGTM_DB_USUARIO", value: "sgtm_app" },
    {
      name: "SGTM_DB_CLAVE",
      valueFrom: { secretKeyRef: { name: e.secretoDe("app"), key: "clave" } },
    },
  ];
}

/**
 * El contenedor del migrador: **la imagen del migrador, no la de la aplicacion** (C-14, punto 1).
 *
 * Lee `SGTM_DB_OWNER_USUARIO` y `SGTM_DB_OWNER_CLAVE` —lo dice el `main` de
 * `kamayuk.rentas.esquema.Migrador`, que rechaza argumentos a proposito para que una
 * clave no quede en el historial del proceso—, y **no** `SGTM_DB_USUARIO`, que es lo que este
 * descriptor ponia hasta C-14 sobre la imagen de la aplicacion: aquello arrancaba el proceso web
 * con las credenciales de `sgtm_owner` y con `spring.flyway.enabled: false`, o sea DDL al alcance
 * de un servidor HTTP y ninguna migracion aplicada.
 */
function contenedorDelMigrador(e: EntornoDelDescriptor): Contenedor {
  return {
    name: "migrador",
    image: e.imagenDe(MIGRADOR),
    env: [
      { name: "SGTM_DB_URL", value: URL_DE_LA_BASE },
      // Migrar es lo unico que corre como `sgtm_owner`: es el unico rol con DDL.
      { name: "SGTM_DB_OWNER_USUARIO", value: "sgtm_owner" },
      {
        name: "SGTM_DB_OWNER_CLAVE",
        valueFrom: { secretKeyRef: { name: e.secretoDe("owner"), key: "clave" } },
      },
    ],
    resources: RECURSOS_DE_ARRANQUE,
    securityContext: SEGURIDAD,
  };
}

/** Las propiedades de `DatosDeImplantacion`, tal como Spring las lee del entorno. */
function variablesDeImplantacion(e: EntornoDelDescriptor): VariableDeEntorno[] {
  const i = e.implantacion;
  return [
    { name: "SPRING_PROFILES_ACTIVE", value: "batch" },
    ...credencialesDeLaAplicacion(e),
    { name: "KAMAYUK_IMPLANTACION_UBIGEO", value: i.ubigeo },
    { name: "KAMAYUK_IMPLANTACION_NOMBRE", value: i.nombre },
    { name: "KAMAYUK_IMPLANTACION_TIPO", value: i.tipo },
    // No crea ninguna contrasena: la credencial vive en Keycloak, y esta cuenta tiene que ser
    // la misma que exista alli.
    { name: "KAMAYUK_IMPLANTACION_ADMINISTRADOR", value: i.administrador },
    { name: "KAMAYUK_IMPLANTACION_NOMBREDELADMINISTRADOR", value: i.nombreDelAdministrador },
    { name: "KAMAYUK_IMPLANTACION_ESDEMOSTRACION", value: String(i.esDemostracion) },
    { name: "KAMAYUK_IMPLANTACION_URL", value: URL_DE_LA_BASE },
    // OWNERCLAVE sin guion bajo: en una variable de entorno el `_` se traduce a punto, asi que
    // `KAMAYUK_IMPLANTACION_OWNER_CLAVE` seria `kamayuk.implantacion.owner.clave` y no
    // `owner-clave`. Es la misma nota que lleva el Job del monolito, y por el mismo motivo.
    {
      name: "KAMAYUK_IMPLANTACION_OWNERCLAVE",
      valueFrom: { secretKeyRef: { name: e.secretoDe("owner"), key: "clave" } },
    },
  ];
}

/** Lo que pide y lo que puede gastar. Sin esto, el planificador no reserva nada. */
const RECURSOS = {
  requests: { cpu: "100m", memory: "512Mi" },
  limits: { cpu: "1", memory: "1Gi" },
};

/**
 * `timeoutSeconds` entre 3 y 5, y no es decorativo: el valor por omision del kubelet es **1 s**,
 * y en un nodo ocupado un contenedor sano pero atareado no contesta en 1 s. Tres fallos de la
 * sonda de vida y lo mata con codigo 143, que se parece a un OOM sin serlo.
 */
function sondas() {
  return {
    startupProbe: {
      timeoutSeconds: 3,
      httpGet: { path: "/actuator/health", port: 8080 },
      failureThreshold: 30,
      periodSeconds: 5,
    },
    readinessProbe: {
      timeoutSeconds: 3,
      httpGet: { path: "/actuator/health/readiness", port: 8080 },
      periodSeconds: 10,
    },
    livenessProbe: {
      timeoutSeconds: 5,
      httpGet: { path: "/actuator/health/liveness", port: 8080 },
      periodSeconds: 20,
    },
  };
}

/** El endurecimiento que no admite excepcion (issue #157). */
const SEGURIDAD = {
  runAsNonRoot: true,
  allowPrivilegeEscalation: false as const,
  capabilities: { drop: ["ALL"] as ["ALL"] },
};

function despliegueDelPerfil(e: EntornoDelDescriptor, perfil: string, atiendeHttp: boolean): Manifiesto[] {
  const nombre = `kamayuk-${SISTEMA}-${perfil}`;
  const etiquetas = { ...e.etiquetas, componente: SISTEMA, perfil };
  const manifiestos: Manifiesto[] = [
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: nombre, namespace: e.namespace, labels: etiquetas },
      spec: {
        replicas: 1,
        // `maxSurge: 0` obliga a matar el pod viejo antes de crear el nuevo: en un nodo sin
        // holgura, un pod extra durante el despliegue no agenda y el rollout se cuelga.
        strategy: { type: "RollingUpdate", rollingUpdate: { maxSurge: 0, maxUnavailable: 1 } },
        selector: { matchLabels: { app: nombre } },
        template: {
          metadata: { labels: { ...etiquetas, app: nombre } },
          spec: {
            priorityClassName: e.prioridadDe(perfil === "batch" ? "lote" : "servicio"),
            containers: [
              {
                name: SISTEMA,
                // La etiqueta la pone `infrastructure`. Ver la cabecera.
                image: e.imagenDe(SISTEMA),
                env: [
                  { name: "SPRING_PROFILES_ACTIVE", value: perfil },
                  { name: "SGTM_DB_URL", value: URL_DE_LA_BASE },
                  { name: "SGTM_DB_USUARIO", value: "sgtm_app" },
                  {
                    name: "SGTM_DB_CLAVE",
                    valueFrom: { secretKeyRef: { name: e.secretoDe("app"), key: "clave" } },
                  },
                  // Sin el emisor la aplicacion se niega a arrancar, y es deliberado: un backend
                  // que atiende sin poder validar un token responde a la sonda, se declara sano y
                  // no atiende a nadie (ADR-0005).
                  { name: "SGTM_OIDC_EMISOR", value: e.plataforma.emisor },
                  // El JWKS por la red INTERNA, cruzando el namespace de la plataforma (C-14).
                  // Hasta aqui este descriptor apuntaba las dos al nombre publico: el backend
                  // habria salido al ingreso para volver a entrar, y con la politica de egreso
                  // declarada —que nombra el pod de identidad, no internet— no habria salido en
                  // absoluto. Todo token invalido, por un motivo que no se parece a su causa.
                  { name: "SGTM_OIDC_JWKS", value: e.plataforma.jwks },
                ],
                ...(atiendeHttp ? { ports: [{ name: "http", containerPort: 8080 }] } : {}),
                resources: RECURSOS,
                ...(atiendeHttp ? sondas() : {}),
                securityContext: SEGURIDAD,
              },
            ],
          },
        },
      },
    },
  ];
  if (atiendeHttp) {
    manifiestos.push({
      apiVersion: "v1",
      kind: "Service",
      metadata: { name: nombre, namespace: e.namespace, labels: etiquetas },
      spec: {
        type: "ClusterIP",
        selector: { app: nombre },
        ports: [{ name: "http", port: 80, targetPort: 8080 }],
      },
    });
  }
  return manifiestos;
}

export const rentas: DescriptorDeSistema = {
  sistema: SISTEMA,
  prefijo: SISTEMA,
  // DOS imagenes, y son dos objetivos del mismo `Dockerfile` (C-14, punto 1): las
  // credenciales de `sgtm_owner` existen durante la migracion y desaparecen con ella.
  imagenes: [SISTEMA, MIGRADOR],

  /**
   * Su base y sus roles. **Solo la suya**: pedir privilegios sobre la de otro sistema es una
   * base compartida disfrazada, y deja el aislamiento entre municipalidades en una promesa.
   *
   * `superusuario: false` no es una formalidad: un superusuario OMITE RLS incluso con
   * `FORCE ROW LEVEL SECURITY` (DAT-01 §0, hallazgo 1).
   */
  baseDeDatos(): BaseDeDatosDeclarada {
    return {
      nombre: SISTEMA,
      roles: [
        { nombre: "sgtm_owner", sobre: [SISTEMA], privilegios: ["ALL"], superusuario: false },
        {
          nombre: "sgtm_app",
          sobre: [SISTEMA],
          privilegios: ["SELECT", "INSERT", "UPDATE"],
          superusuario: false,
        },
        { nombre: "sgtm_readonly", sobre: [SISTEMA], privilegios: ["SELECT"], superusuario: false },
      ],
    };
  },

  despliegue: (e) => [...despliegueDelPerfil(e, "web", true),
    ...despliegueDelPerfil(e, "batch", false)],

  /**
   * Su Job de migracion. Cada base tiene sus migraciones y su prueba de aislamiento.
   *
   * **El nombre lleva la version**, y no es cosmetico: un `Job` de Kubernetes es INMUTABLE —su
   * plantilla de pod no se puede modificar—, asi que un nombre fijo hace fallar el `pulumi up` de
   * la version siguiente al intentar actualizarlo, porque la imagen lleva la etiqueta dentro. El
   * monolito lo resolvio asi desde el issue #150; este descriptor nacio sin ello.
   */
  migracion(e): Manifiesto[] {
    const nombre = e.nombreConVersion(`kamayuk-${SISTEMA}-migracion`);
    const etiquetas = { ...e.etiquetas, componente: SISTEMA };
    return [
      {
        apiVersion: "batch/v1",
        kind: "Job",
        metadata: { name: nombre, namespace: e.namespace, labels: etiquetas },
        spec: {
          backoffLimit: 3,
          ttlSecondsAfterFinished: 86400,
          template: {
            metadata: { labels: { ...etiquetas, app: nombre } },
            spec: {
              restartPolicy: "Never",
              priorityClassName: e.prioridadDe("lote"),
              containers: [contenedorDelMigrador(e)],
            },
          },
        },
      },
    ];
  },

  /**
   * Su Job de implantacion: la fila de `municipalidad` en SU base, y la copia local de usuarios,
   * grupos y accesos (C-7 §2.3, C-14 punto 4).
   *
   * ## Por que el migrador va de contenedor de inicializacion
   *
   * Un `Deployment` no sabe esperar a un `Job` y Kubernetes no tiene `dependsOn`. El monolito lo
   * resuelve con un contenedor que consulta la base con `psql` hasta ver `flyway_schema_history`;
   * aqui esa salida no existe, porque un descriptor solo puede nombrar SUS imagenes —la
   * prohibicion (b)— y la del motor no es suya.
   *
   * Lo que se hace es mas fuerte que esperar: se **asegura** que el esquema esta, corriendo el
   * migrador, que es idempotente y devuelve cero cuando no falta nada. Si el Job de migracion aun
   * no termino, Flyway toma su propio candado y uno de los dos espera al otro; cuando este
   * contenedor sale con exito **el esquema ESTA**, que es lo que la espera del monolito solo
   * puede suponer.
   */
  implantacion(e): Manifiesto[] {
    const nombre = e.nombreConVersion(`kamayuk-${SISTEMA}-implantacion`);
    const etiquetas = { ...e.etiquetas, componente: SISTEMA };
    return [
      {
        apiVersion: "batch/v1",
        kind: "Job",
        metadata: { name: nombre, namespace: e.namespace, labels: etiquetas },
        spec: {
          backoffLimit: 3,
          ttlSecondsAfterFinished: 86400,
          template: {
            metadata: { labels: { ...etiquetas, app: nombre } },
            spec: {
              restartPolicy: "Never",
              priorityClassName: e.prioridadDe("lote"),
              initContainers: [contenedorDelMigrador(e)],
              containers: [
                {
                  name: "implantacion",
                  // La MISMA imagen que la aplicacion, con el perfil `batch` (ADR-0003: un
                  // artefacto, dos perfiles). No abre puerto ninguno.
                  image: e.imagenDe(SISTEMA),
                  env: variablesDeImplantacion(e),
                  resources: RECURSOS_DE_ARRANQUE,
                  securityContext: SEGURIDAD,
                },
              ],
            },
          },
        },
      },
    ];
  },

  /**
   * Sus procesos por lotes con ventana (C-8, C-14 punto 3).
   *
   * **El ingestor de los hechos de `catastro`**, que es la mitad receptora del camino que C-8
   * midio de extremo a extremo. Se conecta con `rol_ingestor_catastro` en su propio pool —`V4` y
   * `V5` no le dan a `sgtm_app` mas que `SELECT` sobre las cuatro proyecciones— y viene a buscar
   * los hechos al buzon de `catastro` por HTTP, con acuse.
   *
   * ## Nace SUSPENDIDO, y hay que decir por que
   *
   * El feed de `catastro` esta detras de `@RequiereAcceso("consulta_fichas")`, y **no hay identidad
   * de servicio**: ADR-0028 §2 —el intercambio de token de RFC 8693— no esta implementado en
   * ninguno de los cuatro repositorios (C-8, hueco 3). Sin credencial, la llamada sale sin
   * `Authorization` y `catastro` la rechaza con 401, que es el comportamiento correcto. Un
   * `CronJob` activo en ese estado fallaria cada noche y su alerta seria ruido.
   *
   * Lo que se declara aqui es **la ventana, los limites y la configuracion entera**, que es lo que
   * C-8 §huecos 2 decia que faltaba: «mientras el descriptor no tenga campo, el ingestor no se
   * puede desplegar». Quitar el `suspend` es una linea el dia que exista la identidad de servicio.
   * Es el mismo trato que `Aplicacion.ts` le da al `CronJob` de `lote` del monolito.
   */
  lotes(e): Manifiesto[] {
    const nombre = `kamayuk-${SISTEMA}-ingestor`;
    const etiquetas = { ...e.etiquetas, componente: SISTEMA };
    const ingestor: CronJob = {
      apiVersion: "batch/v1",
      kind: "CronJob",
      metadata: { name: nombre, namespace: e.namespace, labels: etiquetas },
      spec: {
        schedule: VENTANA_DE_LOTE,
        // Ver la cabecera: no hay identidad de servicio todavia (ADR-0028 §2).
        suspend: true,
        concurrencyPolicy: "Forbid",
        successfulJobsHistoryLimit: 3,
        failedJobsHistoryLimit: 3,
        jobTemplate: {
          spec: {
            backoffLimit: 1,
            template: {
              metadata: { labels: { ...etiquetas, app: nombre } },
              spec: {
                restartPolicy: "Never",
                priorityClassName: e.prioridadDe("lote"),
                containers: [
                  {
                    name: "ingestor",
                    image: e.imagenDe(SISTEMA),
                    env: [
                      { name: "SPRING_PROFILES_ACTIVE", value: "batch" },
                      ...credencialesDeLaAplicacion(e),
                      // El pool del ingestor: OTRO rol, en el mismo proceso. `spring.datasource.url`
                      // la comparte; el usuario y la clave no.
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_USUARIO",
                        value: "rol_ingestor_catastro",
                      },
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_CLAVE",
                        valueFrom: {
                          secretKeyRef: { name: e.secretoDe("ingestor"), key: "clave" },
                        },
                      },
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_MUNICIPALIDAD",
                        value: String(e.implantacion.municipalidadId),
                      },
                      // A quien se avisa cuando un hecho no se puede aplicar. Del AMBIENTE, y
                      // `ResponsableDeLaProyeccion` exige que el canal sea entregable: un hecho
                      // apartado bloquea la cola detras de el (C-8 §4.2).
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_RESPONSABLE",
                        value: e.operacion.responsable,
                      },
                      { name: "KAMAYUK_RENTAS_INGESTOR_CANAL", value: e.operacion.canal },
                      // El buzon de `catastro`, en SU namespace. La direccion se compone con
                      // `namespaceDe` y no a mano: dos copias de la convencion se separan.
                      {
                        name: "KAMAYUK_CATASTRO_URL",
                        value: `http://kamayuk-catastro-web.${e.namespaceDe("catastro")}`,
                      },
                      // La credencial con que se pide el feed. HOY NO SIRVE: no hay identidad de
                      // servicio (ADR-0028 §2), y por eso el CronJob nace suspendido.
                      {
                        name: "KAMAYUK_CATASTRO_CREDENCIAL",
                        valueFrom: {
                          secretKeyRef: { name: e.secretoDe("catastro"), key: "clave" },
                        },
                      },
                    ],
                    resources: RECURSOS_DE_ARRANQUE,
                    securityContext: SEGURIDAD,
                  },
                ],
              },
            },
          },
        },
      },
    };
    return [ingestor];
  },

  /** Sus rutas, **bajo su prefijo**. Reclamar el de otro no falla: se lo queda. */
  ingreso(e): Manifiesto[] {
    return [
      {
        apiVersion: "traefik.io/v1alpha1",
        kind: "IngressRoute",
        metadata: { name: `kamayuk-${SISTEMA}`, namespace: e.namespace, labels: e.etiquetas },
        spec: {
          // Solo `websecure`: 80 redirige, no coexiste. Un formulario de acceso servido por
          // HTTP es una credencial regalada.
          entryPoints: ["websecure"],
          routes: [
            {
              match: `Host(\`${e.dominio}\`) && PathPrefix(\`/${SISTEMA}\`)`,
              kind: "Rule",
              services: [{ name: `kamayuk-${SISTEMA}-web`, port: 80 }],
            },
          ],
          tls: { certResolver: "letsencrypt" },
        },
      },
    ];
  },

  /**
   * A quien puede llamar. **El egreso declarado ES el grafo de dependencias** (ADR-0029), y
   * tiene que coincidir con ARQ-01 reducido a cuatro nodos. Cada arista, con su motivo:
   *
   * - **`catastro`**: la valuacion sellada del ejercicio y las fichas que la sustentan (ADR-0027)
   * - **`normativa`**: el conjunto sellado con que determina, una vez por corrida (ADR-0025 §1)
   * - **`caja`**: las ordenes de cobro que emite, y el recibo que acredita un tramite pagado
   */
  egreso(e): NetworkPolicy[] {
    return [
      {
        apiVersion: "networking.k8s.io/v1",
        kind: "NetworkPolicy",
        metadata: {
          name: `kamayuk-${SISTEMA}-egreso`,
          namespace: e.namespace,
          labels: e.etiquetas,
        },
        spec: {
          podSelector: { matchLabels: { componente: SISTEMA } },
          policyTypes: ["Egress"],
          egress: [
            // Su motor. Los cuatro lo necesitan; cada uno a SU base.
            {
              to: [
                {
                  // El `namespaceSelector` NO es un adorno: desde ADR-0031 cada sistema tiene su
                  // namespace, y un `podSelector` a secas selecciona pods del MISMO. Sin el, esta
                  // regla no abre nada y el sintoma es trafico denegado con una politica que dice
                  // permitirlo (C-14, punto 3).
                  namespaceSelector: {
                    matchLabels: { "kubernetes.io/metadata.name": e.plataforma.namespace },
                  },
                  podSelector: { matchLabels: { componente: "postgres" } },
                },
              ],
              ports: [{ protocol: "TCP", port: 5432 }],
            },
            // La identidad: valida los tokens que recibe.
            {
              to: [
                {
                  // El `namespaceSelector` NO es un adorno: desde ADR-0031 cada sistema tiene su
                  // namespace, y un `podSelector` a secas selecciona pods del MISMO. Sin el, esta
                  // regla no abre nada y el sintoma es trafico denegado con una politica que dice
                  // permitirlo (C-14, punto 3).
                  namespaceSelector: {
                    matchLabels: { "kubernetes.io/metadata.name": e.plataforma.namespace },
                  },
                  podSelector: { matchLabels: { componente: "identidad" } },
                },
              ],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // catastro: la valuacion sellada del ejercicio y las fichas que la sustentan (ADR-0027)
            {
              to: [
                {
                  namespaceSelector: {
                    matchLabels: { "kubernetes.io/metadata.name": e.namespaceDe("catastro") },
                  },
                  podSelector: { matchLabels: { componente: "catastro" } },
                },
              ],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // normativa: el conjunto sellado con que determina, una vez por corrida (ADR-0025 §1)
            {
              to: [
                {
                  namespaceSelector: {
                    matchLabels: { "kubernetes.io/metadata.name": e.namespaceDe("normativa") },
                  },
                  podSelector: { matchLabels: { componente: "normativa" } },
                },
              ],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // caja: las ordenes de cobro que emite, y el recibo que acredita un tramite pagado
            {
              to: [
                {
                  namespaceSelector: {
                    matchLabels: { "kubernetes.io/metadata.name": e.namespaceDe("caja") },
                  },
                  podSelector: { matchLabels: { componente: "caja" } },
                },
              ],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
          ],
        },
      },
    ];
  },

  alertas: (): ReglaDeAlerta[] => [
    {
      alert: `${SISTEMA}SinResponder`,
      expr: `up{job="kamayuk-${SISTEMA}"} == 0`,
      for: "5m",
      labels: { severity: "critical", sistema: SISTEMA },
      annotations: {
        summary: `${SISTEMA} lleva 5 minutos sin responder`,
        description: "Con un solo nodo no hay a donde mover la carga: hay que mirar el pod.",
      },
    },
  ],

  panel: (): PanelDeclarado => ({
    nombre: `kamayuk-${SISTEMA}`,
    // Vacio a proposito: un panel se llena con las metricas que el sistema publica, y todavia
    // no publica ninguna. Inventarle paneles ahora seria dibujar cifras que nadie emite.
    json: { title: `Kamayuk · ${SISTEMA}`, panels: [] },
  }),

  /** Su inventario de claves: metadatos, **nunca un valor** (INF-06, ADR-0011 §3). */
  claves: (): ClaveDeclarada[] => [
    {
      nombre: `kamayuk-${SISTEMA}-app`,
      clave: "clave",
      rol: "sgtm_app",
      rotacion: "trimestral",
      proposito: `la conexion de ${SISTEMA} a su base`,
    },
    {
      nombre: `kamayuk-${SISTEMA}-owner`,
      clave: "clave",
      rol: "sgtm_owner",
      rotacion: "anual",
      proposito: `migrar la base de ${SISTEMA}; es el unico rol con DDL`,
    },
    {
      // El pool del ingestor (C-8): OTRO rol dentro del mismo proceso. `V4` y `V5` no le dan a
      // `sgtm_app` mas que `SELECT` sobre las cuatro proyecciones, asi que quien las escribe
      // tiene que ser otro — y ese otro no atiende peticiones.
      nombre: `kamayuk-${SISTEMA}-ingestor`,
      clave: "clave",
      rol: "rol_ingestor_catastro",
      rotacion: "trimestral",
      proposito: "escribir la proyeccion del padron de catastro; no atiende peticiones",
    },
    {
      // La credencial con que el ingestor pide el feed de `catastro`.
      //
      // **Se declara y HOY NO SIRVE**, y hay que decirlo aqui y no solo en el CronJob: el feed
      // esta detras de `@RequiereAcceso("consulta_fichas")` y no hay identidad de servicio
      // —ADR-0028 §2, RFC 8693— en ninguno de los cuatro repositorios (C-8, hueco 3). Un valor
      // generado por `bootstrap-secretos.sh` es una cadena aleatoria, no un token que Keycloak
      // haya emitido, asi que `catastro` la rechaza con 401. Por eso el CronJob nace suspendido.
      nombre: `kamayuk-${SISTEMA}-catastro`,
      clave: "clave",
      rotacion: "trimestral",
      proposito: "pedir el buzon de hechos de catastro; sin identidad de servicio todavia",
    },
  ],
};

export default rentas;
