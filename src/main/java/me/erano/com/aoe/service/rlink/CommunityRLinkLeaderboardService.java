package me.erano.com.aoe.service.rlink;

import tools.jackson.databind.JsonNode;
import me.erano.com.aoe.data.LeaderboardEntry;
import me.erano.com.aoe.data.LeaderboardType;
import me.erano.com.aoe.data.MatchType;
import me.erano.com.aoe.data.Player;
import me.erano.com.aoe.repository.LeaderboardEntryRepository;
import me.erano.com.aoe.repository.LeaderboardTypeRepository;
import me.erano.com.aoe.repository.MatchTypeRepository;
import me.erano.com.aoe.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CommunityRLinkLeaderboardService {

    private static final Logger logger = LoggerFactory.getLogger(CommunityRLinkLeaderboardService.class);

    private final RestTemplate restTemplate;
    private final LeaderboardTypeRepository leaderboardTypeRepository;
    private final MatchTypeRepository matchTypeRepository;
    private final PlayerRepository playerRepository;
    private final LeaderboardEntryRepository leaderboardEntryRepository;

    // Self-injection via ObjectProvider: Spring proxy üzerinden method çağrısı için
    // Bu sayede @Transactional(REQUIRES_NEW) düzgün çalışır
    // ObjectProvider circular dependency problemini çözer
    private final ObjectProvider<CommunityRLinkLeaderboardService> selfProvider;

    // EntityManager: Session yönetimi için (persist edilmiş ama save olmamış entity'leri temizlemek için)
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static final String AOE_API_BASE_URL = "https://aoe-api.worldsedgelink.com/community/leaderboard";

    // Rate limiting: Community API limit = 50 req/s
    // 50 req/s = 1000ms / 50 = 20ms per request
    // Güvenli olmak için 21ms kullan (49 req/s ~ rate limit altında)
    private static final long REQUEST_DELAY_MS = 21;

    // ExecutorService: 6 thread pool (6 leaderboard = 6 thread optimal)
    // Her leaderboard sequential batch processing kullandığı için, concurrent batch işleme yapmıyoruz
    private final ExecutorService executorService = Executors.newFixedThreadPool(6);

    // Rate limiter: Global olarak max 6 concurrent request (her leaderboard 1 request)
    // Delay ile birlikte bu, 50 req/s limitini aşmayı önler
    // 6 thread * (1000ms / 21ms) = ~47 req/s (safe limit altında)
    private final Semaphore apiRateLimiter = new Semaphore(6);

    // Player insert lock: Aynı player ID için concurrent insert'leri seri hale getir
    // ConcurrentHashMap<PlayerId, Lock> kullanarak player-level locking
    private final ConcurrentHashMap<Long, Object> playerInsertLocks = new ConcurrentHashMap<>();

    @Autowired
    public CommunityRLinkLeaderboardService(RestTemplate restTemplate, LeaderboardTypeRepository leaderboardTypeRepository, MatchTypeRepository matchTypeRepository, PlayerRepository playerRepository, LeaderboardEntryRepository leaderboardEntryRepository, ObjectProvider<CommunityRLinkLeaderboardService> selfProvider) {
        this.restTemplate = restTemplate;
        this.leaderboardTypeRepository = leaderboardTypeRepository;
        this.matchTypeRepository = matchTypeRepository;
        this.playerRepository = playerRepository;
        this.leaderboardEntryRepository = leaderboardEntryRepository;
        this.selfProvider = selfProvider;
    }

    /**
     * Tüm desteklenen oyunları sırayla senkronize et
     * Her oyun için önce leaderboard tiplerini, sonra leaderboard verilerini çek
     */
    public boolean syncAllGames(List<String> gameKeys) {
        logger.info("=== TÜM OYUNLAR İÇİN SENKRONİZASYON BAŞLIYOR ===");
        logger.info("Oyunlar: {}", gameKeys);

        long totalStartTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (String gameKey : gameKeys) {
            try {
                logger.info(">>> Oyun '{}' senkronizasyonu başlıyor...", gameKey);
                long gameStartTime = System.currentTimeMillis();

                // 1. Önce leaderboard tiplerini ve match tiplerini çek
                boolean typesResult = processAvailableLeaderboardTypesAndMatchTypes(gameKey);
                if (!typesResult) {
                    logger.error("Oyun '{}' için leaderboard tipleri alınamadı, bu oyun atlanıyor.", gameKey);
                    failCount++;
                    continue;
                }

                // 2. Sonra leaderboard verilerini çek
                boolean dataResult = syncLeaderboards(gameKey);
                if (!dataResult) {
                    logger.error("Oyun '{}' için leaderboard verileri alınamadı.", gameKey);
                    failCount++;
                    continue;
                }

                long gameElapsedTime = System.currentTimeMillis() - gameStartTime;
                logger.info("<<< Oyun '{}' senkronizasyonu TAMAMLANDI! Süre: {}ms (~{}s)",
                           gameKey, gameElapsedTime, gameElapsedTime / 1000);
                successCount++;

            } catch (Exception e) {
                logger.error("Oyun '{}' senkronizasyonunda HATA: {}", gameKey, e.getMessage(), e);
                failCount++;
            }
        }

        long totalElapsedTime = System.currentTimeMillis() - totalStartTime;
        logger.info("=== TÜM OYUNLAR SENKRONİZASYONU TAMAMLANDI ===");
        logger.info("Toplam süre: {}ms (~{}s)", totalElapsedTime, totalElapsedTime / 1000);
        logger.info("Başarılı: {}, Başarısız: {}, Toplam: {}", successCount, failCount, gameKeys.size());

        return failCount == 0;
    }

    //@Scheduled(initialDelay = 10000, fixedRate = 300000)
    public boolean syncLeaderboards(String gameKey) {
        // DB'den bu oyun için kayıtlı leaderboard tiplerini al
        List<LeaderboardType> leaderboardTypes = leaderboardTypeRepository.getAllByGameKeySorted(gameKey);

        if (leaderboardTypes.isEmpty()) {
            logger.warn("Oyun '{}' için hiç leaderboard tipi bulunamadı. Önce processAvailableLeaderboardTypesAndMatchTypes() çalıştırın.", gameKey);
            return false;
        }

        // Sadece rlink ID'leri al
        List<Long> leaderBoardIds = leaderboardTypes.stream()
                .map(LeaderboardType::getRlinkId)
                .toList();

        logger.info("Oyun '{}' için {} leaderboard senkronize edilecek: {}", gameKey, leaderBoardIds.size(), leaderBoardIds);

        final int BATCH_SIZE = 200;

        long startTime = System.currentTimeMillis();

        // Her leaderboard'ı paralel işle (farklı connection pool'lar kullan)
        List<CompletableFuture<Void>> leaderboardFutures = new ArrayList<>();

        for (Long leaderboardId : leaderBoardIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    logger.info("Leaderboard {} için senkronizasyon başlıyor ({})", leaderboardId, gameKey);
                    syncLeaderboardSequential(gameKey, leaderboardId, BATCH_SIZE);
                    logger.info("Leaderboard {} senkronizasyonu tamamlandı.", leaderboardId);
                } catch (Exception e) {
                    logger.error("Leaderboard {} senkronizasyonunda kritik hata: {}", leaderboardId, e.getMessage(), e);
                }
            }, executorService);
            leaderboardFutures.add(future);
        }

        // Tüm leaderboard'ların bitmesini bekle
        CompletableFuture.allOf(leaderboardFutures.toArray(new CompletableFuture[0])).join();

        long elapsedTime = System.currentTimeMillis() - startTime;
        logger.info("TÜM Leaderboard senkronizasyonu tamamlandı ({}). Geçen süre: {}ms (~{}s)", gameKey, elapsedTime, elapsedTime / 1000);
        return true;
    }

    /**
     * Her leaderboard'ın batch'lerini SEQUENTIAL işle
     * Böylece table lock timeout'ları önleriz
     * Leaderboard'lar arasında paralelizm var (farklı leaderboard'lar farklı thread'lerde)
     */
    private void syncLeaderboardSequential(String gameKey, Long leaderboardId, int batchSize) {
        AtomicInteger totalPlayers = new AtomicInteger(0);

        try {
            // İlk batch'i al ve process et
            JsonNode firstBatch = getRequestForLeaderBoard2JSONWithRateLimit(gameKey, leaderboardId, 0, 1, batchSize);

            if (!(firstBatch != null && firstBatch.has("result") &&
                    firstBatch.get("result").has("message") &&
                    "SUCCESS".equals(firstBatch.get("result").get("message").asText()))) {
                logger.warn("Leaderboard {} için veri alınamadı", leaderboardId);
                return;
            }

            // İlk batch'i process et (Spring proxy üzerinden - REQUIRES_NEW transaction için)
            int firstBatchCount = selfProvider.getObject().processLeaderboardDataBatch(gameKey, leaderboardId, firstBatch);
            totalPlayers.addAndGet(firstBatchCount);
            logger.info("Leaderboard {}: Batch 1 işlendi. Oyuncu: {}", leaderboardId, firstBatchCount);

            // Eğer daha fazla veri varsa, kalan batch'leri SEQUENTIAL (sırayla) işle
            // TÜM DATA bitene kadar devam et (57k+ oyuncu için gerekli)
            if (firstBatchCount == batchSize) {
                int batchNumber = 2;
                while (true) {
                    final int start = 1 + ((batchNumber - 1) * batchSize);
                    final int batchNum = batchNumber;

                    try {
                        // Sequential: bunu await et, sonra devam et
                        JsonNode batchData = getRequestForLeaderBoard2JSONWithRateLimit(gameKey, leaderboardId, 0, start, batchSize);
                        if (!(batchData != null && batchData.has("result") &&
                                batchData.get("result").has("message") &&
                                "SUCCESS".equals(batchData.get("result").get("message").asText()))) {
                            logger.warn("Leaderboard {} Batch {} veri alınamadı, durduruluyor", leaderboardId, batchNum);
                            break;
                        }

                        int processedCount = selfProvider.getObject().processLeaderboardDataBatch(gameKey, leaderboardId, batchData);
                        totalPlayers.addAndGet(processedCount);

                        // Her 10 batch'te bir progress log
                        if (batchNum % 10 == 0) {
                            logger.info("Leaderboard {}: Batch {} işlendi. Toplam oyuncu: {}", leaderboardId, batchNum, totalPlayers.get());
                        } else {
                            logger.debug("Leaderboard {}: Batch {} işlendi. Oyuncu: {}", leaderboardId, batchNum, processedCount);
                        }

                        // Eğer az oyuncu varsa (batch dolu değil), veri bitti demektir
                        if (processedCount < batchSize) {
                            logger.info("Leaderboard {}: Son batch ({}) işlendi. Veri tamamlandı.", leaderboardId, batchNum);
                            break;
                        }

                        batchNumber++;
                    } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                        // Transaction rollback hatası - log ve devam et
                        logger.error("Leaderboard {} Batch {} transaction rollback: {}", leaderboardId, batchNum, e.getMessage());
                        batchNumber++;

                        // Çok fazla ardışık hata varsa dur (infinite loop'tan kaçın)
                        if (batchNum > 1000) {
                            logger.error("Leaderboard {}: 1000 batch limit'ine ulaşıldı, muhtemelen bir problem var, durduruluyor", leaderboardId);
                            break;
                        }
                    } catch (Exception e) {
                        logger.error("Leaderboard {} Batch {} işlenirken hata: {}", leaderboardId, batchNum, e.getMessage());
                        // Hata olsa bile devam et (transient error olabilir)
                        batchNumber++;

                        // Çok fazla ardışık hata varsa dur (infinite loop'tan kaçın)
                        if (batchNum > 1000) {
                            logger.error("Leaderboard {}: 1000 batch limit'ine ulaşıldı, muhtemelen bir problem var, durduruluyor", leaderboardId);
                            break;
                        }
                    }
                }
            }

            logger.info("Leaderboard {} için tüm veriler alındı. Toplam oyuncu: {}", leaderboardId, totalPlayers.get());

        } catch (Exception e) {
            logger.error("Leaderboard {} senkronizasyonunda hata: {}", leaderboardId, e.getMessage(), e);
        }
    }

    /**
     * Optimized batch processing: tüm oyuncuları topla, sonra batch işle
     * REQUIRES_NEW: Her batch ayrı transaction'da - bir batch fail ederse diğerleri etkilenmez
     * noRollbackFor: Exception'lar transaction'ı rollback-only yapmaz (error recovery için)
     */
    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            noRollbackFor = {
                    org.springframework.dao.DataIntegrityViolationException.class,
                    jakarta.persistence.PersistenceException.class,
                    java.sql.SQLException.class,
                    Exception.class
            }
    )
    public int processLeaderboardDataBatch(String gameKey, Long leaderboardId, JsonNode jsonNode) {
        LeaderboardType leaderboardType = leaderboardTypeRepository.findByGameKeyAndRlinkId(gameKey, leaderboardId)
                .orElseThrow(() -> new RuntimeException("Sıralama tablosu tipi bulunamadı: gameKey=" + gameKey + ", rlinkId=" + leaderboardId));

        // Tüm verileri topla
        Map<Long, JsonNode> profiles = new HashMap<>();
        for (JsonNode statGroupElement : jsonNode.get("statGroups")) {
            long statGroupId = statGroupElement.get("id").asLong();
            JsonNode membersNode = statGroupElement.get("members").get(0);
            profiles.put(statGroupId, membersNode);
        }

        Map<Long, Player> playerMap = new HashMap<>();
        Map<Long, LeaderboardEntry> playerIdToEntryMap = new HashMap<>();

        // API'den gelen raw data sayısını tut
        int apiDataCount = 0;

        for (JsonNode playerLeaderboardStatElement : jsonNode.get("leaderboardStats")) {
            apiDataCount++;

            Long statGroupId = playerLeaderboardStatElement.get("statgroup_id").asLong();
            if (profiles.containsKey(statGroupId)) {
                JsonNode profileNode = profiles.get(statGroupId);
                Long profileId = profileNode.get("profile_id").asLong();
                String alias = profileNode.get("alias").asText();
                Long personalStatGroupId = profileNode.get("personal_statgroup_id").asLong();
                String country = profileNode.has("country") ? profileNode.get("country").asText() : "";
                String clanName = profileNode.has("clanlist_name") ? profileNode.get("clanlist_name").asText() : "";

                // Player'ı map'e koy (duplicate key'ler silinecek)
                if (!playerMap.containsKey(profileId)) {
                    Player player = new Player();
                    player.setId(profileId);
                    player.setAlias(alias);
                    player.setPersonalStatGroupId(personalStatGroupId);
                    player.setStatGroupId(statGroupId);
                    player.setCountry(country);
                    player.setClanName(clanName);
                    playerMap.put(profileId, player);
                }

                int wins = playerLeaderboardStatElement.get("wins").asInt();
                int losses = playerLeaderboardStatElement.get("losses").asInt();
                int streak = playerLeaderboardStatElement.get("streak").asInt();
                int disputes = playerLeaderboardStatElement.get("disputes").asInt();
                int drops = playerLeaderboardStatElement.get("drops").asInt();
                int rating = playerLeaderboardStatElement.get("rating").asInt();
                int highestRating = playerLeaderboardStatElement.get("highestrating").asInt();

                // LeaderboardEntry oluştur
                LeaderboardEntry entry = new LeaderboardEntry();
                entry.setGameKey(gameKey);
                entry.setPlayer(playerMap.get(profileId)); // Mapped player'ı kullan
                entry.setLeaderboardType(leaderboardType);
                entry.setRating(rating);
                entry.setWin(wins);
                entry.setLoss(losses);
                entry.setGameCount(wins + losses);
                entry.setWinStreak(streak);
                entry.setWinRate(wins > 0 ? (wins * 100) / (wins + losses) : 0);
                entry.setMaxElo(highestRating);
                entry.setDisputes(disputes);
                entry.setDrops(drops);

                playerIdToEntryMap.put(profileId, entry);
            }
        }

        // Step 1: Tüm player ID'lerini topla ve DB'den varolan player'ları bulk olarak yükle
        Map<Long, Player> existingPlayersMap = new HashMap<>();
        if (!playerMap.isEmpty()) {
            List<Long> playerIds = new ArrayList<>(playerMap.keySet());
            List<Player> existingPlayers = playerRepository.findAllById(playerIds);
            for (Player p : existingPlayers) {
                existingPlayersMap.put(p.getId(), p);
            }
            logger.debug("Leaderboard {}: {}/{} player DB'de mevcut", leaderboardId, existingPlayers.size(), playerIds.size());
        }

        // Step 2: Sadece yeni player'ları kaydet (DB'de olmayanlar)
        List<Player> newPlayersToSave = new ArrayList<>();
        for (Map.Entry<Long, Player> mapEntry : playerMap.entrySet()) {
            Long playerId = mapEntry.getKey();
            if (!existingPlayersMap.containsKey(playerId)) {
                newPlayersToSave.add(mapEntry.getValue());
            }
        }

        // Yeni player'ları database-level MERGE ile kaydet (atomic upsert - concurrent insert safe)
        // Player-level locking ile deadlock'ları önle
        boolean hadPlayerInsertError = false;
        if (!newPlayersToSave.isEmpty()) {
            int savedCount = 0;

            for (Player player : newPlayersToSave) {
                // Player ID bazlı lock al (concurrent insert'leri serialize et)
                Object playerLock = playerInsertLocks.computeIfAbsent(player.getId(), k -> new Object());

                synchronized (playerLock) {
                    try {
                        // Önce DB'de var mı kontrol et (başka thread insert etmiş olabilir)
                        Player existingPlayer = playerRepository.findById(player.getId()).orElse(null);
                        if (existingPlayer != null) {
                            // Zaten var, map'e ekle
                            existingPlayersMap.put(existingPlayer.getId(), existingPlayer);
                            savedCount++;
                            continue;
                        }

                        // H2 MERGE INTO: atomic upsert - concurrent insert safe
                        // Eğer key varsa update, yoksa insert (idempotent)
                        String mergeSQL = "MERGE INTO player (id, alias, clan_name, country, name, personal_stat_group_id, stat_group_id) " +
                                          "KEY (id) " +
                                          "VALUES (:id, :alias, :clan_name, :country, :name, :personal_stat_group_id, :stat_group_id)";

                        int rowsAffected = entityManager.createNativeQuery(mergeSQL)
                                .setParameter("id", player.getId())
                                .setParameter("alias", player.getAlias())
                                .setParameter("clan_name", player.getClanName())
                                .setParameter("country", player.getCountry())
                                .setParameter("name", player.getName())
                                .setParameter("personal_stat_group_id", player.getPersonalStatGroupId())
                                .setParameter("stat_group_id", player.getStatGroupId())
                                .executeUpdate();

                        savedCount += rowsAffected;

                        // Player'ı DB'den yükle (managed entity için gerekli)
                        Player savedPlayer = playerRepository.findById(player.getId()).orElse(null);
                        if (savedPlayer != null) {
                            existingPlayersMap.put(savedPlayer.getId(), savedPlayer);
                        }
                    } catch (Exception e) {
                        hadPlayerInsertError = true;
                        logger.error("Leaderboard {}: Player {} MERGE hatası: {}", leaderboardId, player.getId(), e.getMessage());

                        // Fallback: DB'den yükle (başka thread kaydetti olabilir)
                        try {
                            Player existingPlayer = playerRepository.findById(player.getId()).orElse(null);
                            if (existingPlayer != null) {
                                existingPlayersMap.put(existingPlayer.getId(), existingPlayer);
                            }
                        } catch (Exception fallbackEx) {
                            logger.error("Leaderboard {}: Player {} fallback hatası: {}", leaderboardId, player.getId(), fallbackEx.getMessage());
                        }
                    } finally {
                        // Lock'u temizle (memory leak'i önle)
                        playerInsertLocks.remove(player.getId(), playerLock);
                    }
                }
            }

            logger.info("Leaderboard {}: {}/{} player MERGE edildi",
                       leaderboardId, savedCount, newPlayersToSave.size());
        }

        // Player insert hatası olduysa Session'ı clear et (null ID'li entity'lerden kurtul)
        if (hadPlayerInsertError) {
            logger.info("Leaderboard {}: Player insert hatası nedeniyle Session temizleniyor...", leaderboardId);
            entityManager.clear();

            // Session clear sonrası: existingPlayersMap'teki tüm entity'ler detached
            // Tüm player'ları DB'den yeniden yükle
            existingPlayersMap.clear();
            List<Long> allPlayerIds = new ArrayList<>(playerMap.keySet());
            List<Player> reloadedPlayers = playerRepository.findAllById(allPlayerIds);
            for (Player p : reloadedPlayers) {
                existingPlayersMap.put(p.getId(), p);
            }

            // ÖNEMLI: playerIdToEntryMap'teki tüm entry'leri YENİDEN OLUŞTUR
            // Çünkü eski entry'ler detached oldu ve null ID'li olabilirler
            Map<Long, LeaderboardEntry> newPlayerIdToEntryMap = new HashMap<>();
            for (Map.Entry<Long, LeaderboardEntry> mapEntry : playerIdToEntryMap.entrySet()) {
                Long playerId = mapEntry.getKey();
                LeaderboardEntry oldEntry = mapEntry.getValue();

                // Reload edilmiş player'ı al (KRITIK: Player set edilmeli!)
                Player reloadedPlayer = existingPlayersMap.get(playerId);
                if (reloadedPlayer == null) {
                    logger.warn("Leaderboard {}: Player {} reload edilemedi, entry skip", leaderboardId, playerId);
                    continue;
                }

                // Yeni LeaderboardEntry oluştur (fresh - Session'da yok)
                LeaderboardEntry newEntry = new LeaderboardEntry();
                newEntry.setGameKey(gameKey);
                newEntry.setPlayer(reloadedPlayer); // ← KRITIK: Player'ı set et!
                newEntry.setLeaderboardType(leaderboardType); // Bu managed entity
                newEntry.setRating(oldEntry.getRating());
                newEntry.setWin(oldEntry.getWin());
                newEntry.setLoss(oldEntry.getLoss());
                newEntry.setGameCount(oldEntry.getGameCount());
                newEntry.setWinStreak(oldEntry.getWinStreak());
                newEntry.setWinRate(oldEntry.getWinRate());
                newEntry.setMaxElo(oldEntry.getMaxElo());
                newEntry.setDisputes(oldEntry.getDisputes());
                newEntry.setDrops(oldEntry.getDrops());

                newPlayerIdToEntryMap.put(playerId, newEntry);
            }
            playerIdToEntryMap = newPlayerIdToEntryMap;

            logger.info("Leaderboard {}: Session cleared - {}/{} player reload edildi, {} entry yeniden oluşturuldu",
                       leaderboardId, reloadedPlayers.size(), allPlayerIds.size(), playerIdToEntryMap.size());
        }

        // Step 3: LeaderboardEntry'leri save et
        if (!playerIdToEntryMap.isEmpty()) {
            List<LeaderboardEntry> entriesToSave = new ArrayList<>(playerIdToEntryMap.values());

            int savedCount = 0;
            for (LeaderboardEntry entry : entriesToSave) {
                try {
                    // Null check: Player null olamaz
                    if (entry.getPlayer() == null) {
                        logger.error("Leaderboard {}: Entry'nin player'ı null, skip ediliyor", leaderboardId);
                        continue;
                    }

                    Long playerId = entry.getPlayer().getId();

                    // Null check: Player ID null olamaz
                    if (playerId == null) {
                        logger.error("Leaderboard {}: Entry'nin player ID'si null, skip ediliyor", leaderboardId);
                        continue;
                    }

                    // Player'ı DB'den yüklenmiş map'ten al
                    Player dbPlayer = existingPlayersMap.get(playerId);
                    if (dbPlayer == null) {
                        // Map'te yoksa DB'den tekrar dene (concurrent insert olabilir)
                        dbPlayer = playerRepository.findById(playerId).orElse(null);
                        if (dbPlayer == null) {
                            logger.error("Leaderboard {}: Player {} bulunamadı, entry skip ediliyor", leaderboardId, playerId);
                            continue;
                        }
                        existingPlayersMap.put(playerId, dbPlayer);
                    }

                    // Varolan entry var mı kontrol et
                    java.util.Optional<LeaderboardEntry> existingEntry = leaderboardEntryRepository
                            .findByPlayerAndLeaderboardType(dbPlayer, entry.getLeaderboardType());

                    LeaderboardEntry entryToSave;
                    if (existingEntry.isPresent()) {
                        // Update varolan entry
                        entryToSave = existingEntry.get();
                        entryToSave.setRating(entry.getRating());
                        entryToSave.setWin(entry.getWin());
                        entryToSave.setLoss(entry.getLoss());
                        entryToSave.setGameCount(entry.getGameCount());
                        entryToSave.setWinStreak(entry.getWinStreak());
                        entryToSave.setWinRate(entry.getWinRate());
                        entryToSave.setMaxElo(entry.getMaxElo());
                        entryToSave.setDisputes(entry.getDisputes());
                        entryToSave.setDrops(entry.getDrops());
                    } else {
                        // Create new entry
                        entryToSave = entry;
                        entryToSave.setPlayer(dbPlayer);
                    }

                    leaderboardEntryRepository.saveAndFlush(entryToSave);
                    savedCount++;
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // Duplicate key - concurrent insert olmuş olabilir, skip et
                    logger.warn("Leaderboard {}: Entry save duplicate key (concurrent insert?), skip", leaderboardId);
                } catch (jakarta.persistence.PersistenceException e) {
                    logger.error("Leaderboard {}: Entry save PersistenceException: {}", leaderboardId, e.getMessage());
                } catch (Exception e) {
                    logger.error("Leaderboard {}: Entry save FAILED: {}", leaderboardId, e.getMessage(), e);
                }
            }
            int failedCount = entriesToSave.size() - savedCount;
            logger.info("Leaderboard {}: {}/{} entry kaydedildi, {} FAILED", leaderboardId, savedCount, entriesToSave.size(), failedCount);
        }

        return apiDataCount;
    }

    /**
     * Rate-limited API çağrısı: Semaphore ile request sayısını kontrol et
     * 50 req/s = 20ms per request
     */
    private JsonNode getRequestForLeaderBoard2JSONWithRateLimit(String gameKey, Long leaderboardId, int sortBy, int start, int count) {
        try {
            // Semaphore'u al (timeout ile, deadlock'tan kaçın)
            boolean acquired = apiRateLimiter.tryAcquire(1, 30, TimeUnit.SECONDS);
            if (!acquired) {
                logger.warn("Rate limiter timeout: {} seconds bekledi", 30);
                throw new RuntimeException("Rate limiter timeout");
            }

            try {
                // Delay ile request'i gönder (20ms = 50 req/s)
                Thread.sleep(REQUEST_DELAY_MS);
                return getRequestForLeaderBoard2JSON(gameKey, leaderboardId, sortBy, start, count);
            } finally {
                // Semaphore'u her halükarda bırak
                apiRateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rate limiter interrupted", e);
        }
    }

    //https://aoe-api.worldsedgelink.com/community/leaderboard/getLeaderBoard2?leaderboard_id=6&platform=PC_STEAM&title=age3&sortBy=0&start=1&count=25
    public JsonNode getRequestForLeaderBoard2JSON(String gameKey, Long leaderboardId, int sortBy, int start, int count) {
        if (gameKey == null || gameKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Game key belirtilmelidir");
        }
        if (leaderboardId == null || leaderboardId <= 0) {
            throw new IllegalArgumentException("Geçersiz leaderboard ID: " + leaderboardId);
        }
        if (count <= 0 || count > 200) {
            throw new IllegalArgumentException("Count 1-100 aralığında olmalıdır");
        }

        try {
            // UriComponentsBuilder ile güvenli URL oluşturma
            String url = UriComponentsBuilder.fromUriString(AOE_API_BASE_URL + "/getLeaderBoard2")
                    .queryParam("leaderboard_id", leaderboardId)
                    .queryParam("platform", "PC_STEAM")
                    .queryParam("title", gameKey)
                    .queryParam("sortBy", sortBy)
                    .queryParam("start", start)
                    .queryParam("count", count)
                    .build()
                    .encode()
                    .toUriString();

            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                throw new RuntimeException("API'den veri alınamadı. Durum kodu: " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Liderlik tabloları alınırken bir hata oluştu: " + e.getMessage(), e);
        }

    }

    //age1, age2, age3, age4
    // for example: https://aoe-api.worldsedgelink.com/community/leaderboard/GetAvailableLeaderboards?title=age3
    public JsonNode getRequestForAvailableLeaderboardsJSON(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title belirtilmelidir");
        }

        try {
            // UriComponentsBuilder ile güvenli URL oluşturma
            String url = UriComponentsBuilder.fromUriString(AOE_API_BASE_URL + "/GetAvailableLeaderboards")
                    .queryParam("title", title)
                    .build()
                    .encode()
                    .toUriString();

            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                throw new RuntimeException("API'den veri alınamadı. Durum kodu: " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Liderlik tablo çeşitleri alınırken bir hata oluştu: " + e.getMessage(), e);
        }
    }

    @Transactional
    public boolean processAvailableLeaderboardTypesAndMatchTypes(String gameKey) {
        JsonNode jsonNode = getRequestForAvailableLeaderboardsJSON(gameKey);

        if (!(jsonNode.has("result") && jsonNode.get("result").has("message")
                && "SUCCESS".equals(jsonNode.get("result").get("message").asText()))) {
            return false;
        }

        JsonNode matchTypesNode = jsonNode.get("matchTypes");
        for (JsonNode node : matchTypesNode) {
            Long rlinkId = node.get("id").asLong();
            String name = node.get("name").asText();

            MatchType matchType = matchTypeRepository
                    .findByGameKeyAndRlinkId(gameKey, rlinkId)
                    .orElse(new MatchType(gameKey, rlinkId, name));
            matchType.setName(name);
            matchTypeRepository.save(matchType);
        }

        JsonNode leaderboardsNode = jsonNode.get("leaderboards");
        for (JsonNode node : leaderboardsNode) {
            Long rlinkId = node.get("id").asLong();
            String name = node.get("name").asText();

            LeaderboardType leaderboardType = leaderboardTypeRepository
                    .findByGameKeyAndRlinkId(gameKey, rlinkId)
                    .orElse(new LeaderboardType(gameKey, rlinkId, name));
            leaderboardType.setName(name);
            leaderboardTypeRepository.save(leaderboardType);
            JsonNode leaderboardMapNode = node.get("leaderboardmap");
            for (JsonNode subnode : leaderboardMapNode) {
                Long matchTypeRlinkId = subnode.get("matchtype_id").asLong();

                matchTypeRepository.findByGameKeyAndRlinkId(gameKey, matchTypeRlinkId).ifPresent(matchType -> {
                    matchType.setLeaderboardType(leaderboardType);
                    matchTypeRepository.save(matchType);
                });
            }
        }
        return true;
    }
}
