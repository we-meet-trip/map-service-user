package map.service.user.trip;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import map.service.user.config.HubClientConfig;
import map.service.user.global.crypto.LocationSeal;
import map.service.user.trip.dto.HubDirectionsDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

class HubDirectionsBudgetTest {
    @Test void directionsDefaultBudgetIs20WhileOtherHubRequestsRemain5() throws Exception {
        var methods=HubClientConfig.class.getDeclaredMethods();
        var route=Arrays.stream(methods).filter(m->m.getName().equals("hubDirectionsRestClient")).findFirst().orElseThrow();
        var other=Arrays.stream(methods).filter(m->m.getName().equals("hubRestClient")).findFirst().orElseThrow();
        assertThat(route.getParameters()[2].getAnnotation(Value.class).value()).isEqualTo("${hub.directions-timeout-seconds:20}");
        assertThat(other.getParameters()[2].getAnnotation(Value.class).value()).isEqualTo("${hub.timeout-seconds:5}");
        assertThatThrownBy(()->new HubClientConfig().hubDirectionsRestClient(RestClient.builder(),"http://127.0.0.1",0,""))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void twentyLegBatchHasOneHttpTimeoutBudgetAndNoApplicationRetry() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        List<Integer> sizes=new CopyOnWriteArrayList<>();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/v1/directions/batch",exchange->{
            try {
                requests.incrementAndGet();
                sizes.add(mapper.readTree(exchange.getRequestBody()).path("legs").size());
                Thread.sleep(2200);
                byte[] body="{\"routes\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start();
        try {
            var rest=new HubClientConfig().hubDirectionsRestClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),1,"");
            LocationSeal seal=mock(LocationSeal.class);
            var client=new HubDirectionsClient(rest,seal);
            List<LegReq> legs=Collections.nCopies(20,new LegReq(new Point(37.5,127.0),new Point(37.51,127.01),"synthetic start","synthetic goal"));
            long start=System.nanoTime();
            assertThat(client.fetchRoutes("walk",legs)).isNull();
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertThat(elapsed).isBetween(700L,1900L);
            assertThat(requests.get()).isEqualTo(1); assertThat(sizes).containsExactly(20);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
