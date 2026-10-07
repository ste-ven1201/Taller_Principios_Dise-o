package apexstore;

import apexstore.balanceador.BalanceadorCarga;
import apexstore.cliente.ClienteApexStore;
import apexstore.comun.ComunicadorIce;
import apexstore.contrato.CompraException;
import apexstore.contrato.EstadoOrden;
import apexstore.contrato.GestionarComprasPrx;
import apexstore.contrato.RespuestaCompra;
import apexstore.contrato.SolicitudCompra;
import apexstore.datos.AlmacenTransacciones;
import apexstore.nodos.NodoBackend;
import apexstore.nodos.NodoBalanceador;
import apexstore.nodos.NodoBaseDatos;
import apexstore.nodos.NodoPasarelas;
import apexstore.pasarelas.SimuladorPasarela.Modo;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Levanta los 5 nodos del diagrama corregido en una sola JVM (cada uno con su Communicator y su puerto)
 * y recorre los escenarios que justifican el rediseño. Para correr los nodos en procesos separados
 * se usan las clases Nodo* con los archivos de la carpeta config/.
 */
public final class Demo {

    private static final String H = "127.0.0.1";
    private static int verificaciones = 0;
    private static int exitosas = 0;
    private static int secuencia = 0;

    private Demo() { }

    public static void main(String[] args) throws Exception {
        // ------------------------------------------------------------------ arranque
        titulo("Arranque de los nodos");
        Communicator cBdPrimaria = comunicador("AdaptadorDatos.Endpoints", "tcp -h " + H + " -p 10101", "Datos.Rol", "primaria");
        AlmacenTransacciones bdPrimaria = NodoBaseDatos.iniciar(cBdPrimaria);
        Communicator cBdReplica = comunicador("AdaptadorDatos.Endpoints", "tcp -h " + H + " -p 10102", "Datos.Rol", "replica",
                "Datos.Primaria.Endpoint", "tcp -h " + H + " -p 10101");
        AlmacenTransacciones bdReplica = NodoBaseDatos.iniciar(cBdReplica);

        Communicator cBalanceador = comunicador("AdaptadorBalanceador.Endpoints", "tcp -h " + H + " -p 10400",
                "Balanceador.Replicas", "2",
                "Balanceador.Replica.1.Endpoint", "tcp -h " + H + " -p 10301",
                "Balanceador.Replica.2.Endpoint", "tcp -h " + H + " -p 10302");
        BalanceadorCarga balanceador = NodoBalanceador.iniciar(cBalanceador);

        Communicator cPasarelas = comunicador("AdaptadorPasarelas.Endpoints", "tcp -h " + H + " -p 10201",
                "Pasarelas.Notificacion.Proxy", "notificacionBalanceador:tcp -h " + H + " -p 10400",
                "Pasarelas.Banco.MinMs", "200", "Pasarelas.Banco.MaxMs", "600",
                "Pasarelas.ProbRechazo", "0.0", "Pasarelas.LentaMs", "4000");
        NodoPasarelas.Simuladores pasarelas = NodoPasarelas.iniciar(cPasarelas);

        String[] comunes = {"Datos.Primaria.Endpoint", "tcp -h " + H + " -p 10101",
                "Datos.Replica.Endpoint", "tcp -h " + H + " -p 10102",
                "Pasarelas.Endpoint", "tcp -h " + H + " -p 10201",
                "Backend.TimeoutPasarelaMs", "1500", "Backend.Breaker.Umbral", "3", "Backend.Breaker.EsperaMs", "3000"};
        Communicator cBackend1 = comunicador(concat(comunes, "Backend.Nombre", "R1",
                "AdaptadorBackend.Endpoints", "tcp -h " + H + " -p 10301"));
        NodoBackend.iniciar(cBackend1);
        Communicator cBackend2 = comunicador(concat(comunes, "Backend.Nombre", "R2",
                "AdaptadorBackend.Endpoints", "tcp -h " + H + " -p 10302"));
        NodoBackend.iniciar(cBackend2);

        Communicator cCliente = comunicador();
        GestionarComprasPrx prx = GestionarComprasPrx.uncheckedCast(
                cCliente.stringToProxy("balanceador:tcp -h " + H + " -p 10400")).ice_invocationTimeout(10000);
        ClienteApexStore cliente = new ClienteApexStore(prx);
        Thread.sleep(1500);

        // ------------------------------------------------------------------ 1
        titulo("Escenario 1: los tres medios de pago (despacho + callback asíncrono)");
        SolicitudCompra[] tres = {
            ClienteApexStore.compraStripe(id(), "ana", 120.50),
            ClienteApexStore.compraPSE(id(), "ana", 35.00),
            ClienteApexStore.compraCripto(id(), "ana", 500.00)
        };
        String idPrimeraOrden = tres[0].idOrden;
        for (SolicitudCompra s : tres) {
            long t0 = System.nanoTime();
            RespuestaCompra inicial = cliente.comprar(s);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            RespuestaCompra fin = cliente.esperarResultado(s.idOrden, 8000);
            System.out.printf("   %-6s %s: respuesta inicial %s en %d ms -> final %s (%s)%n",
                    s.medio, s.idOrden, inicial.estado, ms, fin.estado, fin.detalle);
            verificar(s.medio + " termina Confirmada", fin.estado == EstadoOrden.Confirmada);
        }

        // ------------------------------------------------------------------ 2
        titulo("Escenario 2: orden repetida no genera doble cobro (RAS-03)");
        int antes = pasarelas.stripe.cobrosAceptados();
        SolicitudCompra dup = ClienteApexStore.compraStripe("ORD-DUPLICADA", "beto", 80.0);
        RespuestaCompra a = cliente.comprar(dup);
        RespuestaCompra b = cliente.comprar(dup);
        cliente.esperarResultado(dup.idOrden, 8000);
        RespuestaCompra c3 = cliente.comprar(dup);
        int cobros = pasarelas.stripe.cobrosAceptados() - antes;
        System.out.printf("   3 envíos de la misma orden -> %s / %s / %s ; cobros reales en la pasarela: %d%n",
                a.estado, b.estado, c3.estado, cobros);
        verificar("una sola orden cobrada", cobros == 1);
        verificar("el reintento tardío devuelve el estado final", c3.estado == EstadoOrden.Confirmada);

        // ------------------------------------------------------------------ 3
        titulo("Escenario 3: PSE caída -> circuit breaker; Stripe y Cripto siguen funcionando");
        pasarelas.pse.setModo(Modo.CAIDA);
        int rechazadasRapido = 0;
        for (int i = 1; i <= 8; i++) {
            long t0 = System.nanoTime();
            RespuestaCompra r = cliente.comprar(ClienteApexStore.compraPSE(id(), "carla", 20.0));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("   PSE #%d -> %s en %d ms: %s%n", i, r.estado, ms, r.detalle);
            if (r.detalle.contains("circuit breaker")) {
                rechazadasRapido++;
            }
            if (i == 4) {
                SolicitudCompra s = ClienteApexStore.compraStripe(id(), "carla", 20.0);
                cliente.comprar(s);
                RespuestaCompra fin = cliente.esperarResultado(s.idOrden, 8000);
                System.out.println("   (mientras tanto) Stripe -> " + fin.estado);
                verificar("Stripe no se afecta por la caída de PSE", fin.estado == EstadoOrden.Confirmada);
            }
        }
        verificar("el circuit breaker de PSE se abrió", rechazadasRapido > 0);
        pasarelas.pse.setModo(Modo.NORMAL);
        System.out.println("   ... PSE se recupera; se espera la ventana del breaker (3 s)");
        Thread.sleep(3500);
        SolicitudCompra trial = ClienteApexStore.compraPSE(id(), "carla", 20.0);
        cliente.comprar(trial);
        RespuestaCompra finTrial = cliente.esperarResultado(trial.idOrden, 8000);
        System.out.println("   PSE tras recuperarse -> " + finTrial.estado);
        verificar("PSE vuelve a funcionar (breaker semiabierto -> cerrado)", finTrial.estado == EstadoOrden.Confirmada);

        titulo("Escenario 3b: Cripto no responde (red congestionada), 4 compras concurrentes");
        pasarelas.cripto.setModo(Modo.LENTA);
        long t0 = System.nanoTime();
        List<CompletableFuture<RespuestaCompra>> lentas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lentas.add(cliente.comprarAsync(ClienteApexStore.compraCripto(id(), "diego", 50.0)));
        }
        int rechazadasPorTiempo = 0;
        for (CompletableFuture<RespuestaCompra> f : lentas) {
            RespuestaCompra r = f.get();
            if (r.estado == EstadoOrden.Rechazada) {
                rechazadasPorTiempo++;
            }
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("   4 compras resueltas en %d ms en total (en paralelo, sin bloquear hilos): %d rechazadas%n",
                total, rechazadasPorTiempo);
        verificar("las 4 se resuelven en paralelo (< 4 s)", total < 4000);
        pasarelas.cripto.setModo(Modo.NORMAL);

        // ------------------------------------------------------------------ 4
        titulo("Escenario 4: pico de carga (300 compras simultáneas, banco de 200-600 ms)");
        int n = 300;
        List<Long> latencias = Collections.synchronizedList(new ArrayList<>());
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> pendientes = new ArrayList<>();
        long inicioPico = System.nanoTime();
        for (int i = 0; i < n; i++) {
            SolicitudCompra s = ClienteApexStore.compraStripe(id(), "cliente-" + i, 10.0 + i);
            long ti = System.nanoTime();
            pendientes.add(cliente.comprarAsync(s).handle((r, e) -> {
                latencias.add((System.nanoTime() - ti) / 1_000_000);
                if (e == null) {
                    ids.add(r.idOrden);
                }
                return null;
            }));
        }
        CompletableFuture.allOf(pendientes.toArray(new CompletableFuture[0])).get();
        long msPico = (System.nanoTime() - inicioPico) / 1_000_000;
        Collections.sort(latencias);
        long p50 = latencias.get(latencias.size() / 2);
        long p95 = latencias.get((int) (latencias.size() * 0.95) - 1);
        System.out.printf("   %d aceptadas en %d ms | latencia de aceptación P50=%d ms, P95=%d ms (RAS-02: < 250 ms)%n",
                ids.size(), msPico, p50, p95);
        Thread.sleep(1500);
        int confirmadas = 0;
        for (String idOrden : ids) {
            if (cliente.consultar(idOrden).estado == EstadoOrden.Confirmada) {
                confirmadas++;
            }
        }
        System.out.printf("   confirmadas por callback: %d de %d%n", confirmadas, ids.size());
        verificar("las 300 compras fueron aceptadas", ids.size() == n);
        verificar("las 300 quedaron Confirmadas", confirmadas == n);

        // ------------------------------------------------------------------ 5
        titulo("Escenario 5: cae la Réplica 1 del backend (sin SPOF en el contexto)");
        cBackend1.destroy();
        Thread.sleep(300);
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            SolicitudCompra s = ClienteApexStore.compraStripe(id(), "elena", 15.0);
            RespuestaCompra r = cliente.comprar(s);
            RespuestaCompra fin = cliente.esperarResultado(s.idOrden, 8000);
            if (fin.estado == EstadoOrden.Confirmada) {
                ok++;
            }
        }
        System.out.printf("   6 compras con R1 caída -> %d confirmadas por R2%n", ok);
        verificar("el sistema sigue operando con una sola réplica", ok == 6);

        // ------------------------------------------------------------------ 6
        titulo("Escenario 6: cae la BD primaria (failover a la réplica)");
        System.out.println("   transacciones en primaria=" + bdPrimaria.totalTransacciones()
                + ", en réplica=" + bdReplica.totalTransacciones());
        verificar("la réplica tiene los datos de la primaria", bdReplica.totalTransacciones() == bdPrimaria.totalTransacciones());
        cBdPrimaria.destroy();
        String resultadoInmediato;
        try {
            RespuestaCompra r = cliente.comprar(ClienteApexStore.compraStripe(id(), "fabio", 15.0));
            resultadoInmediato = "aceptada (" + r.estado + ")";
        } catch (CompraException e) {
            resultadoInmediato = "rechazada de forma segura: " + e.razon;
        }
        System.out.println("   compra justo después de la caída: " + resultadoInmediato);
        System.out.println("   ... se espera la promoción automática de la réplica");
        long limite = System.currentTimeMillis() + 8000;
        while (!bdReplica.esPrimaria() && System.currentTimeMillis() < limite) {
            Thread.sleep(200);
        }
        verificar("la réplica fue promovida", bdReplica.esPrimaria());
        SolicitudCompra postFailover = ClienteApexStore.compraStripe(id(), "fabio", 15.0);
        cliente.comprar(postFailover);
        RespuestaCompra finPost = cliente.esperarResultado(postFailover.idOrden, 8000);
        System.out.println("   compra tras el failover -> " + finPost.estado);
        verificar("se pueden registrar compras tras el failover", finPost.estado == EstadoOrden.Confirmada);
        RespuestaCompra vieja = cliente.consultar(idPrimeraOrden);
        System.out.println("   orden del Escenario 1 consultada desde la réplica -> " + vieja.estado);
        verificar("las órdenes anteriores sobreviven al failover", vieja.estado == EstadoOrden.Confirmada);

        titulo("Resumen");
        System.out.printf("   %d de %d verificaciones OK%n", exitosas, verificaciones);
        System.exit(exitosas == verificaciones ? 0 : 1);
    }

    private static void verificar(String descripcion, boolean condicion) {
        verificaciones++;
        if (condicion) {
            exitosas++;
        }
        System.out.println("   [" + (condicion ? "OK" : "FALLO") + "] " + descripcion);
    }

    private static String id() {
        return "ORD-" + (++secuencia);
    }

    private static void titulo(String texto) {
        System.out.println();
        System.out.println("=== " + texto + " ===");
    }

    private static String[] concat(String[] base, String... extra) {
        String[] r = new String[base.length + extra.length];
        System.arraycopy(base, 0, r, 0, base.length);
        System.arraycopy(extra, 0, r, base.length, extra.length);
        return r;
    }

    private static Communicator comunicador(String... pares) {
        InitializationData datos = ComunicadorIce.datosPorDefecto();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            datos.properties.setProperty(pares[i], pares[i + 1]);
        }
        return Util.initialize(datos);
    }
}
