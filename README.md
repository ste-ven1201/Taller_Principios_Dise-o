# ApexStore: implementación Java + ICE (Punto 4)

Implementa el diagrama de despliegue **corregido** del Punto 3. Las pasarelas de pago están
**completamente simuladas**: ningún componente se conecta a servicios financieros reales.

Las tres estrategias se sirven desde el Nodo 4 y el ProcesadorPagosContexto de cada backend las invoca
remotamente. Las estrategias validan los datos específicos del medio y delegan en el proveedor simulado local.
Los adaptadores ICE del backend traducen el monto común de la solicitud a `valorCOP` para PSE y `satoshis`
para Cripto, conforme a las firmas descritas en el enunciado.

## Estructura

```
src/main/slice/ApexStore.ice          contrato ICE (una interfaz Slice por lollipop del diagrama)
src/main/java/apexstore/
  comun/         Asinc, Bitacora, ComunicadorIce
  cliente/       Nodo 1  - ClienteApexStore (WebApp / MobileApp)
  balanceador/   Nodo 2  - BalanceadorCarga
  checkout/      Nodo 3  - ServicioCheckout
  pagos/         Nodo 3  - ProcesadorPagosContexto, selector remoto, CircuitBreaker
  pasarelas/     Nodo 4  - EstrategiaStripe/PSE/Cripto y proveedores simulados
  datos/         Nodo 5  - BD simulada, replicación y failover
  nodos/         main() de cada nodo
  Demo.java      los 5 nodos en una JVM + escenarios
config/          un .cfg por nodo (para correrlos en procesos separados)
```

## Mapeo diagrama → código

| Lollipop del diagrama | Interfaz Slice | Lo provee (identidad ICE) | Lo requieren |
|---|---|---|---|
| `gestionarComprasHttp` | `GestionarCompras` | `BalanceadorCarga` (`balanceador`) | `ClienteApexStore` |
| `gestionarComprasInterno` | `GestionarCompras` | `ServicioCheckout` R1/R2 (`servicioCheckout`) | `BalanceadorCarga` |
| `iniciarPagoOrden` | `OrquestadorPago` | `ProcesadorPagosContexto` (`procesadorPagos`) | `ServicioCheckout` |
| `autorizarCargoStripe` | `PasarelaStripe` | `EstrategiaStripe` (`pasarelaStripe`) | `ProcesadorPagosContexto` |
| `debitarTransferenciaPSE` | `PasarelaPSE` | `EstrategiaPSE` (`pasarelaPSE`) | `ProcesadorPagosContexto` |
| `generarCobroCriptoBtc` | `PasarelaCripto` | `EstrategiaCripto` (`pasarelaCripto`) | `ProcesadorPagosContexto` |
| `notificarTransaccionExitosa` | `NotificacionPago` | `BalanceadorCarga` (`notificacionBalanceador`) | las 3 pasarelas (`SimuladorPasarela`) |
| `notificarTransaccionInterno` | `NotificacionPago` | `ProcesadorPagosContexto` R1/R2 (`notificacionProcesador`) | `BalanceadorCarga` |
| `persistirTransaccionPostgres` | `Persistencia` | BD primaria (`persistencia`) | `ProcesadorPagosContexto` (único) |
| `replicacion` | `Replicacion` | BD primaria (`replicacion`) | BD réplica (`SeguidorReplicacion`) |

Cada componente con dos lollipops (balanceador, procesador) expone **dos objetos ICE** con identidades
distintas, porque un servant de ICE solo puede implementar una interfaz Slice.

Cambios de firma respecto al diagrama (necesarios para que el flujo asíncrono funcione):
las operaciones de estrategia reciben `idOrden` como primer parámetro (para correlacionar el callback),
`gestionarCompras*` e `iniciarPagoOrden` reciben una `SolicitudCompra`, y el callback lleva un
`ResultadoPago` con el campo `exito` (así notifica también los rechazos del banco).

## Cómo ejecutar

Requisitos: Java 11+ y acceso a Maven Central.

```
gradle runDemo          # los 5 nodos en una JVM + escenarios (recomendado para la sustentación)
```

Un proceso por nodo (una terminal por tarea, en este orden):

```
gradle runBaseDatosPrimaria
gradle runBaseDatosReplica
gradle runBalanceador
gradle runPasarelas
gradle runBackend1
gradle runBackend2
gradle runCliente
```

Sin Gradle (Linux/macOS con Ice 3.7 instalado): `ICE_JAR=/ruta/ice-3.7.10.jar scripts/ejecutar-sin-gradle.sh`.
En consolas de Windows, si las tildes salen mal, ejecutar con `-Dstdout.encoding=UTF-8` (los tasks de Gradle ya lo incluyen).

Para provocar fallos con procesos separados, editar `config/pasarelas.cfg`
(`Pasarelas.PSE.Modo=CAIDA`, `Pasarelas.Cripto.Modo=LENTA`, ...) o detener con Ctrl+C un backend o la BD primaria.

## Qué demuestra la demo

| Escenario | Qué prueba | Relación con el análisis |
|---|---|---|
| 1. Tres medios de pago | Despacho con acuse inmediato + confirmación por callback | Dim. 2 y 4, RAS-01 |
| 2. Orden repetida | Un solo cobro real aunque se envíe 3 veces (idempotencia por `idOrden`) | RAS-03 |
| 3. PSE caída | El circuit breaker se abre; Stripe sigue funcionando; PSE se recupera (semiabierto → cerrado) | Dim. 3, mal uso 5 |
| 3b. Cripto no responde | 4 compras se resuelven en paralelo en ~1,5 s sin bloquear hilos | Dim. 2 y 3 |
| 4. Pico de 300 compras | Aceptación con P95 ≈ 220 ms y 300/300 confirmadas por callback | RAS-01, RAS-02 |
| 5. Cae la réplica R1 | El balanceador detecta la caída y todo sigue por R2 | Dim. 6, mal uso 5 |
| 6. Cae la BD primaria | La réplica se promueve sola; no se pierden órdenes; durante la conmutación se rechaza sin cobrar | Dim. 3 y 5, RAS-03 |

**Mal uso 4 (Cripto → BD):** en el código solo `ProcesadorPagosContexto` tiene el proxy de `Persistencia`.
Las estrategias y las pasarelas no lo conocen: su único camino de vuelta es el callback.

## Decisiones, trade-offs y limitaciones (para el informe)

- El proyecto representa los clientes con un cliente Java de consola, usa ICE/TCP para el ingreso en lugar
  de HTTPS y simula la BD en memoria; esas partes aún no equivalen a React/Flutter, HTTPS ni PostgreSQL
  desplegados como en el diagrama.

- **Las pasarelas siguen simuladas**: cada estrategia desplegada en Nodo 4 valida/transforma su solicitud y delega
  en un motor local que no se conecta a Stripe, PSE ni una red blockchain reales.
- **La BD está simulada en memoria** (`AlmacenTransacciones`): imita atomicidad, estados finales inmutables y un
  log de replicación, pero no es PostgreSQL. Para producción se reemplaza por un repositorio JDBC detrás de la
  misma interfaz `Persistencia`.
- **El circuit breaker es por réplica**: cada `ProcesadorPagosContexto` cuenta sus propios fallos, por eso en la
  demo se necesitan 3 fallos en R1 y 3 en R2 antes de que ambos abran. Compartir el estado exigiría un
  almacén distribuido (más complejidad y otro posible SPOF).
- **Pasarela lenta = sin cobro en la simulación.** En el mundo real, un timeout deja la duda de si el banco cobró;
  haría falta un proceso de conciliación (consultar el estado a la pasarela). No se implementó.
- **Failover de BD:** el proxy lista primaria y réplica con selección ordenada; la réplica en espera rechaza
  escrituras hasta promoverse, así que nunca hay dos primarias activas. Si la primaria original vuelve sin
  intervención, habría que reintegrarla como réplica (no implementado).
- **Ventana de conmutación:** durante ~1,5 s tras caer la primaria las compras nuevas se rechazan de forma segura
  (antes de cobrar). Se prefirió consistencia sobre disponibilidad en ese instante (RAS-03 sobre RAS-01).
- **Las pasarelas siguen en un solo nodo** (decisión del Punto 3); el balanceador y el backend sí están replicados.
- **Medición de latencia:** el P95 de la demo se mide con los 5 nodos en una sola JVM y loopback. Es indicativo
  del comportamiento asíncrono, no una prueba de carga formal de RAS-02.
- El estado de la orden vive en la BD, no en la memoria de las réplicas: por eso el callback puede llegar a
  cualquier réplica y el balanceador puede reintentar una compra en la otra réplica sin riesgo de doble cobro.
