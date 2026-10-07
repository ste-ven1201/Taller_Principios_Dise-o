package apexstore.pagos;

import apexstore.comun.Bitacora;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Circuit breaker por medio de pago (Dimensión 3: tolerancia a fallos).
 *
 *  CERRADO     -> las llamadas pasan; los fallos consecutivos se cuentan.
 *  ABIERTO     -> se rechaza de inmediato, sin tocar la pasarela, durante esperaAbiertoMs.
 *  SEMIABIERTO -> pasa UNA llamada de prueba: si funciona se cierra, si falla se vuelve a abrir.
 *
 * Cada estrategia tiene su propia instancia, así que el fallo de PSE no afecta a Stripe ni a Cripto.
 */
public class CircuitBreaker {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    private final String nombre;
    private final int umbralFallos;
    private final long esperaAbiertoMs;
    private Estado estado = Estado.CERRADO;
    private int fallosConsecutivos = 0;
    private long abiertoDesde = 0;
    private boolean pruebaEnCurso = false;

    public CircuitBreaker(String nombre, int umbralFallos, long esperaAbiertoMs) {
        this.nombre = nombre;
        this.umbralFallos = umbralFallos;
        this.esperaAbiertoMs = esperaAbiertoMs;
    }

    public synchronized Estado estado() {
        return estado;
    }

    public <T> CompletionStage<T> ejecutar(Supplier<CompletionStage<T>> operacion) {
        if (!permitir()) {
            return CompletableFuture.failedFuture(new CircuitoAbiertoException(nombre));
        }
        CompletionStage<T> etapa;
        try {
            etapa = operacion.get();
        } catch (RuntimeException e) {
            registrarFallo();
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<T> salida = new CompletableFuture<>();
        etapa.whenComplete((resultado, error) -> {
            if (error == null) {
                registrarExito();
                salida.complete(resultado);
            } else {
                registrarFallo();
                salida.completeExceptionally(error);
            }
        });
        return salida;
    }

    private synchronized boolean permitir() {
        switch (estado) {
            case CERRADO:
                return true;
            case ABIERTO:
                if (System.currentTimeMillis() - abiertoDesde >= esperaAbiertoMs) {
                    estado = Estado.SEMIABIERTO;
                    pruebaEnCurso = true;
                    Bitacora.info("CircuitBreaker-" + nombre, "SEMIABIERTO: se deja pasar una llamada de prueba");
                    return true;
                }
                return false;
            default: // SEMIABIERTO
                if (!pruebaEnCurso) {
                    pruebaEnCurso = true;
                    return true;
                }
                return false;
        }
    }

    private synchronized void registrarExito() {
        if (estado != Estado.CERRADO) {
            Bitacora.info("CircuitBreaker-" + nombre, "CERRADO: la pasarela se recuperó");
        }
        estado = Estado.CERRADO;
        fallosConsecutivos = 0;
        pruebaEnCurso = false;
    }

    private synchronized void registrarFallo() {
        pruebaEnCurso = false;
        fallosConsecutivos++;
        if (estado == Estado.SEMIABIERTO || fallosConsecutivos >= umbralFallos) {
            if (estado != Estado.ABIERTO) {
                Bitacora.info("CircuitBreaker-" + nombre, "ABIERTO tras " + fallosConsecutivos
                        + " fallo(s); se rechazan cobros por " + esperaAbiertoMs + " ms");
            }
            estado = Estado.ABIERTO;
            abiertoDesde = System.currentTimeMillis();
        }
    }
}
