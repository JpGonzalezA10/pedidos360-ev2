# SmartLogix

SmartLogix es una plataforma inteligente para la gestion logistica de eCommerce orientada a PYMEs. El sistema busca reemplazar procesos manuales o soluciones monoliticas por una arquitectura moderna basada en frontend React, API Gateway, microservicios Spring Boot, persistencia MySQL y seguridad con JWT.

## Objetivo del proyecto

El objetivo es analizar distintos patrones de diseno, arquetipos y patrones arquitectonicos, con el proposito de generar componentes frontend y backend que se adapten a la necesidad del cliente. La solucion incorpora estrategias de branching, optimiza el trabajo colaborativo del equipo y permite construir una solucion tecnica alineada con los requerimientos de gestion de inventario, pedidos, envios y usuarios.

## Alcance documentado

Este repositorio documenta y organiza los siguientes entregables:

- Estructurar el proyecto: validar que frontend, backend y base Maven esten configurados, separados por responsabilidad y preparados para pruebas.
- Documentar: describir arquitectura, patrones seleccionados, estrategia de branching, instrucciones de instalacion, ejecucion y pruebas.
- Versionar: mantener los componentes en GitHub y registrar los enlaces principales en `repositorios.txt`.
- Justificar patrones: explicar por que se usan microservicios, API Gateway, Repository Pattern, DTOs, Factory Method, Circuit Breaker y separacion por capas.
- Preparar base de informe: dejar una estructura clara para luego convertir esta informacion en un informe PDF.

## Arquitectura general

SmartLogix usa una arquitectura de microservicios con entrada unica por API Gateway.

```text
React Frontend
  |
  v
API Gateway / entrada BFF para el frontend
  |
  +--> auth-service    -> BD smartlogix_auth
  +--> inventario      -> BD smartlogix
  +--> pedidos         -> BD smartlogix_orders
  +--> envios          -> BD smartlogix_shipping
```

Flujo principal:

1. El usuario accede desde React.
2. React consume el API Gateway usando `VITE_API_BASE_URL`, por defecto `http://localhost:8080`.
3. El API Gateway enruta `/auth/**`, `/users/**`, `/inventory/**`, `/orders/**` y `/shipping/**`.
4. `auth-service` registra usuarios, valida credenciales y emite JWT.
5. React almacena el token y lo envia como `Authorization: Bearer TOKEN`.
6. El API Gateway valida el JWT y controla acceso inicial por rutas.
7. Cada microservicio mantiene su propia base de datos y no consulta tablas de otros servicios.
8. Las integraciones entre servicios se hacen por HTTP REST usando DTOs.
9. Las llamadas entre servicios criticas usan Circuit Breaker para responder de forma controlada cuando un servicio no esta disponible.

## Componentes del repositorio

| Componente | Ruta | Tipo | Puerto local | Responsabilidad |
| --- | --- | --- | --- | --- |
| Frontend | `smartlogix-frontend/Front-Smartlogix` | React + Vite + NPM | `5173` | Interfaz de usuarios, administracion, inventario, pedidos y envios. |
| API Gateway | `api-gateway` | Spring Cloud Gateway WebMVC | `8080` | Entrada unica del frontend, validacion JWT, autorizacion inicial y enrutamiento. |
| Auth service | `auth-service` | Spring Boot + JWT + JPA | `8082` | Usuarios, roles, login, registro y emision de tokens. |
| Inventario | `inventario` | Spring Boot + JPA + Flyway | `8081` | Productos, stock, disponibilidad y operaciones de inventario. |
| Pedidos | `pedidos` | Spring Boot + JPA + Resilience4j | `8084` | Creacion, validacion, trazabilidad y estados de pedidos. |
| Envios | `envios` | Spring Boot + JPA + Resilience4j | `8083` | Despachos, tracking, estados de envio y consulta de pedidos. |
| Postman | `postman` | Colecciones JSON | N/A | Pruebas manuales de gateway y microservicios. |

## Responsables por componente

| Componente | Responsable |
| --- | --- |
| Inventario | Felipe Norambuena |
| Pedidos | Matias Vega |
| Envios | Nicolas Olivares |
| Auth service | Juan Pablo Gonzalez |
| API Gateway | Colaborativo entre todos los integrantes |
| Frontend | Colaborativo entre todos los integrantes |

## Estructura principal

```text
Smartlogix/
  api-gateway/
  auth-service/
  inventario/
  pedidos/
  envios/
  smartlogix-frontend/
    Front-Smartlogix/
      package.json
      public/
      src/
  postman/
  smartlogix_auth_mysql_laragon.sql
  smartlogix_inventory_seed_products.sql
  smartlogix_mysql_laragon.sql
  smartlogix_orders_mysql_laragon.sql
  smartlogix_shipping_mysql_laragon.sql
  repositorios.txt
  README.md
```

## Patrones seleccionados y justificacion

| Patron | Uso en SmartLogix | Justificacion |
| --- | --- | --- |
| Microservices Architecture | `auth-service`, `inventario`, `pedidos`, `envios` | Separa responsabilidades de negocio y permite evolucionar cada modulo sin acoplar usuarios, stock, pedidos y despachos. |
| API Gateway | `api-gateway` | Centraliza entrada desde React, rutas externas, CORS, validacion JWT y control inicial de roles. |
| Backend For Frontend ligero | `api-gateway` como entrada para React | El frontend no consume rutas internas `/api/**`; usa rutas externas orientadas a la experiencia web. |
| Database per Service | Cada servicio usa su esquema MySQL | Evita acceso directo a tablas ajenas y mantiene independencia de datos por dominio. |
| Layered Architecture | `controller`, `service`, `repository`, `model`, `dto`, `config`, `exception`, `security` | Mantiene controladores delgados, logica en servicios y persistencia aislada en repositorios. |
| Repository Pattern | Repositories Spring Data JPA | Encapsula acceso a base de datos y facilita pruebas de servicios. |
| DTO Pattern | Requests y responses en paquetes `dto` | Evita exponer entidades JPA directamente en la API y permite validar entrada. |
| Factory Method | `inventario/src/main/java/com/api/inventario/factory/SkuFactory.java` | Centraliza la creacion de SKUs y permite cambiar la regla de generacion sin afectar servicios ni controladores. |
| Circuit Breaker | `pedidos` hacia `inventario` y `auth-service`; `envios` hacia `pedidos` | Reduce fallas encadenadas cuando un servicio dependiente no responde y devuelve errores controlados. |
| JWT + RBAC | `auth-service` y `api-gateway` | Permite autenticacion stateless y autorizacion por roles como `ADMIN`, `OPERADOR_INVENTARIO`, `OPERADOR_PEDIDOS`, `OPERADOR_ENVIOS` y `CLIENTE`. |
| Controller Advice | Paquetes `exception` | Normaliza errores JSON y separa manejo de excepciones de la logica de negocio. |
| Flyway migrations | `src/main/resources/db/migration` | Versiona el esquema de base de datos por servicio. |
| Component-based frontend | React con `features`, `components`, `hooks` y `services` | Organiza la UI por modulo funcional y separa vista, estado y acceso HTTP. |

## Arquetipo Maven seleccionado

Para este proyecto se documenta Spring Boot Maven como arquetipo base. Es decir, no se crea un arquetipo Maven personalizado independiente; se usa una estructura comun de proyecto Spring Boot generada con Maven y adaptada a la arquitectura SmartLogix.

Los componentes backend siguen esta base comun:

- Build tool: Maven con `mvnw` y `mvnw.cmd`.
- Packaging: `jar`.
- Framework: Spring Boot `4.0.6`.
- Lenguaje: Java.
- Version Java: Java 17 en microservicios; `api-gateway` esta configurado con Java 21.
- Persistencia: Spring Data JPA.
- Base de datos: MySQL/MariaDB.
- Migraciones: Flyway donde corresponde.
- Validacion: Spring Validation.
- Documentacion API: Springdoc OpenAPI en microservicios funcionales.
- Seguridad: Spring Security y JWT donde aplica.
- Resiliencia: Resilience4j Circuit Breaker para integraciones entre servicios.

Estructura base esperada para nuevos servicios:

```text
src/main/java/com/smartlogix/{servicio}/
  controller/
  service/
  repository/
  model/
  dto/
  config/
  exception/
  security/
  factory/
src/main/resources/
  application.properties
  db/migration/
pom.xml
```

Justificacion del arquetipo base:

- Spring Boot Maven entrega una base estandar, compatible con dependencias empresariales y facil de reproducir por el equipo.
- Maven permite administrar dependencias, perfiles, empaquetado `jar` y ejecucion de pruebas de forma consistente en todos los servicios.
- La estructura por capas se mantiene igual entre microservicios, lo que reduce curva de aprendizaje y facilita colaboracion.
- El uso de `mvnw` y `mvnw.cmd` evita depender de una instalacion global exacta de Maven.
- La base permite agregar dependencias especificas segun el dominio del servicio, por ejemplo Security en `auth-service` o Resilience4j en `pedidos` y `envios`.

Guia breve para generar un nuevo componente backend con la base actual:

1. Crear un proyecto Spring Boot Maven con Java 17 y packaging `jar`.
2. Agregar dependencias segun responsabilidad: WebMVC, Data JPA, Validation, MySQL, Flyway, Lombok, Springdoc, Security y Resilience4j si tiene integraciones.
3. Crear paquetes `controller`, `service`, `repository`, `model`, `dto`, `config`, `exception`, `security` y `factory` si aplica.
4. Definir una base de datos propia para el servicio.
5. Agregar migraciones Flyway en `src/main/resources/db/migration`.
6. Exponer DTOs, no entidades JPA, desde los controladores.
7. Agregar README del componente con instalacion, ejecucion, variables y pruebas.

## Estrategia de branching

La estrategia utilizada es Trunk-Based Development. Bajo este enfoque, `main` funciona como tronco principal de integracion y las ramas de trabajo deben ser pequenas, de corta duracion y orientadas a cambios concretos.

Objetivo de la estrategia:

- Reducir conflictos grandes entre integrantes.
- Integrar cambios con mayor frecuencia.
- Mantener una rama principal estable.
- Facilitar pruebas tempranas desde frontend, gateway y microservicios.
- Evitar ramas largas con diferencias acumuladas.

| Rama | Uso |
| --- | --- |
| `main` | Tronco principal. Debe contener la version integrada y funcional del proyecto. |
| Ramas cortas de feature/fix/docs | Cambios pequenos que se integran rapidamente a `main`. |
| Ramas por integrante existentes | Se usan solo como apoyo temporal y deben sincronizarse frecuentemente con `main`. |

Flujo aplicado:

1. Partir cada cambio desde `main` actualizado.
2. Crear una rama corta para una funcionalidad, correccion o documentacion.
3. Mantener commits pequenos y descriptivos, por ejemplo `docs: actualiza estrategia branching` o `feat: agrega validacion de stock`.
4. Ejecutar las pruebas necesarias del componente modificado.
5. Integrar a `main` mediante Pull Request o merge controlado.
6. Resolver conflictos de inmediato si aparecen.
7. Validar el flujo completo desde frontend cuando el cambio afecte la experiencia del usuario.

Convencion sugerida para nuevas ramas:

```text
feature/frontend-login
feature/pedidos-stock-validation
fix/gateway-auth-routes
docs/informe-patrones
hotfix/envios-status-transition
```

## Frontend

Ruta:

```text
smartlogix-frontend/Front-Smartlogix
```

El frontend esta empaquetado segun el estandar NPM:

- `package.json`: dependencias y scripts.
- `package-lock.json`: versiones bloqueadas.
- `src/`: codigo fuente React.
- `public/`: assets publicos.
- `vite.config.js`: configuracion Vite.
- `eslint.config.js`: reglas de lint.

Dependencias principales:

- `react`
- `react-dom`
- `vite`
- `@vitejs/plugin-react`
- `eslint`

Scripts disponibles:

```powershell
npm install
npm run dev
npm run build
npm run lint
npm run preview
```

Variable de entorno:

```text
VITE_API_BASE_URL=http://localhost:8080
```

Si no se define, el frontend usa `http://localhost:8080` como API Gateway.

## Backend

### API Gateway

Ruta:

```text
api-gateway
```

Responsabilidad:

- Centralizar llamadas externas del frontend.
- Validar JWT emitido por `auth-service`.
- Aplicar autorizacion inicial por rutas.
- Enrutar hacia microservicios.

Comando local:

```powershell
cd api-gateway
.\mvnw.cmd spring-boot:run
```

Variables relevantes:

```properties
SERVER_PORT=8080
AUTH_SERVICE_URL=http://localhost:8082
INVENTORY_SERVICE_URL=http://localhost:8081
ORDERS_SERVICE_URL=http://localhost:8084
SHIPPING_SERVICE_URL=http://localhost:8083
JWT_SECRET=smartlogix-auth-dev-secret-change-me-1234567890
JWT_ISSUER=smartlogix-auth
```

### Microservicios

| Servicio | Ruta | Base de datos | Comando | Pruebas |
| --- | --- | --- | --- | --- |
| Auth | `auth-service` | `smartlogix_auth` | `.\mvnw.cmd spring-boot:run` | `.\mvnw.cmd test` |
| Inventario | `inventario` | `smartlogix` | `.\mvnw.cmd spring-boot:run` | `.\mvnw.cmd test` |
| Pedidos | `pedidos` | `smartlogix_orders` | `.\mvnw.cmd spring-boot:run` | `.\mvnw.cmd test` |
| Envios | `envios` | `smartlogix_shipping` | `.\mvnw.cmd spring-boot:run` | `.\mvnw.cmd test` |

Orden recomendado para levantar en local:

1. Ejecutar scripts SQL de base de datos en MySQL/Laragon.
2. Levantar `auth-service` en `http://localhost:8082`.
3. Levantar `inventario` en `http://localhost:8081`.
4. Levantar `pedidos` en `http://localhost:8084`.
5. Levantar `envios` en `http://localhost:8083`.
6. Levantar `api-gateway` en `http://localhost:8080`.
7. Levantar frontend en `http://localhost:5173`.

Scripts SQL disponibles:

```text
smartlogix_auth_mysql_laragon.sql
smartlogix_mysql_laragon.sql
smartlogix_inventory_seed_products.sql
smartlogix_orders_mysql_laragon.sql
smartlogix_shipping_mysql_laragon.sql
```

## Rutas principales

Rutas externas por API Gateway:

| Ruta | Servicio destino | Seguridad esperada |
| --- | --- | --- |
| `POST /auth/register` | `auth-service` | Publica |
| `POST /auth/login` | `auth-service` | Publica |
| `GET /auth/me` | `auth-service` | JWT requerido |
| `/users/**` | `auth-service` | `ADMIN` |
| `/inventory/**` | `inventario` | `ADMIN`, `OPERADOR_INVENTARIO` |
| `/orders/**` | `pedidos` | `ADMIN`, `OPERADOR_PEDIDOS`, `CLIENTE` segun reglas |
| `/shipping/**` | `envios` | `ADMIN`, `OPERADOR_ENVIOS` |

Rutas internas relevantes:

```text
inventario: /api/products, /api/inventory
pedidos:    /api/orders
envios:     /api/shipments
```

## Pruebas con Postman

Colecciones disponibles:

```text
postman/smartlogix-auth.postman_collection.json
postman/smartlogix-inventario.postman_collection.json
postman/smartlogix-pedidos.postman_collection.json
postman/smartlogix-envios.postman_collection.json
postman/smartlogix-gateway.postman_collection.json
```

Flujo recomendado:

1. Ejecutar health checks del servicio o gateway.
2. Registrar usuario o iniciar sesion.
3. Guardar el JWT en la variable de Postman.
4. Probar rutas protegidas desde el Gateway.
5. Probar integraciones: pedido consulta inventario; envio consulta pedido.

## Evidencia de pruebas

La evidencia funcional sera documentada a partir de pruebas realizadas desde el frontend. El objetivo es registrar capturas, resultados esperados y resultados obtenidos para los flujos principales:

| Flujo | Estado de evidencia |
| --- | --- |
| Login y uso de JWT | Pendiente de documentar despues de prueba frontend |
| Administracion de usuarios | Pendiente de documentar despues de prueba frontend |
| Gestion de inventario | Pendiente de documentar despues de prueba frontend |
| Creacion y seguimiento de pedidos | Pendiente de documentar despues de prueba frontend |
| Coordinacion y seguimiento de envios | Pendiente de documentar despues de prueba frontend |
| Validacion de rutas por rol desde Gateway | Pendiente de documentar despues de prueba frontend |

Las pruebas frontend seran realizadas y documentadas posteriormente como evidencia para el informe PDF.

## Versionamiento y repositorios

Repositorio principal configurado:

```text
https://github.com/FelipeNorambuena/Smartlogix.git
```

El archivo `repositorios.txt` registra el repositorio principal y los enlaces por componente dentro del monorepo. Si el equipo decide separar frontend, BFF o microservicios en repositorios individuales, se deben reemplazar los valores pendientes por las URLs finales de GitHub.


## EP3 - Mensajeria asincrona con RabbitMQ

Se incorpora RabbitMQ para comunicar dominios de forma desacoplada, sin modificar los flujos REST existentes.
Los servicios de negocio solo emiten eventos de dominio de Spring (`ApplicationEventPublisher`); la capa
`messaging/` de cada microservicio los publica en RabbitMQ **despues del commit** (`@TransactionalEventListener`).

### Estructura de la capa de mensajeria (por microservicio)

```text
messaging/
  config/     MessagingProperties (nombres centralizados), RabbitTopologyConfig (Queue/Exchange/Binding), RabbitMessagingConfig (JSON + confirms)
  producer/   publicadores de eventos propios
  consumer/   @RabbitListener agrupados por dominio (consumer/pedidos, consumer/envios, consumer/alertas, consumer/notificaciones)
  event/      contratos de los mensajes consumidos
  support/    MessageAcknowledger (ACK/NACK, retry y DLQ)
```

### Topologia

| Exchange (topic) | Routing key | Cola consumidora | Microservicio | Accion |
| --- | --- | --- | --- | --- |
| `pedidos.events` | `pedido.confirmado` | `envios.pedido-confirmado.queue` | envios | Crea el despacho automaticamente |
| `pedidos.events` | `pedido.confirmado` | `inventario.pedido-confirmado.queue` | inventario | Evalua stock bajo de los productos |
| `pedidos.events` | `pedido.cancelado` | `envios.pedido-cancelado.queue` | envios | Cancela el despacho pendiente |
| `pedidos.events` | `pedido.estado.actualizado` | `pedidos.notificaciones-cliente.queue` | pedidos | Notifica al cliente el cambio de estado |
| `envios.events` | `envio.estado.actualizado` | `pedidos.envio-estado.queue` | pedidos | Sincroniza SHIPPED / DELIVERED |
| `inventario.events` | `inventario.stock.bajo` | `inventario.alertas-stock.queue` | inventario | Notifica reposicion al operador |

Cada cola tiene su `*.retry` (TTL 5 s, vuelve a la cola principal) y su `*.dlq` enlazada al DLX del servicio
(`pedidos.dlx`, `envios.dlx`, `inventario.dlx`, tipo direct). Las DLQ retienen 7 dias y maximo 10.000 mensajes.

### Confirmacion y manejo de errores (ACK manual)

| Situacion | Decision | Resultado |
| --- | --- | --- |
| Procesado correctamente | `basicAck` | Mensaje eliminado de la cola |
| Error permanente (payload invalido, recurso inexistente, regla de negocio) | `basicNack(requeue=false)` | DLX -> DLQ + log `[DLQ]` |
| Error transitorio (servicio caido, BD) | copia a `*.retry` con `x-retry-count+1` y `basicAck` | Reintento a los 5 s (max. 3 intentos) |
| Reintentos agotados | `basicNack(requeue=false)` | DLX -> DLQ + log `[DLQ]` |

Nunca se usa `requeue=true` sobre la cola principal (evita *poison messages* en bucle). Los consumidores son idempotentes.

### Ejecucion del stack completo

```bash
cp .env.example .env
docker compose --profile app up -d --build
docker exec -i pedidos360-mysql mysql < smartlogix_inventory_seed_products.sql   # datos de inventario
bash scripts/ep3-demo.sh            # escenarios de demostracion (requiere curl y jq)
```

- RabbitMQ Management: `http://<host>:15672` (usuario/clave de `.env`)
- Administrador de RabbitMQ: ver [`rabbitmq-admin/README.md`](rabbitmq-admin/README.md)
- Frontend: seccion **Mensajeria** del panel ADMIN (alerta de DLQ con consulta cada 5 s)
