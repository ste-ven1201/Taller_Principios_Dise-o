package apexstore.balanceador;

import apexstore.comun.Asinc;
import apexstore.comun.Bitacora;
import apexstore.contrato.CompraException;
import apexstore.contrato.GestionarCompras;
import apexstore.contrato.GestionarComprasPrx;
import apexstore.contrato.NotificacionPago;
import apexstore.contrato.NotificacionPagoPrx;
import apexstore.contrato.RespuestaCompra;
import apexstore.contrato.ResultadoPago;
import apexstore.contrato.SolicitudCompra;
import com.zeroc.Ice.CommunicatorDestroyedException;
import com.zeroc.Ice.ConnectionManuallyClosedException;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.ObjectAdapterDeactivatedException;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.SocketException;
import com.zeroc.Ice.TimeoutException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Balanceador de carga (Nodo 2). Un solo componente con dos caras:
 *  - provee gestionarComprasHttp (este objeto, hacia los clientes) y lo reenvía a gestionarComprasInterno de R1/R2;
 *  - provee notificarTransaccionExitosa (lollipopNotificacion(), hacia las pasarelas) y lo reenvía a notificarTransaccionInterno de R1/R2.
 *
 * Reparte con round-robin, hace health checks periódicos y, si una réplica falla a mitad de
 * una petición, reintenta con la otra. Es seguro porque el ProcesadorPagosContexto es idempotente
 * por idOrden y el estado vive en la BD, no en la memoria de la réplica.
 */
public class BalanceadorCarga implements GestionarCompras {

    /** Una réplica del backend vista desde el balanceador. */
    public static final class Replica {
        final String nombre;
        final GestionarComprasPrx servicio;
        final NotificacionPagoPrx procesador;
        volatile boolean viva = true;

        public Replica(String nombre, GestionarComprasPrx servicio, NotificacionPagoPrx procesador) {
            this.nombre = nombre;
            this.servicio = servicio;
            this.procesador = procesador;
        }
    }

    private final List<Replica> replicas;
    private final AtomicInteger turno = new AtomicInteger();
    private final RolBalanceador rol;

    public BalanceadorCarga(List<Replica> replicas, ScheduledExecutorService planificador, long periodoHealthMs,
                            RolBalanceador rol) {
        this.replicas = replicas;
        this.rol = rol;
        planificador.scheduleWithFixedDelay(this::revisarSalud, periodoHealthMs, periodoHealthMs, TimeUnit.MILLISECONDS);
    }

    public boolean esActivo() {
        return rol.esActivo();
    }

    // ---------------------------------------------------------------- gestionarComprasHttp

    @Override
    public CompletionStage<RespuestaCompra> iniciarCompraAsync(SolicitudCompra solicitud, Current current)
            throws CompraException {
        return rol.ejecutarCuandoActivo(() -> conFailover(r -> r.servicio.iniciarCompraAsync(solicitud)));
    }

    @Override
    public CompletionStage<RespuestaCompra> consultarOrdenAsync(String idOrden, Current current)
            throws CompraException {
        return rol.ejecutarCuandoActivo(() -> conFailover(r -> r.servicio.consultarOrdenAsync(idOrden)));
    }

    // ---------------------------------------------------------------- notificarTransaccionExitosa

    /** Segundo lollipop del balanceador: notificarTransaccionExitosa (objeto ICE independiente). */
    public NotificacionPago lollipopNotificacion() {
        return new NotificacionPago() {
            @Override
            public CompletionStage<Void> notificarTransaccionExitosaAsync(ResultadoPago resultado, Current current) {
                return rol.ejecutarCuandoActivo(
                        () -> conFailover(r -> r.procesador.notificarTransaccionExitosaAsync(resultado)));
            }
        };
    }

    // ---------------------------------------------------------------- failover y salud

    private <R> CompletionStage<R> conFailover(Function<Replica, CompletionStage<R>> operacion) {
        CompletableFuture<R> salida = new CompletableFuture<>();
        intentar(operacion, turno.getAndIncrement(), 0, salida, null);
        return salida;
    }

    private <R> void intentar(Function<Replica, CompletionStage<R>> operacion, int inicio, int n,
                              CompletableFuture<R> salida, Throwable ultimoFallo) {
        if (n >= replicas.size()) {
            salida.completeExceptionally(ultimoFallo != null ? ultimoFallo
                    : new CompraException("No hay réplicas del backend disponibles"));
            return;
        }
        boolean hayVivas = replicas.stream().anyMatch(r -> r.viva);
        Replica candidata = replicas.get(Math.floorMod(inicio + n, replicas.size()));
        if (hayVivas && !candidata.viva) {
            intentar(operacion, inicio, n + 1, salida, ultimoFallo);
            return;
        }
        CompletionStage<R> etapa;
        try {
            etapa = operacion.apply(candidata);
        } catch (RuntimeException e) {
            etapa = CompletableFuture.failedFuture(e);
        }
        etapa.whenComplete((valor, error) -> {
            if (error == null) {
                salida.complete(valor);
                return;
            }
            Throwable causa = Asinc.causa(error);
            if (esFalloDeRed(causa)) {
                if (candidata.viva) {
                    Bitacora.info("Balanceador", "Réplica " + candidata.nombre + " marcada como CAÍDA ("
                            + causa.getClass().getSimpleName() + "); se reintenta con otra");
                }
                candidata.viva = false;
                intentar(operacion, inicio, n + 1, salida, causa);
            } else {
                salida.completeExceptionally(causa);
            }
        });
    }

    private static boolean esFalloDeRed(Throwable t) {
        return t instanceof SocketException
                || t instanceof TimeoutException
                || t instanceof ConnectionManuallyClosedException
                || t instanceof ObjectAdapterDeactivatedException
                || t instanceof CommunicatorDestroyedException;
    }

    private void revisarSalud() {
        for (Replica r : replicas) {
            ObjectPrx sonda = r.servicio.ice_invocationTimeout(800);
            sonda.ice_pingAsync().whenComplete((v, error) -> {
                boolean vivaAhora = error == null;
                if (vivaAhora != r.viva) {
                    Bitacora.info("Balanceador", "Health check: réplica " + r.nombre
                            + (vivaAhora ? " RECUPERADA" : " CAÍDA"));
                }
                r.viva = vivaAhora;
            });
        }
    }
}
