package apexstore.pasarelas;

import apexstore.contrato.PasarelaException;
import apexstore.contrato.PasarelaPSE;
import com.zeroc.Ice.Current;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lollipop debitarTransferenciaPSE (simulado). */
public class EstrategiaPSE implements PasarelaPSE {

    private final SimuladorPasarela motor;

    public EstrategiaPSE(SimuladorPasarela motor) {
        this.motor = motor;
    }

    @Override
    public CompletionStage<String> debitarTransferenciaPSEAsync(String idOrden, String codigoBanco, String tipoDoc,
                                                               String numCuenta, double valorCOP, Current current) {
        if (vacio(codigoBanco) || vacio(tipoDoc) || vacio(numCuenta) || valorCOP <= 0) {
            return CompletableFuture.failedFuture(new PasarelaException("Datos bancarios inválidos"));
        }
        return motor.procesarCobro(idOrden, String.format("COP %.0f", valorCOP));
    }

    private static boolean vacio(String valor) { return valor == null || valor.trim().isEmpty(); }
}
