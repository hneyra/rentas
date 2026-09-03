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
  DescriptorDeSistema,
  EntornoDelDescriptor,
  Manifiesto,
  NetworkPolicy,
  PanelDeclarado,
  ReglaDeAlerta,
} from "@sgtm/infra-contrato";

const SISTEMA = "rentas";

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
                  { name: "SGTM_DB_URL", value: `jdbc:postgresql://postgres:5432/${SISTEMA}` },
                  { name: "SGTM_DB_USUARIO", value: "sgtm_app" },
                  {
                    name: "SGTM_DB_CLAVE",
                    valueFrom: { secretKeyRef: { name: e.secretoDe("app"), key: "clave" } },
                  },
                  // Sin el emisor la aplicacion se niega a arrancar, y es deliberado: un backend
                  // que atiende sin poder validar un token responde a la sonda, se declara sano y
                  // no atiende a nadie (ADR-0005).
                  { name: "SGTM_OIDC_EMISOR", value: `https://${e.dominio}/keycloak/realms/sgtm` },
                  { name: "SGTM_OIDC_JWKS", value: `https://${e.dominio}/keycloak/realms/sgtm/protocol/openid-connect/certs` },
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
  imagenes: [SISTEMA],

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

  /** Su Job de migracion. Cada base tiene sus migraciones y su prueba de aislamiento. */
  migracion(e): Manifiesto[] {
    const nombre = `kamayuk-${SISTEMA}-migracion`;
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
              containers: [
                {
                  name: "migrador",
                  image: e.imagenDe(SISTEMA),
                  env: [
                    { name: "SGTM_DB_URL", value: `jdbc:postgresql://postgres:5432/${SISTEMA}` },
                    // Migrar es lo unico que corre como `sgtm_owner`: es el unico rol con DDL.
                    { name: "SGTM_DB_USUARIO", value: "sgtm_owner" },
                    {
                      name: "SGTM_DB_CLAVE",
                      valueFrom: { secretKeyRef: { name: e.secretoDe("owner"), key: "clave" } },
                    },
                  ],
                  resources: RECURSOS,
                  securityContext: SEGURIDAD,
                },
              ],
            },
          },
        },
      },
    ];
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
              to: [{ podSelector: { matchLabels: { componente: "postgres" } } }],
              ports: [{ protocol: "TCP", port: 5432 }],
            },
            // La identidad: valida los tokens que recibe.
            {
              to: [{ podSelector: { matchLabels: { componente: "identidad" } } }],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // catastro: la valuacion sellada del ejercicio y las fichas que la sustentan (ADR-0027)
            {
              to: [{ podSelector: { matchLabels: { componente: "catastro" } } }],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // normativa: el conjunto sellado con que determina, una vez por corrida (ADR-0025 §1)
            {
              to: [{ podSelector: { matchLabels: { componente: "normativa" } } }],
              ports: [{ protocol: "TCP", port: 8080 }],
            },
            // caja: las ordenes de cobro que emite, y el recibo que acredita un tramite pagado
            {
              to: [{ podSelector: { matchLabels: { componente: "caja" } } }],
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
  ],
};

export default rentas;
