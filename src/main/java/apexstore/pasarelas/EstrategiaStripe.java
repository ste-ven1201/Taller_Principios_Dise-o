package apexstore.pasarelas;

import apexstore.contrato.PasarelaException;
import apexstore.contrato.PasarelaStripe;
import com.zeroc.Ice.Current;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lollipop autorizarCargoStripe (simulado). */
public class EstrategiaStripe implements PasarelaStripe {

    private final SimuladorPasarela motor;

    public EstrategiaStripe(SimuladorPasarela motor) {
        this.motor = motor;
    }

    @Override
    public CompletionStage<String> autorizarCargoStripeAsync(String idOrden, String tokenTarjeta, double montoUSD,
                                                            String cvcSeguridad, Current current) {
        if (tokenTarjeta == null || tokenTarjeta.trim().isEmpty() || montoUSD <= 0
                || cvcSeguridad == null || !cvcSeguridad.matches("\\d{3,4}")) {
            return CompletableFuture.failedFuture(new PasarelaException("Datos de tarjeta inválidos"));
        }
        return motor.procesarCobro(idOrden, String.format("USD %.2f", montoUSD));
    }
}
