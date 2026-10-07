package apexstore.balanceador;

import apexstore.comun.Bitacora;
import apexstore.contrato.HeartbeatBalanceador;
import apexstore.contrato.HeartbeatBalanceadorPrx;
import com.zeroc.Ice.Current;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class RolBalanceador implements HeartbeatBalanceador {
    private final boolean primario;
    private final long leaseMs;
    private final AtomicLong ultimoLatidoNanos = new AtomicLong(System.nanoTime());
    private volatile boolean promovido;

    public RolBalanceador(boolean primario, long leaseMs) {
        this.primario = primario;
        this.leaseMs = leaseMs;
    }

    public void iniciarHeartbeats(HeartbeatBalanceadorPrx respaldo, ScheduledExecutorService planificador,
                                  long intervaloMs) {
        if (!primario || respaldo == null) {
            return;
        }
        planificador.scheduleWithFixedDelay(() -> respaldo.heartbeatAsync("activo").whenComplete((v, error) -> {
            if (error != null) {
                Bitacora.info("Balanceador", "heartbeat al respaldo no entregado: "
                        + error.getClass().getSimpleName());
            }
        }), 0, intervaloMs, TimeUnit.MILLISECONDS);
    }

    public boolean esActivo() {
        if (primario) {
            return true;
        }
        boolean heartbeatVigente = System.nanoTime() - ultimoLatidoNanos.get()
                <= TimeUnit.MILLISECONDS.toNanos(leaseMs);
        if (heartbeatVigente) {
            if (promovido) {
                promovido = false;
                Bitacora.info("Balanceador", "heartbeat recuperado; vuelve a modo PASIVO");
            }
            return false;
        }
        if (!promovido) {
            promovido = true;
            Bitacora.info("Balanceador", "lease vencido; respaldo PROMOVIDO a ACTIVO");
        }
        return true;
    }

    @Override
    public void heartbeat(String emisor, Current current) {
        if (!primario) {
            ultimoLatidoNanos.set(System.nanoTime());
        }
    }

    public <T> CompletionStage<T> ejecutarCuandoActivo(Supplier<CompletionStage<T>> operacion) {
        if (esActivo()) {
            return ejecutar(operacion);
        }
        CompletableFuture<T> salida = new CompletableFuture<>();
        long limiteNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10000);
        esperarPromocion(operacion, salida, limiteNanos);
        return salida;
    }

    private <T> void esperarPromocion(Supplier<CompletionStage<T>> operacion, CompletableFuture<T> salida,
                                      long limiteNanos) {
        if (salida.isDone()) {
            return;
        }
        if (esActivo()) {
            ejecutar(operacion).whenComplete((v, e) -> {
                if (e == null) salida.complete(v);
                else salida.completeExceptionally(e);
            });
        } else if (System.nanoTime() >= limiteNanos) {
            salida.completeExceptionally(new IllegalStateException("El balanceador de respaldo sigue pasivo"));
        } else {
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(
                    () -> esperarPromocion(operacion, salida, limiteNanos));
        }
    }

    private static <T> CompletionStage<T> ejecutar(Supplier<CompletionStage<T>> operacion) {
        try {
            return operacion.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
