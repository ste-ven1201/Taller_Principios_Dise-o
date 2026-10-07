package apexstore.pagos;

/** Se lanza (sin llamar a la pasarela) cuando el circuit breaker de un medio de pago está abierto. */
public class CircuitoAbiertoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CircuitoAbiertoException(String medio) {
        super("Circuit breaker abierto para " + medio);
    }
}
