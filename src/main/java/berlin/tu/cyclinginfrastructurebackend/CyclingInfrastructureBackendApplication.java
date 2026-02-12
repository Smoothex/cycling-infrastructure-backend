package berlin.tu.cyclinginfrastructurebackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class CyclingInfrastructureBackendApplication {

    static void main(String[] args) {
        SpringApplication.run(CyclingInfrastructureBackendApplication.class, args);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

}
