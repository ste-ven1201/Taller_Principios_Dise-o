package apexstore.pasarelas;

import apexstore.contrato.PasarelaCripto;
import apexstore.contrato.PasarelaException;
import com.zeroc.Ice.Current;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lollipop generarCobroCriptoBtc (simulado). */
public class EstrategiaCripto implements PasarelaCripto {

    private final SimuladorPasarela motor;

    public EstrategiaCripto(SimuladorPasarela motor) {
        this.motor = motor;
    }

    @Override
    public CompletionStage<String> generarCobroCriptoBtcAsync(String idOrden, String direccionWallet,
                                                             String redBlockchain, long satoshis, Current current) {
        if (vacio(direccionWallet) || vacio(redBlockchain) || satoshis <= 0) {
            return CompletableFuture.failedFuture(new PasarelaException("Datos de wallet inválidos"));
        }
        return motor.procesarCobro(idOrden, satoshis + " sats en " + redBlockchain);
    }

    private static boolean vacio(String valor) { return valor == null || valor.trim().isEmpty(); }
}
