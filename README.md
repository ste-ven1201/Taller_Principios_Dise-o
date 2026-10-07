# ApexStore

Prototipo de la arquitectura corregida de ApexStore, implementado en Java con ICE. La aplicación simula el proceso de compra, el despacho a distintos medios de pago y la confirmación de resultados mediante callbacks.

## Correcciones implementadas

### Persistencia centralizada

En el diseño original, la estrategia Cripto escribía directamente en la base de datos. En esta implementación, ninguna estrategia ni pasarela accede a la persistencia. El `ProcesadorPagosContexto` registra la orden, selecciona la estrategia correspondiente y recibe el resultado por callback. Luego actualiza el estado de la orden en la base de datos. Así, todas las formas de pago siguen el mismo flujo y el procesador conserva el control de la transacción.

### Reducción de puntos únicos de fallo

El backend tiene dos réplicas, atendidas por un balanceador. Para reducir el riesgo de que el propio balanceador sea un punto único de fallo, hay dos instancias en configuración activo/pasivo: la secundaria recibe heartbeats y puede asumir el tráfico si deja de recibirlos. Cuando vuelve la instancia activa, la secundaria retorna a modo pasivo.

La base de datos también tiene una instancia primaria y una réplica. La réplica sigue los cambios y puede promoverse si la primaria deja de responder. El estado compartido permite que cualquiera de las réplicas del backend consulte y actualice las órdenes.

## Alcance de la simulación

Las pasarelas no se conectan a servicios financieros reales. La base de datos y su replicación también se simulan en memoria; no se requiere instalar PostgreSQL para ejecutar la demo.
