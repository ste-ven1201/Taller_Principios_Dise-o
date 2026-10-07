package apexstore.pagos;

import apexstore.contrato.MedioPago;
import apexstore.contrato.PasarelaStripePrx;
import apexstore.contrato.SolicitudCompra;

import java.util.concurrent.CompletionStage;

/** Estrategia de tarjeta de crédito. Requiere autorizarCargoStripe. */
public class ClienteEstrategiaStripe implements EstrategiaPago {

    private final PasarelaStripePrx pasarela;

    public ClienteEstrategiaStripe(PasarelaStripePrx pasarela) {
        this.pasarela = pasarela;
    }

    @Override
    public MedioPago medio() {
        return MedioPago.Stripe;
    }

    @Override
    public void validar(SolicitudCompra s) {
        // La validación se realiza en la estrategia alojada en el nodo de pasarelas.
    }

    @Override
    public CompletionStage<String> cobrar(SolicitudCompra s) {
        return pasarela.autorizarCargoStripeAsync(s.idOrden, EstrategiaPago.datoRemoto(s, "tokenTarjeta"),
                s.montoUSD, EstrategiaPago.datoRemoto(s, "cvc"));
    }
}
