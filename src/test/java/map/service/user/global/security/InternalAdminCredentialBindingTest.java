package map.service.user.global.security;

import java.util.Map;
import map.service.user.global.config.InternalAdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalAdminCredentialBindingTest {
    private InternalAdminProperties bind(Map<String,Object> values) throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("synthetic", values));
        for (var source : new YamlPropertySourceLoader().load("actual-application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return Binder.get(env).bind("internal.admin", InternalAdminProperties.class).get();
    }

    private void request(InternalAdminProperties props, String token, boolean allowed) throws Exception {
        var request = new MockHttpServletRequest("GET", "/internal/admin/moderation/reports");
        request.setRemoteAddr("10.1.1.1");
        request.addHeader("X-Internal-Token", token);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new InternalAdminGuardFilter(props).doFilter(request, response, chain);
        if (allowed) verify(chain).doFilter(request,response);
        else { assertEquals(403,response.getStatus()); verifyNoInteractions(chain); }
    }

    @Test void actualConfigurationNeverFallsBackToServingCredential() throws Exception {
        var props=bind(Map.of("INTERNAL_SERVICE_TOKEN","synthetic-serving"));
        assertEquals("",props.getToken());
        request(props,"synthetic-serving",false);
    }

    @Test void distinctDedicatedCredentialAuthorizesAndServingCredentialDoesNot() throws Exception {
        var props=bind(Map.of("INTERNAL_SERVICE_TOKEN","synthetic-serving","USER_ADMIN_INTERNAL_TOKEN","synthetic-admin"));
        request(props,"synthetic-serving",false);
        request(props,"synthetic-admin",true);
    }

    @Test void ReusedOrBlankDedicatedCredentialFailsClosed() throws Exception {
        for(String token : new String[]{"synthetic-serving"," ",""}) {
            request(bind(Map.of("INTERNAL_SERVICE_TOKEN","synthetic-serving","USER_ADMIN_INTERNAL_TOKEN",token)),token,false);
        }
    }
}
