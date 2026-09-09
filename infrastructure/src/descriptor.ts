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
} from "@kamayuk/infra-contrato";

const SISTEMA = "rentas";

/** La imagen del migrador: el otro objetivo del mismo `Dockerfile` (C-14, punto 1). */
const MIGRADOR = `${SISTEMA}-migrador`;

/**
 * La imagen de la interfaz (#44): `frontend/Dockerfile`, un nginx sirviendo el `dist/` de
 * `rentas-web`.
 *
 * **Nunca `rentas-web`.** Ese nombre YA ES otra cosa en este mismo archivo: el `Deployment` y el
 * `Service` del BACKEND con el perfil `web` de Spring, que produce
 * `despliegueDelPerfil(e, "web", true)` como `kamayuk-${SISTEMA}-web`. Reutilizarlo dejaria dos
 * artefactos del producto llamados igual —uno sirve la API y el otro archivos estaticos— y
 * cualquier frase del tipo «despliega `kamayuk-rentas-web`» pasaria a tener dos respuestas.
 */
const INTERFAZ = `${SISTEMA}-interfaz`;

/** El nombre de sus tres recursos. Sale una vez y se usa en seis sitios. */
const NOMBRE_DE_LA_INTERFAZ = `kamayuk-${SISTEMA}-interfaz`;

/**
 * Su etiqueta `componente`, **distinta de la del backend**, y no es cosmetica.
 *
 * `egreso()` selecciona por `componente: rentas` los pods que pueden hablar con el motor, con la
 * identidad y con los tres sistemas vecinos. Si la interfaz llevara esa misma etiqueta heredaria
 * las cinco aristas, y un nginx de archivos estaticos con salida a PostgreSQL es superficie que
 * nadie pidio (AC-7 de #44). Con etiqueta propia sus dos politicas se escriben aparte y dicen lo
 * que de verdad necesita, que es mucho menos.
 */
const COMPONENTE_DE_LA_INTERFAZ = INTERFAZ;

/**
 * El cliente publico de Keycloak con el que la interfaz entra.
 *
 * **Se escribe aqui porque `EntornoDelDescriptor` no lo publica**, y eso es lo que hay hoy: el
 * realm lo describe `infrastructure` y este contrato entrega el emisor (`plataforma.emisor`) pero
 * no el cliente. Mientras siga asi, cambiar de cliente es cambiar esta linea.
 *
 * **Y hay un hueco declarado que este repositorio NO puede cerrar (AC-9 de #44)**: `sgtm-backoffice`
 * admite `http://localhost:5173/*` y nada mas, medido. `identidad.ts` compone el `redirect_uri`
 * como `origin + '/'`, o sea `https://<dominio>/` una vez desplegado, que ese cliente **no
 * admite** — el rebote acaba en «Invalid parameter: redirect_uri» y no entra nadie. Ampliar
 * `redirectUris` es del dueno del realm, que es `infrastructure`. Se nombra aqui para que quien
 * despliegue lo lea antes y no el dia del despliegue.
 */
const CLIENTE_OIDC_DE_LA_INTERFAZ = "sgtm-backoffice";

/**
 * Su base, en el motor de la plataforma. Una por sistema (ADR-0029, ADR-0032).
 *
 * **El anfitrion lo pide, no lo escribe** (C-17, punto 1). Hasta aqui esta linea decia
 * `jdbc:postgresql://postgres:5432/...`, y en Kubernetes **no hay ningun `Service` llamado
 * `postgres`**: ese nombre viene del `compose.yaml` local. El servicio real es
 * `kamayuk-<ambiente>-postgres` y vive en el namespace de la PLATAFORMA, asi que ni siquiera un
 * nombre corto correcto resolveria desde aqui. Lo medido fue `UnknownHostException` en los ocho
 * Jobs y en los `Deployment` de los cuatro: nada del producto podia arrancar.
 *
 * Componerlo aqui seria repetir dos convenciones que son de `infrastructure` —como se nombra un
 * recurso del ambiente y como se llama su namespace—, y dos copias de una convencion se separan.
 * Lo que si es de este sistema, y por eso se escribe aqui, es el nombre de su base.
 */
function urlDeLaBase(e: EntornoDelDescriptor): string {
  return `jdbc:postgresql://${e.plataforma.motor}/${SISTEMA}`;
}

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

/** La conexion de la aplicacion: `kamayuk_app` y solo `kamayuk_app` (ARQ-03 §4). */
function credencialesDeLaAplicacion(e: EntornoDelDescriptor): VariableDeEntorno[] {
  return [
    { name: "KAMAYUK_DB_URL", value: urlDeLaBase(e) },
    { name: "KAMAYUK_DB_USUARIO", value: "kamayuk_app" },
    {
      name: "KAMAYUK_DB_CLAVE",
      valueFrom: { secretKeyRef: { name: e.secretoDe("app"), key: "clave" } },
    },
  ];
}

/**
 * El contenedor del migrador: **la imagen del migrador, no la de la aplicacion** (C-14, punto 1).
 *
 * Lee `KAMAYUK_DB_OWNER_USUARIO` y `KAMAYUK_DB_OWNER_CLAVE` —lo dice el `main` de
 * `kamayuk.rentas.esquema.Migrador`, que rechaza argumentos a proposito para que una
 * clave no quede en el historial del proceso—, y **no** `KAMAYUK_DB_USUARIO`, que es lo que este
 * descriptor ponia hasta C-14 sobre la imagen de la aplicacion: aquello arrancaba el proceso web
 * con las credenciales de `kamayuk_owner` y con `spring.flyway.enabled: false`, o sea DDL al alcance
 * de un servidor HTTP y ninguna migracion aplicada.
 */
function contenedorDelMigrador(e: EntornoDelDescriptor): Contenedor {
  return {
    name: "migrador",
    image: e.imagenDe(MIGRADOR),
    env: [
      { name: "KAMAYUK_DB_URL", value: urlDeLaBase(e) },
      // Migrar es lo unico que corre como `kamayuk_owner`: es el unico rol con DDL.
      { name: "KAMAYUK_DB_OWNER_USUARIO", value: "kamayuk_owner" },
      {
        name: "KAMAYUK_DB_OWNER_CLAVE",
        valueFrom: { secretKeyRef: { name: e.secretoDe("owner"), key: "clave" } },
      },
    ],
    resources: RECURSOS_DE_ARRANQUE,
    securityContext: SEGURIDAD,
  };
}

/**
 * Las propiedades de `DatosDeImplantacion`, tal como Spring las lee del entorno.
 *
 * **El prefijo es `KAMAYUK_IMPLANTACION_`, y desde R-A/B lo es en los cuatro.** No es una
 * preferencia: `DatosDeImplantacion` de ESTE sistema declara
 * `@ConfigurationProperties("kamayuk.implantacion")` —y `RegistroDeMunicipalidadesJdbc` lee
 * `${kamayuk.implantacion.url}` y `${kamayuk.implantacion.owner-clave}`—. Hasta R-A/B `rentas`
 * leia `sgtm.implantacion`, porque era el monolito, y C-18 corrigio esa asimetria por el lado del
 * descriptor; R-A/B la deshace por el otro, renombrando la propiedad en el Java.
 *
 * Hasta C-18 este descriptor ponia el prefijo de los otros tres, y el sintoma **no se parece a
 * su causa**: `ImplantarMunicipalidad` esta condicionado a `@ConditionalOnProperty(
 * "kamayuk.implantacion.ubigeo")`, asi que el runner NO SE REGISTRA, el proceso arranca, no hace
 * nada y **sale con codigo 0**. El `Job` de Kubernetes queda `Complete` y la evidencia de C-17 lo
 * recoge asi —«kamayuk-rentas-implantacion … Complete 1/1 25s»—: una tarea que dice que si porque
 * no estaba mirando. Lo medido en el compose de C-18: `flyway_schema_history` con 13 filas y
 * `municipalidad` **vacia**, o sea `rentas` migrado y sin ninguna municipalidad — sin fila no hay
 * `municipalidad_id` que poner en ningun token, ni accesos sembrados, ni administrador, asi que a
 * `rentas` no puede entrar nadie.
 *
 * Lo que impide que vuelva a pasar es una guarda de `infrastructure` que LEE el
 * `@ConfigurationProperties` de cada sistema y lo compara con lo que su descriptor pone
 * (`prefijo-de-la-implantacion.test.ts`): el prefijo deja de estar escrito en dos sitios.
 */
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
    { name: "KAMAYUK_IMPLANTACION_URL", value: urlDeLaBase(e) },
    // OWNERCLAVE sin guion bajo: en una variable de entorno el `_` se traduce a punto, asi que
    // `KAMAYUK_IMPLANTACION_OWNER_CLAVE` seria `kamayuk.implantacion.owner.clave` y no `owner-clave`.
    // Es la misma nota que lleva el Job del monolito, y por el mismo motivo.
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

/**
 * Los tres sistemas vecinos que este backend consume, con el prefijo bajo el que cada uno sirve.
 *
 * <p>Escrito una sola vez y no tres, y el prefijo va aqui porque es parte de la direccion: los tres
 * clientes de Java componen `raiz + ruta` y una raiz sin prefijo daria un 404 en cada llamada.
 */
const VECINOS = ["caja", "catastro", "normativa"] as const;

/**
 * `KAMAYUK_<VECINO>_URL` para cada uno, apuntando a su `Service` en su namespace.
 *
 * Esto y el bloque equivalente de `despliegue/compose.yaml` **tienen que decir lo mismo**, y lo
 * sujeta `infra/verificaciones/compose-de-los-sistemas.test.ts`, que compara los NOMBRES de las
 * variables de los dos lados en las dos direcciones. Anadirlas solo en el compose pone en rojo la
 * guarda del repositorio hermano con «declara «KAMAYUK_CAJA_URL» y el descriptor no se la da».
 */
function urlesDeLosVecinos(e: EntornoDelDescriptor) {
  return VECINOS.map((vecino) => ({
    name: `KAMAYUK_${vecino.toUpperCase()}_URL`,
    value: `http://kamayuk-${vecino}-web.${e.namespaceDe(vecino)}/${vecino}/api/v1`,
  }));
}

/**
 * Lo que pide la interfaz, y es **mucho menos que el backend**: un nginx sirviendo archivos
 * estaticos no necesita 1 CPU ni 1 Gi.
 *
 * No son cifras inventadas: son las mismas que `convenciones.recursos.interfaz` de
 * `infrastructure` le da al nginx del monolito, que hace exactamente esto. Se copian y no se
 * importan porque un descriptor no puede depender de `infrastructure` —seria la dependencia al
 * reves de ADR-0031 §2—, y con un solo nodo lo que se reparte es el `request`: 50m frente a los
 * 100m del backend es la diferencia entre que este pod quepa al lado de todo lo demas o no.
 */
const RECURSOS_DE_LA_INTERFAZ = {
  requests: { cpu: "50m", memory: "64Mi" },
  limits: { cpu: "200m", memory: "128Mi" },
};

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
                  { name: "KAMAYUK_DB_URL", value: urlDeLaBase(e) },
                  { name: "KAMAYUK_DB_USUARIO", value: "kamayuk_app" },
                  {
                    name: "KAMAYUK_DB_CLAVE",
                    valueFrom: { secretKeyRef: { name: e.secretoDe("app"), key: "clave" } },
                  },
                  // Sin el emisor la aplicacion se niega a arrancar, y es deliberado: un backend
                  // que atiende sin poder validar un token responde a la sonda, se declara sano y
                  // no atiende a nadie (ADR-0005).
                  { name: "KAMAYUK_OIDC_EMISOR", value: e.plataforma.emisor },
                  // El JWKS por la red INTERNA, cruzando el namespace de la plataforma (C-14).
                  // Hasta aqui este descriptor apuntaba las dos al nombre publico: el backend
                  // habria salido al ingreso para volver a entrar, y con la politica de egreso
                  // declarada —que nombra el pod de identidad, no internet— no habria salido en
                  // absoluto. Todo token invalido, por un motivo que no se parece a su causa.
                  { name: "KAMAYUK_OIDC_JWKS", value: e.plataforma.jwks },
                  // Los tres vecinos (#25). Sin ellas `rentas` levanta, la sonda dice UP y tres de
                  // sus operaciones contestan 500 —el panel de recaudacion, los predios de un
                  // contribuyente y las senias del conjunto sellado—, porque las tres se declaran
                  // `@Value("${kamayuk.<sistema>.url:}")`, con cadena vacia por omision.
                  //
                  // La politica de egreso de `red()` ya nombraba los tres namespaces: lo que
                  // faltaba no era el permiso de red, era la direccion. Se compone con
                  // `namespaceDe` y no a mano, por lo mismo que el ingestor: dos copias de la
                  // convencion se separan.
                  //
                  // El `Service` de cada sistema escucha en el puerto 80 y su backend sirve bajo su
                  // propio prefijo, asi que la raiz lleva el prefijo dentro: los tres clientes
                  // componen `raiz + ruta`.
                  ...urlesDeLosVecinos(e),
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

/**
 * Las senias del ambiente que la interfaz lee **al arrancar**, servidas como un guion.
 *
 * <h2>El problema que resuelve, que es el unico de #44 que no es de despliegue</h2>
 *
 * Vite sustituye `import.meta.env.VITE_*` **al construir**, asi que todo lo que la interfaz
 * supiera por esa via quedaria horneado dentro de la imagen. Y una de esas cosas es la **URL del
 * emisor OIDC**, que no es la misma en el puesto de quien desarrolla que en la municipalidad.
 *
 * <h2>Por que NO se hace una imagen por ambiente, que es lo que hizo el monolito</h2>
 *
 * `infrastructure/infra/componentes/Aplicacion.ts` etiqueta la del monolito
 * `sgtm-interfaz:${environment}-${version}` justamente por esto, y lo dice en su comentario. Aqui
 * no cabe, y por dos motivos que se pierden a la vez:
 *
 *   1. **La etiqueta es el `sha` de este repositorio** (AC-1 de #44, y lo que `publicar-imagenes.yml`
 *      ya hace con las otras dos). Meter el nombre del ambiente dentro deja una etiqueta que no
 *      resuelve contra ningun `git log`, y entonces «que corre en la municipalidad» vuelve a no
 *      tener respuesta — que es exactamente el defecto que aquel workflow vino a cerrar.
 *   2. **Lo verificado dejaria de ser lo desplegado.** Con una imagen por ambiente no se promueve
 *      un artefacto de la marcha blanca a produccion: se vuelve a construir, y lo que sale no es
 *      lo que se probo.
 *
 * <h2>Y por que un `ConfigMap` con esto y NO con el `nginx.conf`</h2>
 *
 * `caja` mete su `nginx.conf` en un `ConfigMap` y lo monta encima del que la imagen trae, para
 * poder cambiarlo sin republicar. Aqui **no se hace**, y es una decision: ese archivo no tiene ni
 * una linea que dependa del ambiente —el reparto entre la API y la interfaz lo hace el ingreso,
 * no el nginx, asi que aqui no hay ningun destino que reescribir— de modo que la copia seria un
 * segundo original con cero beneficio y una forma segura de divergir del que de verdad se sirve.
 *
 * Lo que si depende del ambiente es esto, y por eso es esto lo que viaja en el `ConfigMap`.
 *
 * <h2>El nombre del archivo y su forma</h2>
 *
 * `configuracion.js`, el mismo que `frontend/public/configuracion.js` —que viaja **vacio** dentro
 * de la imagen— y sobre el que este se monta. Se compone con `JSON.stringify` y no concatenando
 * comillas: un valor con una comilla dentro se saldria del literal y dejaria un guion que no
 * analiza, o sea la aplicacion entera en blanco.
 */
function senasDelAmbiente(e: EntornoDelDescriptor): Record<string, string> {
  return {
    // El emisor PUBLICO, que es el que el navegador tiene que alcanzar. Es la misma cadena que el
    // backend recibe en `KAMAYUK_OIDC_EMISOR` —el contrato la describe como «el emisor OIDC,
    // publico. Es lo que se compara con el `iss`»— y por eso no se compone aqui: componerla seria
    // repetir una convencion de `infrastructure`, y dos copias de una convencion se separan.
    //
    // Ojo con la otra: `plataforma.jwks` NO vale aqui. Es una direccion de la red interna del
    // cluster, y el navegador no la puede alcanzar.
    oidcRealm: e.plataforma.emisor,
    oidcCliente: CLIENTE_OIDC_DE_LA_INTERFAZ,
    // Sin `offline_access` ni nada que pida un `refresh_token`: el token de esta interfaz vive en
    // una variable de modulo y muere con la pestana (ADR-0030 §3), asi que una credencial de vida
    // larga seria justo lo que ese diseno evita.
    oidcAlcance: "openid profile",
  };
}

/**
 * La interfaz: su `ConfigMap`, su `Deployment` y su `Service` (AC-6 de #44).
 *
 * <h2>Que corre aqui, y que NO</h2>
 *
 * Un `nginx` sirviendo el `dist/` de `rentas-web`. **Sin una sola variable de entorno y sin un
 * solo `secretKeyRef`**: lo unico que este proceso necesita saber del ambiente son las senias del
 * emisor, que no son secretas —el cliente es publico y su URL la ve cualquiera que abra el
 * navegador— y viajan en el `ConfigMap`. Un `Secret` montado aqui seria una credencial regalada a
 * un proceso que no la usa.
 *
 * <h2>`runAsNonRoot` sin `runAsUser`</h2>
 *
 * `SEGURIDAD` fija `runAsNonRoot: true`. El monolito tiene que anadirle ademas `runAsUser: 101`
 * porque su `Dockerfile` dice `USER nginx` —un NOMBRE— y el kubelet no puede comprobar que un
 * nombre no sea root: se niega a arrancar el contenedor con un `CreateContainerConfigError` que
 * solo aparece al desplegar. El de #44 dice **`USER 101`**, en numero y por este motivo, asi que
 * aqui no hace falta repetirlo. Y si alguien lo devolviera a un nombre, este `Deployment` dejaria
 * de arrancar sin que este archivo tuviera por que enterarse: por eso `descriptor.test.ts` lee el
 * `Dockerfile` y lo comprueba.
 *
 * <h2>Las sondas piden `/index.html` y no `/`</h2>
 *
 * Por lo mismo que el `HEALTHCHECK` de la imagen: con el `try_files` de `nginx.conf`, `/` devuelve
 * la pantalla **caiga lo que caiga**, asi que pedirlo no distingue «nginx levantado» de «nginx
 * levantado sobre el `dist/` que se copio». Pedir el archivo por su nombre si.
 *
 * <h2>Sin `startupProbe`, al reves que el backend</h2>
 *
 * El backend arranca una JVM con Spring y necesita hasta 150 s (`failureThreshold: 30`). Un nginx
 * escucha en menos de un segundo. Una sonda de arranque aqui solo retrasaria la primera lectura.
 */
function despliegueDeLaInterfaz(e: EntornoDelDescriptor): Manifiesto[] {
  const etiquetas = { ...e.etiquetas, componente: COMPONENTE_DE_LA_INTERFAZ };
  const configuracion = `${NOMBRE_DE_LA_INTERFAZ}-configuracion`;
  return [
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: { name: configuracion, namespace: e.namespace, labels: etiquetas },
      data: {
        "configuracion.js": `window.__KAMAYUK_RENTAS__ = ${JSON.stringify(senasDelAmbiente(e), null, 2)};\n`,
      },
    },
    {
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: { name: NOMBRE_DE_LA_INTERFAZ, namespace: e.namespace, labels: etiquetas },
      spec: {
        replicas: 1,
        // El mismo `maxSurge: 0` que el backend, y por el mismo motivo: en un nodo sin holgura un
        // pod extra durante el despliegue no agenda y el rollout se cuelga.
        strategy: { type: "RollingUpdate", rollingUpdate: { maxSurge: 0, maxUnavailable: 1 } },
        selector: { matchLabels: { app: NOMBRE_DE_LA_INTERFAZ } },
        template: {
          metadata: { labels: { ...etiquetas, app: NOMBRE_DE_LA_INTERFAZ } },
          spec: {
            priorityClassName: e.prioridadDe("servicio"),
            containers: [
              {
                name: "interfaz",
                // La etiqueta la pone `infrastructure`. Ver la cabecera.
                image: e.imagenDe(INTERFAZ),
                ports: [{ name: "http", containerPort: 8080 }],
                resources: RECURSOS_DE_LA_INTERFAZ,
                readinessProbe: {
                  timeoutSeconds: 3,
                  httpGet: { path: "/index.html", port: 8080 },
                  periodSeconds: 10,
                },
                livenessProbe: {
                  timeoutSeconds: 3,
                  httpGet: { path: "/index.html", port: 8080 },
                  periodSeconds: 20,
                },
                volumeMounts: [
                  {
                    name: "configuracion",
                    mountPath: "/usr/share/nginx/html/configuracion.js",
                    // `subPath`, o el montaje taparia el directorio entero y se llevaria por
                    // delante el `index.html` y todo `assets/`: la imagen serviria un directorio
                    // con un solo archivo dentro.
                    //
                    // El precio del `subPath` es que **no se actualiza solo**: cambiar el
                    // `ConfigMap` exige reiniciar el pod. Es el precio correcto aqui — cambiar de
                    // emisor a mitad de sesion dejaria a unas pestanas hablando con un realm y a
                    // otras con otro.
                    subPath: "configuracion.js",
                    readOnly: true,
                  },
                ],
                securityContext: SEGURIDAD,
              },
            ],
            volumes: [{ name: "configuracion", configMap: { name: configuracion } }],
          },
        },
      },
    },
    {
      apiVersion: "v1",
      kind: "Service",
      metadata: { name: NOMBRE_DE_LA_INTERFAZ, namespace: e.namespace, labels: etiquetas },
      spec: {
        type: "ClusterIP",
        selector: { app: NOMBRE_DE_LA_INTERFAZ },
        // 80 hacia fuera y 8080 dentro, como el `Service` del backend de este mismo archivo: el
        // contenedor no corre como root y no puede abrir un puerto privilegiado.
        ports: [{ name: "http", port: 80, targetPort: 8080 }],
      },
    },
  ];
}

/**
 * Las dos prioridades del ingreso, **explicitas y no heredadas de la longitud de la regla**
 * (AC-6 de #44).
 *
 * Traefik v3 ordena las rutas por la longitud de su `match` cuando nadie declara `priority`, y
 * `PathPrefix(/rentas/api/v1)` es mas larga que `PathPrefix(/rentas)`, asi que hoy saldria bien
 * **por accidente**. No se deja implicito, y el motivo es que el fallo no grita: con la
 * precedencia al reves, `/rentas/api/v1/contribuyentes` lo atenderia el nginx de la interfaz,
 * cuyo `try_files $uri /index.html` devuelve el `index.html` con un **200**. La pantalla pide
 * JSON y recibe HTML con codigo de exito: no un error, una pagina. Es el mismo modo de fallo que
 * `frontend/vite.config.ts` documenta para el desarrollo desde I-1, medido alli con Vite de
 * verdad: `200`, `content-type: text/html`, 584 bytes.
 */
const PRIORIDAD_DE_LA_API = 20;
const PRIORIDAD_DE_LA_INTERFAZ = 10;

/**
 * DNS, y va primero en toda politica de egreso porque todo lo demas depende de el.
 *
 * Sin esta regla las demas NO SIRVEN DE NADA. Una politica de egreso convierte a los pods que
 * selecciona en «solo lo declarado», y `postgres`, `identidad` y los sistemas hermanos se nombran
 * por su `Service`: resolver ese nombre es una consulta a CoreDNS, que vive en `kube-system`, y
 * ninguna de las otras reglas la permite. El sintoma medido es `UnknownHostException`, y es
 * **intermitente** —la resolucion se cachea, asi que a veces sale y a veces no—, que es peor que
 * fallar siempre.
 *
 * Con esta regla anadida a mano sobre el clúster, las OCHO tareas de los cuatro sistemas pasaron
 * de `Failed` a `Complete` (C-17, punto 3).
 *
 * Es la misma politica que `Red.ts` le da al namespace de la plataforma desde que existe
 * (`permitir-dns`): lo que fallo aqui no fue la idea, fue que estas politicas se escribieron de
 * cero y esa parte no se copio. Va **en el descriptor** y no en `infrastructure` porque quien
 * decide que pods restringe esta politica es este archivo —`podSelector` es suyo—; lo que si es
 * de `infrastructure` es la guarda que comprueba que ningun sistema se la deje.
 *
 * Sin `podSelector` en el destino, a proposito: lo que se abre es el PUERTO 53 hacia el namespace
 * del sistema, no un pod concreto. Nombrar `k8s-app: kube-dns` ataria esta politica a como
 * etiqueta sus pods una distribucion de Kubernetes.
 *
 * **Escrita una vez y usada dos** (#44): la del backend y la de la interfaz. Hasta aqui vivia en
 * linea dentro de `egreso()`, y con una segunda politica en el archivo eso habrian sido dos
 * copias de la misma decision envejeciendo aparte.
 */
function reglaDeDns() {
  return {
    to: [
      {
        namespaceSelector: {
          matchLabels: { "kubernetes.io/metadata.name": "kube-system" },
        },
      },
    ],
    ports: [
      { protocol: "UDP" as const, port: 53 },
      // TCP tambien: una respuesta que no cabe en un datagrama se reintenta por TCP, y una
      // politica que solo abriera UDP funcionaria hasta el dia que dejara de hacerlo, por el
      // tamano de una respuesta.
      { protocol: "TCP" as const, port: 53 },
    ],
  };
}

/**
 * Las dos politicas de red de la interfaz, y son las dos puntas de un solo flujo (AC-7 de #44).
 *
 * <h2>Entrada: Traefik y nadie mas</h2>
 *
 * `infrastructure` deniega por omision en el namespace, asi que sin esta regla el ingreso enruta
 * y el paquete no llega — la ruta existe, el pod esta sano y el navegador se queda esperando.
 *
 * El puerto es el **8080 del contenedor**, no el 80 del `Service`: una `NetworkPolicy` filtra
 * sobre el puerto del pod, y el mapeo 80 -> 8080 lo deshace el `Service` antes de que la politica
 * mire nada. Escribir 80 aqui seria una politica que no admite absolutamente nada, y el sintoma
 * volveria a ser el navegador esperando.
 *
 * <h2>Salida: DNS y NADA MAS, y sobre todo NO el backend</h2>
 *
 * Esta interfaz no habla con nadie. No es una promesa: `frontend/nginx.conf` no tiene un solo
 * reenvio, y no lo tiene porque **el mismo origen se consigue un piso mas arriba** — el ingreso
 * parte `/rentas` en dos y manda `/rentas/api/v1` al backend directamente. El navegador ve un
 * unico origen, que es lo que hacia falta para que no hubiera CORS, y este pod no participa.
 *
 * De ahi que **no exista una regla hacia el backend**, y eso es la mitad importante de este
 * bloque: no es un olvido, es que anadirla abriria una salida que ningun proceso usa. Y el dia
 * que alguien escriba un `proxy_pass` aqui, no funcionara en el cluster aunque funcione en el
 * compose — y ese es el sitio correcto para enterarse: el PR que lo escriba.
 *
 * Y **no sale a PostgreSQL**, que es lo que el AC-7 pide comprobar: no hereda ninguna de las
 * cinco aristas del backend porque su `componente` es `rentas-interfaz` y no `rentas`, asi que
 * ningun `podSelector` de `egreso()` la alcanza.
 */
function politicasDeLaInterfaz(e: EntornoDelDescriptor): NetworkPolicy[] {
  const seleccion = { matchLabels: { componente: COMPONENTE_DE_LA_INTERFAZ } };
  return [
    {
      apiVersion: "networking.k8s.io/v1",
      kind: "NetworkPolicy",
      metadata: {
        name: `${NOMBRE_DE_LA_INTERFAZ}-ingreso`,
        namespace: e.namespace,
        labels: e.etiquetas,
      },
      spec: {
        podSelector: seleccion,
        policyTypes: ["Ingress"],
        ingress: [
          {
            from: [
              {
                namespaceSelector: {
                  matchLabels: { "kubernetes.io/metadata.name": "kube-system" },
                },
              },
            ],
            ports: [{ protocol: "TCP", port: 8080 }],
          },
        ],
      },
    },
    {
      apiVersion: "networking.k8s.io/v1",
      kind: "NetworkPolicy",
      metadata: {
        name: `${NOMBRE_DE_LA_INTERFAZ}-egreso`,
        namespace: e.namespace,
        labels: e.etiquetas,
      },
      spec: {
        podSelector: seleccion,
        policyTypes: ["Egress"],
        egress: [reglaDeDns()],
      },
    },
  ];
}

export const rentas: DescriptorDeSistema = {
  sistema: SISTEMA,
  prefijo: SISTEMA,
  // TRES imagenes. Las dos primeras son dos objetivos del MISMO `Dockerfile` (C-14, punto 1): las
  // credenciales de `kamayuk_owner` existen durante la migracion y desaparecen con ella. La
  // tercera es de OTRO —`frontend/Dockerfile`, con contexto `frontend/` (#44)— y no comparte una
  // sola capa con ellas: no lleva JVM, ni Node, ni codigo fuente; solo `dist/` y nginx.
  imagenes: [SISTEMA, MIGRADOR, INTERFAZ],

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
        { nombre: "kamayuk_owner", sobre: [SISTEMA], privilegios: ["ALL"], superusuario: false },
        {
          nombre: "kamayuk_app",
          sobre: [SISTEMA],
          privilegios: ["SELECT", "INSERT", "UPDATE"],
          superusuario: false,
        },
        { nombre: "kamayuk_readonly", sobre: [SISTEMA], privilegios: ["SELECT"], superusuario: false },
      ],
    };
  },

  /**
   * **Un solo `Deployment`, el del perfil `web`** (C-17, punto 5).
   *
   * Hasta aqui habia dos, y el segundo era `kamayuk-rentas-batch`. Medido en el clúster: arranca,
   * registra «No TaskScheduler/ScheduledExecutorService bean found for scheduled processing»,
   * **sale con codigo 0** a los once segundos y Kubernetes lo vuelve a crear —`CrashLoopBackOff`
   * con siete reinicios—. No es un fallo de arranque disfrazado: es que **no hay nada que
   * sostenga vivo a ese proceso**, y el propio codigo lo dice. `CorrerElIngestor` y
   * `CorrerLaAntiEntropia` son `ApplicationRunner` del perfil `batch`, y su javadoc explica por
   * que no son `@Scheduled`: «en los cuatro backends no hay ni un `@EnableScheduling` […] y el
   * perfil `batch` TERMINA el proceso con `web-application-type: none`».
   *
   * ## Por que se quita, y no se convierte en `CronJob` ni se le da algo que lo mantenga vivo
   *
   * Porque el trabajo del perfil `batch` **ya tiene su forma, y son dos**: `implantacion()` —un
   * `Job`, que corre una vez— y `lotes()` —el `CronJob` del ingestor, que corre en su ventana—.
   * Los dos crean su pod cuando hay trabajo y lo dejan morir al acabar. Un `Deployment` dice
   * «esto tiene que estar corriendo siempre», y aqui no hay nada que lo este.
   *
   * Un `CronJob` mas tampoco: un `CronJob` necesita una ventana y algo que correr en ella, y este
   * sistema ya tiene el suyo. Anadir un segundo con la misma imagen y ningun runner que invocar
   * seria el mismo vacio con horario.
   *
   * ## Lo que costaba tenerlo, que es mas que un pod en rojo
   *
   * Un `Deployment` solo admite `restartPolicy: Always`, asi que Kubernetes **no puede
   * distinguir «termino» de «se murio»**: la forma miente en las dos direcciones —afirma que algo
   * corre siempre cuando no corre nada, y reporta como fallo una salida con exito—. Y un
   * `CrashLoopBackOff` permanente en el tablero es ruido que acaba no mirandose, que es lo que
   * hace que el dia que reviente algo de verdad tampoco se mire.
   */
  despliegue: (e) => [...despliegueDelPerfil(e, "web", true), ...despliegueDeLaInterfaz(e)],

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
   * `V5` no le dan a `kamayuk_app` mas que `SELECT` sobre las cuatro proyecciones— y viene a buscar
   * los hechos al buzon de `catastro` por HTTP, con acuse.
   *
   * ## Ya NO nace suspendido, y hay que decir por que dejo de estarlo
   *
   * Nacio `suspend: true` porque el feed de `catastro` esta detras de
   * `@RequiereAcceso("consulta_fichas")` y **no habia identidad de servicio**: sin credencial la
   * llamada salia sin `Authorization`, `catastro` la rechazaba con 401 —el comportamiento
   * correcto— y un `CronJob` activo en ese estado fallaria cada noche con una alerta que es ruido.
   * Su propio comentario decia «quitar el `suspend` es una linea el dia que exista la identidad de
   * servicio».
   *
   * **Ese dia es #21.** `KAMAYUK_CATASTRO_CREDENCIAL` declara `emisor: "keycloak"` en el
   * inventario, y con eso la guarda `identidad-de-servicio` de `infrastructure` **no deja pasar el
   * build** mientras alguna municipalidad no declare su cliente de servicio. O sea que el CronJob
   * ya no puede quedarse activo contra un emisor que no ha emitido nada: lo que antes sostenia el
   * `suspend` lo sostiene ahora una guarda que se pone roja.
   *
   * Lo que se declara aqui sigue siendo **la ventana, los limites y la configuracion entera**, que
   * es lo que C-8 §huecos 2 decia que faltaba.
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
                      // A donde se pide el token, por la red INTERNA (#21 AC-2). Es una
                      // direccion y no una identidad: el emisor publico es lo que se compara con
                      // el `iss` del token que se RECIBE; esto es a donde se va a buscarlo.
                      // Pedirlo al publico haria salir al ingreso para volver a entrar, y la
                      // politica de egreso —que nombra el pod de identidad, no internet— no lo
                      // permite.
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_IDENTIDAD_TOKEN",
                        value: e.plataforma.token,
                      },
                      // Y con QUE cliente: uno por municipalidad, porque la cuenta de servicio de
                      // ese cliente es la que lleva `municipalidad_id` y ADR-0028 §2 dice que «no
                      // hay un proceso con permiso sobre todas». El nombre lo fija
                      // `clienteDeServicio()` de `infrastructure`, y su guarda
                      // `identidad-de-servicio` compara esta cadena con la suya.
                      {
                        name: "KAMAYUK_RENTAS_INGESTOR_IDENTIDAD_CLIENTE",
                        value: `kamayuk-${SISTEMA}-servicio-${e.implantacion.ubigeo}`,
                      },
                      // La credencial con que se pide el feed: la clave del cliente confidencial
                      // con la que el ingestor pide su token (#21). No es el token.
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

  /**
   * Sus rutas, **bajo su prefijo**. Reclamar el de otro no falla: se lo queda.
   *
   * <h2>La ruta va PARTIDA EN DOS, y ese reparto es lo que quita el CORS (AC-6 de #44)</h2>
   *
   * `/rentas/api/v1` al backend y `/rentas` a la interfaz, dentro del **mismo `Host`**. Desde el
   * navegador todo cuelga de un solo origen, asi que no hay peticion entre origenes que
   * autorizar — y hace falta que no la haya, porque esta medido que el backend **no publica ni
   * una cabecera `Access-Control-Allow-Origin`**: cero `CorsConfiguration` y cero `@CrossOrigin`
   * en todo `backend/`. Una peticion desde otro origen la bloquea el navegador antes de que
   * nadie la lea.
   *
   * Es el mismo resultado que el monolito consigue con un `proxy_pass` dentro de su nginx, y se
   * prefiere este por tres cosas: no hay dos caminos a la API que puedan divergir, el destino no
   * cambia entre el compose y el cluster —asi que no hay que reescribir ninguna linea del
   * `nginx.conf` al meterlo en un `ConfigMap`, ni hay `ConfigMap` de `nginx.conf` que mantener— y
   * la interfaz no necesita salida de red hacia el backend (ver `politicasDeLaInterfaz`).
   *
   * <h2>El prefijo se quita SOLO en la de la interfaz</h2>
   *
   * `Api.RAIZ` del backend **es** `/rentas/api/v1` entera, asi que quitarle el prefijo dejaria a
   * Spring buscando `/api/v1/...` y contestando 404 a todo. La interfaz al reves: su `nginx.conf`
   * sirve en la raiz del contenedor, y el paquete pide con el prefijo puesto porque
   * `vite.config.ts` declara `base: '/rentas/'` (ADR-0030 §2).
   */
  ingreso(e): Manifiesto[] {
    const quitarElPrefijo = `kamayuk-${SISTEMA}-quitar-prefijo`;
    return [
      {
        apiVersion: "traefik.io/v1alpha1",
        kind: "Middleware",
        metadata: { name: quitarElPrefijo, namespace: e.namespace, labels: e.etiquetas },
        // Traefik reenvia lo que queda y anade `X-Forwarded-Prefix`, asi que quien quiera
        // reconstruir la URL publica puede; nginx no lo necesita para servir un archivo.
        spec: { stripPrefix: { prefixes: [`/${SISTEMA}`] } },
      },
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
              match: `Host(\`${e.dominio}\`) && PathPrefix(\`/${SISTEMA}/api/v1\`)`,
              kind: "Rule",
              priority: PRIORIDAD_DE_LA_API,
              // SIN `middlewares`: el backend espera la ruta entera. Ver la cabecera.
              services: [{ name: `kamayuk-${SISTEMA}-web`, port: 80 }],
            },
            {
              match: `Host(\`${e.dominio}\`) && PathPrefix(\`/${SISTEMA}\`)`,
              kind: "Rule",
              priority: PRIORIDAD_DE_LA_INTERFAZ,
              services: [{ name: NOMBRE_DE_LA_INTERFAZ, port: 80 }],
              middlewares: [{ name: quitarElPrefijo }],
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
            // DNS, y va primero porque todo lo demas depende de el. Ver `reglaDeDns`.
            reglaDeDns(),
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
      // La interfaz, que **no comparte ninguna** de las cinco aristas de arriba: su `componente`
      // es «rentas-interfaz» y no «rentas», asi que ningun `podSelector` de esta politica la
      // selecciona. Es lo que hace que un nginx de archivos estaticos no tenga salida a
      // PostgreSQL (AC-7 de #44). Ver `politicasDeLaInterfaz`.
      ...politicasDeLaInterfaz(e),
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

  /**
   * Su inventario de claves: metadatos, **nunca un valor** (INF-06, ADR-0011 §3).
   *
   * **El nombre sale de `e.secretoDe(...)`, el mismo que usan los manifiestos** (C-17, punto 4).
   * Hasta aqui esta lista decia `kamayuk-<sistema>-app` —sin el ambiente— mientras los
   * `secretKeyRef` de arriba pedian `kamayuk-<sistema>-<ambiente>-app`: el inventario nombraba
   * un `Secret` que nadie monta, y los que se montan no estaban en ningun inventario. La
   * interseccion entre lo declarado y lo referenciado era **cero**, y el sintoma no es un error
   * sino un pod en `Pending` esperando un `Secret` que nadie genera.
   */
  claves: (e): ClaveDeclarada[] => [
    {
      nombre: e.secretoDe("app"),
      clave: "clave",
      rol: "kamayuk_app",
      rotacion: "trimestral",
      proposito: `la conexion de ${SISTEMA} a su base`,
    },
    {
      nombre: e.secretoDe("owner"),
      clave: "clave",
      rol: "kamayuk_owner",
      rotacion: "anual",
      proposito: `migrar la base de ${SISTEMA}; es el unico rol con DDL`,
    },
    {
      // El pool del ingestor (C-8): OTRO rol dentro del mismo proceso. `V4` y `V5` no le dan a
      // `kamayuk_app` mas que `SELECT` sobre las cuatro proyecciones, asi que quien las escribe
      // tiene que ser otro — y ese otro no atiende peticiones.
      nombre: e.secretoDe("ingestor"),
      clave: "clave",
      rol: "rol_ingestor_catastro",
      rotacion: "trimestral",
      proposito: "escribir la proyeccion del padron de catastro; no atiende peticiones",
    },
    {
      // La credencial con que el ingestor pide el feed de `catastro`.
      //
      // **`emisor: "keycloak"` es lo que la separa de una clave de PostgreSQL** (#21). Su valor no
      // vale por si mismo: es la clave del cliente confidencial `kamayuk-rentas-servicio-<ubigeo>`
      // con la que se pide un token, y ese cliente lo crea `reconciliar-identidades.sh servicios`
      // desde `despliegue/identidad/municipalidades/<ubigeo>.json`.
      //
      // Hasta #21 esto se declaraba sin decirlo, `bootstrap-secretos.sh` generaba una cadena
      // aleatoria, y `catastro` la rechazaba con 401 — un secreto que existe y no autentica a
      // nadie es indistinguible de uno bueno hasta que se despliega. Ahora `identidad-de-servicio`
      // exige que la cuenta exista en TODAS las municipalidades antes de dejar pasar el build.
      nombre: e.secretoDe("catastro"),
      clave: "clave",
      emisor: "keycloak",
      rotacion: "trimestral",
      proposito: "pedir el buzon de hechos de catastro con el token del cliente de servicio",
    },
  ],
};

export default rentas;
