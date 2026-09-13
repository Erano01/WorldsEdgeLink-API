package me.erano.com.aoe;

import me.erano.com.aoe.service.rlink.CommunityRLinkLeaderboardService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.Executor;

//https://wiki.librematch.org/rlink/community/leaderboard/start
//https://wiki.librematch.org/rlink/community/start

@SpringBootApplication
//@EnableScheduling
@EnableAsync
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Core thread sayısını processor sayısına göre ayarla
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        // Maksimum thread sayısı - API limitlerini göz önünde bulundurun
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("LeaderboardFetcher-");
        executor.initialize();
        return executor;
    }



    /*@Bean
    public CommandLineRunner getAvailableLeaderboardsAndMatchTypesJSON(CommunityRLinkLeaderboardService rLinkLeaderboardService) {
        return args -> {
            //JsonNode result = rLinkLeaderboardService.getAvailableLeaderboardsJSON("age3");
            JsonNode result = rLinkLeaderboardService.getLeaderBoard2JSON(1L,0,1,200);
            ObjectMapper mapper = new ObjectMapper();
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            System.out.println(prettyJson);
        };
    }*/

    @Bean
    public CommandLineRunner getAvailableLeaderboardsAndMatchTypes(CommunityRLinkLeaderboardService communityRLinkLeaderboardService) {
        return args -> {
            // Tüm desteklenen Age of Empires oyunları
            List<String> gameKeys = List.of("age1", "age2", "age3", "age4");

            System.out.println("==============================================");
            System.out.println("TÜM OYUNLAR İÇİN SENKRONİZASYON BAŞLIYOR");
            System.out.println("Oyunlar: " + gameKeys);
            System.out.println("==============================================");

            boolean result = communityRLinkLeaderboardService.syncAllGames(gameKeys);

            if (result) {
                System.out.println("✅ TÜM OYUNLARIN liderlik tablosu verileri başarıyla senkronize edildi!");
            } else {
                System.err.println("❌ Bazı oyunların liderlik tablosu verilerinde hata oluştu!");
            }

        };
    }


}
