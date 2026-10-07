package apexstore.pasarelas;

import apexstore.comun.Bitacora;
import apexstore.contrato.NotificacionPagoPrx;
import apexstore.contrato.PasarelaException;
import apexstore.contrato.ResultadoPago;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor de simulación compartido por las tres pasarelas. NO se conecta a ningún servicio real.
 *
 * Flujo (RAS-01): la pasarela responde un ACUSE casi inmediato con una referencia y, más tarde
 * (latencia bancaria simulada), envía el resultado por el callback notificarTransaccionExitosa.
 * Así ningún hilo del servidor queda bloqueado esperando al banco.
 */
public class SimuladorPasarela {

    public enum Modo {
        /** Funciona con normalidad. */
        NORMAL,
        /** Rechaza de inmediato: la pasarela está caída. */
        CAIDA,
        /** No responde a tiempo (el acuse tarda más que el timeout del contexto). */
        LENTA,
        /** Falla aproximadamente la mitad de las veces. */
        INESTABLE
    }

    private final String nombre;
    private final ScheduledExecutorService planificador;
    private final NotificacionPagoPrx notificador;
    private final long bancoMinMs;
    private final long bancoMaxMs;
    private final double probRechazo;
    private final long latenciaLentaMs;
    private volatile Modo modo = Modo.NORMAL;
    private final AtomicInteger cobrosAceptados = new AtomicInteger();

    public SimuladorPasarela(String nombre, ScheduledExecutorService planificador, NotificacionPagoPrx notificador,
                             long bancoMinMs, long bancoMaxMs, double probRechazo, long latenciaLentaMs) {
        this.nombre = nombre;
        this.planificador = planificador;
        this.notificador = notificador;
        this.bancoMinMs = bancoMinMs;
        this.bancoMaxMs = bancoMaxMs;
        this.probRechazo = probRechazo;
        this.latenciaLentaMs = latenciaLentaMs;
    }

    public void setModo(Modo nuevo) {
        Bitacora.info("Pasarela-" + nombre, "modo de simulación -> " + nuevo);
        this.modo = nuevo;
    }

    public int cobrosAceptados() {
        return cobrosAceptados.get();
    }

    /** Devuelve el acuse (referencia del cobro) y programa el callback con el resultado. */
    public CompletionStage<String> procesarCobro(String idOrden, String resumen) {
        CompletableFuture<String> acuse = new CompletableFuture<>();
        Modo actual = modo;
        if (actual == Modo.INESTABLE) {
            actual = ThreadLocalRandom.current().nextBoolean() ? Modo.NORMAL : Modo.CAIDA;
        }
        switch (actual) {
            case CAIDA:
                acuse.completeExceptionally(new PasarelaException("Pasarela " + nombre + " no disponible"));
                break;
            case LENTA:
                // Nunca atiende a tiempo: el acuse falla mucho después de que el contexto ya expiró.
                planificador.schedule(() -> acuse.completeExceptionally(
                        new PasarelaException("Pasarela " + nombre + " respondió fuera de tiempo")),
                        latenciaLentaMs, TimeUnit.MILLISECONDS);
                break;
            default:
                String referencia = nombre.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
                cobrosAceptados.incrementAndGet();
                planificador.schedule(() -> acuse.complete(referencia), 20, TimeUnit.MILLISECONDS);
                long espera = 20 + bancoMinMs + (long) (ThreadLocalRandom.current().nextDouble()
                        * Math.max(1, bancoMaxMs - bancoMinMs));
                planificador.schedule(() -> enviarCallback(idOrden, referencia, resumen, 1),
                        espera, TimeUnit.MILLISECONDS);
        }
        return acuse;
    }

    private void enviarCallback(String idOrden, String referencia, String resumen, int intento) {
        boolean aprobado = ThreadLocalRandom.current().nextDouble() >= probRechazo;
        ResultadoPago resultado = new ResultadoPago(idOrden, aprobado, referencia,
                aprobado ? "Aprobado por " + nombre + " (" + resumen + ")" : "Rechazado por el banco (simulado)");
        notificador.notificarTransaccionExitosaAsync(resultado).whenComplete((v, error) -> {
            if (error != null && intento < 5) {
                Bitacora.info("Pasarela-" + nombre, "Callback de " + idOrden + " falló (" + intento
                        + "/5), se reintenta: " + error.getClass().getSimpleName());
                planificador.schedule(() -> enviarCallback(idOrden, referencia, resumen, intento + 1),
                        400L * intento, TimeUnit.MILLISECONDS);
            }
        });
    }
}
