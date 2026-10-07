package apexstore.pagos;

import apexstore.comun.Asinc;
import apexstore.comun.Bitacora;
import apexstore.contrato.CompraException;
import apexstore.contrato.EstadoOrden;
import apexstore.contrato.MedioPago;
import apexstore.contrato.NoEsPrimarioException;
import apexstore.contrato.NotificacionPago;
import apexstore.contrato.OrquestadorPago;
import apexstore.contrato.PasarelaException;
import apexstore.contrato.PersistenciaPrx;
import apexstore.contrato.RespuestaCompra;
import apexstore.contrato.ResultadoPago;
import apexstore.contrato.SolicitudCompra;
import apexstore.contrato.Transaccion;
import apexstore.contrato.TransaccionNoEncontrada;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.TimeoutException;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Strategy Context. Es el ÚNICO componente que coordina un pago y el ÚNICO que escribe en la BD
 * (corrige el mal uso #4: ya no hay acceso directo de EstrategiaCripto a la base de datos).
 *
 * Provee: iniciarPagoOrden (este objeto) y notificarTransaccionInterno (lollipopNotificacion()).
 * Requiere: las tres pasarelas (vía estrategias) y persistirTransaccionPostgres.
 *
 * Todo el flujo es asíncrono: ningún hilo de ICE espera al banco (RAS-01, Dimensión 2).
 */
public class ProcesadorPagosContexto implements OrquestadorPago {

    private final String nombre;
    private final PersistenciaPrx persistencia;
    private final Map<MedioPago, EstrategiaPago> estrategias = new EnumMap<>(MedioPago.class);
    private final Map<MedioPago, CircuitBreaker> breakers = new EnumMap<>(MedioPago.class);

    public ProcesadorPagosContexto(String nombre, PersistenciaPrx persistencia) {
        this.nombre = nombre;
        this.persistencia = persistencia;
    }

    /** Registra una estrategia con su propio circuit breaker (extensibilidad, RAS-04). */
    public void registrar(EstrategiaPago estrategia, CircuitBreaker breaker) {
        estrategias.put(estrategia.medio(), estrategia);
        breakers.put(estrategia.medio(), breaker);
    }

    public CircuitBreaker breakerDe(MedioPago medio) {
        return breakers.get(medio);
    }

    // ---------------------------------------------------------------- iniciarPagoOrden

    @Override
    public CompletionStage<RespuestaCompra> iniciarPagoOrdenAsync(SolicitudCompra s, Current current)
            throws CompraException {
        if (s == null || s.idOrden == null || s.idOrden.trim().isEmpty()) {
            throw new CompraException("La orden debe tener un identificador");
        }
        if (s.montoUSD <= 0) {
            throw new CompraException("El monto debe ser mayor que cero");
        }
        EstrategiaPago estrategia = estrategias.get(s.medio);
        if (estrategia == null) {
            throw new CompraException("Medio de pago no soportado: " + s.medio);
        }
        try {
            estrategia.validar(s);
        } catch (IllegalArgumentException e) {
            throw new CompraException(e.getMessage());
        }

        long ahora = System.currentTimeMillis();
        Transaccion pendiente = new Transaccion(s.idOrden, s.idCliente, s.medio, s.montoUSD,
                EstadoOrden.Pendiente, "", "Orden registrada, cobro en proceso", ahora, ahora);

        // 1) Primero se registra la orden: nunca se cobra algo que no esté persistido (RAS-03).
        CompletionStage<RespuestaCompra> flujo = persistencia.persistirTransaccionPostgresAsync(pendiente)
                .thenCompose(resultado -> {
                    if (!resultado.aplicada) {
                        // 2a) Orden repetida: se devuelve el estado actual y NO se cobra otra vez.
                        Bitacora.info(nombre, "Orden " + s.idOrden + " repetida; se devuelve su estado ("
                                + resultado.actual.estado + ") sin volver a cobrar");
                        return CompletableFuture.completedFuture(respuestaDe(resultado.actual));
                    }
                    // 2b) Orden nueva: se despacha a la estrategia protegida por su circuit breaker.
                    return breakers.get(s.medio).ejecutar(() -> estrategia.cobrar(s))
                            .handle((referencia, error) -> error == null
                                    ? CompletableFuture.completedFuture(new RespuestaCompra(s.idOrden,
                                            EstadoOrden.Pendiente, referencia,
                                            "Cobro en proceso; la confirmación llega por callback"))
                                    : rechazar(pendiente, motivoDe(error)))
                            .thenCompose(x -> x);
                });

        return Asinc.desenvolver(flujo.handle((respuesta, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(respuesta);
            }
            CompletableFuture<RespuestaCompra> fallo = new CompletableFuture<>();
            Throwable causa = Asinc.causa(error);
            fallo.completeExceptionally(causa instanceof CompraException ? causa
                    : new CompraException(razonDePersistencia(causa)));
            return fallo;
        }).thenCompose(x -> x));
    }

    /** El cobro no pudo despacharse: la orden queda Rechazada y la respuesta es inmediata. */
    private CompletionStage<RespuestaCompra> rechazar(Transaccion pendiente, String motivo) {
        Transaccion rechazada = pendiente.clone();
        rechazada.estado = EstadoOrden.Rechazada;
        rechazada.detalle = motivo;
        rechazada.actualizadaMs = System.currentTimeMillis();
        Bitacora.info(nombre, "Orden " + pendiente.idOrden + " RECHAZADA: " + motivo);
        return persistencia.persistirTransaccionPostgresAsync(rechazada)
                .thenApply(r -> new RespuestaCompra(pendiente.idOrden, EstadoOrden.Rechazada, "", motivo));
    }

    /** Mensaje para el cliente cuando no se pudo registrar la orden (y por tanto NO se cobró nada). */
    private static String razonDePersistencia(Throwable causa) {
        if (causa instanceof NoEsPrimarioException || causa instanceof com.zeroc.Ice.LocalException) {
            return "Registro de órdenes no disponible temporalmente (conmutación de la BD en curso). "
                    + "No se realizó ningún cobro; reintente en unos segundos.";
        }
        return "No fue posible registrar la orden: " + causa.getClass().getSimpleName();
    }

    private static String motivoDe(Throwable error) {
        Throwable causa = Asinc.causa(error);
        if (causa instanceof CircuitoAbiertoException) {
            return "Medio de pago temporalmente no disponible (circuit breaker abierto)";
        }
        if (causa instanceof TimeoutException) {
            return "La pasarela no respondió a tiempo";
        }
        if (causa instanceof PasarelaException) {
            return ((PasarelaException) causa).razon;
        }
        return "Error al contactar la pasarela: " + causa.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- callback

    /**
     * Callback asíncrono de las pasarelas (llega a través del balanceador). Es idempotente:
     * si la pasarela reintenta, la BD ignora la segunda notificación.
     */
    private CompletionStage<Void> procesarNotificacion(ResultadoPago resultado) {
        CompletableFuture<Void> salida = new CompletableFuture<>();
        persistencia.consultarTransaccionAsync(resultado.idOrden)
                .thenCompose(existente -> {
                    Transaccion actualizada = existente.clone();
                    actualizada.estado = resultado.exito ? EstadoOrden.Confirmada : EstadoOrden.Rechazada;
                    actualizada.referencia = resultado.referencia;
                    actualizada.detalle = resultado.detalle;
                    actualizada.actualizadaMs = System.currentTimeMillis();
                    return persistencia.persistirTransaccionPostgresAsync(actualizada);
                })
                .whenComplete((r, error) -> {
                    if (error == null) {
                        Bitacora.info(nombre, "Callback de " + resultado.idOrden + " -> "
                                + (r.aplicada ? r.actual.estado : "duplicado ignorado (" + r.actual.estado + ")"));
                        salida.complete(null);
                        return;
                    }
                    Throwable causa = Asinc.causa(error);
                    if (causa instanceof TransaccionNoEncontrada) {
                        // Pago sin orden: no se reintenta, se deja constancia para conciliación manual.
                        Bitacora.info(nombre, "ALERTA: callback de una orden desconocida (" + resultado.idOrden + ")");
                        salida.complete(null);
                    } else {
                        // Fallo transitorio (p. ej. failover de BD en curso): se propaga para que la pasarela reintente.
                        salida.completeExceptionally(causa);
                    }
                });
        return salida;
    }

    /** Segundo lollipop del componente: notificarTransaccionInterno (objeto ICE independiente). */
    public NotificacionPago lollipopNotificacion() {
        return new NotificacionPago() {
            @Override
            public CompletionStage<Void> notificarTransaccionExitosaAsync(ResultadoPago resultado, Current current) {
                return procesarNotificacion(resultado);
            }
        };
    }

    // ---------------------------------------------------------------- consulta

    @Override
    public CompletionStage<RespuestaCompra> consultarEstadoOrdenAsync(String idOrden, Current current)
            throws CompraException {
        CompletableFuture<RespuestaCompra> salida = new CompletableFuture<>();
        persistencia.consultarTransaccionAsync(idOrden).whenComplete((t, error) -> {
            if (error == null) {
                salida.complete(respuestaDe(t));
            } else {
                Throwable causa = Asinc.causa(error);
                salida.completeExceptionally(causa instanceof TransaccionNoEncontrada
                        ? new CompraException("Orden no encontrada: " + idOrden) : causa);
            }
        });
        return salida;
    }

    private static RespuestaCompra respuestaDe(Transaccion t) {
        return new RespuestaCompra(t.idOrden, t.estado, t.referencia, t.detalle);
    }
}
