package apexstore.datos;

import apexstore.comun.Bitacora;
import apexstore.contrato.EntradaLog;
import apexstore.contrato.ReplicacionPrx;
import com.zeroc.Ice.LocalException;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tarea de la réplica: copia periódicamente el log de la primaria y, si la primaria deja de
 * responder varias veces seguidas, se promueve a sí misma (failover automático).
 */
public class SeguidorReplicacion {

    private final AlmacenTransacciones almacen;
    private final ReplicacionPrx primaria;
    private final int umbralFallos;
    private final ScheduledExecutorService planificador =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "seguidor-replicacion");
                t.setDaemon(true);
                return t;
            });
    private int fallosConsecutivos = 0;

    public SeguidorReplicacion(AlmacenTransacciones almacen, ReplicacionPrx primaria, int umbralFallos) {
        this.almacen = almacen;
        this.primaria = primaria.ice_invocationTimeout(500);
        this.umbralFallos = umbralFallos;
    }

    public void iniciar(long periodoMs) {
        planificador.scheduleWithFixedDelay(this::copiar, periodoMs, periodoMs, TimeUnit.MILLISECONDS);
    }

    public void detener() {
        planificador.shutdownNow();
    }

    private void copiar() {
        if (almacen.esPrimaria()) {
            planificador.shutdown();
            return;
        }
        try {
            EntradaLog[] nuevas = primaria.obtenerDesde(almacen.ultimaSecuencia(), 500);
            almacen.aplicarReplicado(nuevas);
            fallosConsecutivos = 0;
        } catch (LocalException e) {
            fallosConsecutivos++;
            Bitacora.info(almacen.nombre(), "Primaria no responde (" + fallosConsecutivos + "/" + umbralFallos + ")");
            if (fallosConsecutivos >= umbralFallos) {
                almacen.promover();
                planificador.shutdown();
            }
        }
    }
}
