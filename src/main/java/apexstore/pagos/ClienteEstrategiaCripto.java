package apexstore.pagos;

import apexstore.contrato.MedioPago;
import apexstore.contrato.PasarelaCriptoPrx;
import apexstore.contrato.SolicitudCompra;

import java.util.concurrent.CompletionStage;

/** Estrategia de pago con Bitcoin. Requiere generarCobroCriptoBtc. */
public class ClienteEstrategiaCripto implements EstrategiaPago {

    private static final double USD_POR_BTC = 60000.0;
    private static final double SATOSHIS_POR_BTC = 100_000_000.0;

    private final PasarelaCriptoPrx pasarela;

    public ClienteEstrategiaCripto(PasarelaCriptoPrx pasarela) {
        this.pasarela = pasarela;
    }

    @Override
    public MedioPago medio() {
        return MedioPago.Cripto;
    }

    @Override
    public void validar(SolicitudCompra s) {
        // La validación y conversión se realizan en la estrategia del nodo de pasarelas.
    }

    @Override
    public CompletionStage<String> cobrar(SolicitudCompra s) {
        long satoshis = Math.round(s.montoUSD / USD_POR_BTC * SATOSHIS_POR_BTC);
        return pasarela.generarCobroCriptoBtcAsync(s.idOrden, EstrategiaPago.datoRemoto(s, "direccionWallet"),
                EstrategiaPago.datoRemoto(s, "redBlockchain"), satoshis);
    }
}
